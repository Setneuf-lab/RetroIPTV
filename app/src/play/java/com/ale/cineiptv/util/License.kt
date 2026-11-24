package com.ale.cineiptv.util

import android.app.Activity
import android.content.Context
import android.widget.Toast
import com.ale.cineiptv.R
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Versión Google Play: 7 días de prueba con todo abierto y, después, un pago único
 * ("versión completa", producto integrado [PRODUCT_ID] en Play Console) para seguir reproduciendo.
 *
 * - La prueba cuenta desde la primera instalación (también si se borran los datos de la app).
 *   Reinstalar la reinicia: Play solo da pruebas gratuitas "oficiales" a las suscripciones.
 * - Si el reloj del aparato se atrasa, no se recuperan días.
 * - La compra se guarda en el aparato (sirve sin conexión) y se vuelve a comprobar con Play
 *   al abrir la app: un reembolso la desactiva.
 */
object License {

    const val PRODUCT_ID = "version_completa"
    private const val TRIAL_DAYS = 7
    private const val DAY_MS = 86_400_000L
    private const val PREFS = "retro_license"
    private const val FALLBACK_PRICE = "4,99 €"

    private lateinit var app: Context
    private var client: BillingClient? = null
    private var product: ProductDetails? = null
    private var owned = false
    /** Qué hacer cuando termine la compra en curso. */
    private var onBought: (() -> Unit)? = null

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        val p = prefs()
        owned = p.getBoolean("owned", false)
        val installed = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).firstInstallTime
        }.getOrDefault(System.currentTimeMillis())
        val start = minOf(p.getLong("trial_start", Long.MAX_VALUE), installed, System.currentTimeMillis())
        p.edit().putLong("trial_start", start).apply()
        connect()
    }

    // ---------- Prueba ----------

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Tiempo de prueba gastado; el reloj nunca "vuelve atrás". */
    private fun elapsedMs(): Long {
        val p = prefs()
        val now = maxOf(System.currentTimeMillis(), p.getLong("last_seen", 0L))
        p.edit().putLong("last_seen", now).apply()
        return (now - p.getLong("trial_start", now)).coerceAtLeast(0L)
    }

    private fun daysLeft(): Int {
        val left = TRIAL_DAYS * DAY_MS - elapsedMs()
        return if (left <= 0) 0 else ((left + DAY_MS - 1) / DAY_MS).toInt()
    }

    fun canPlay(ctx: Context): Boolean {
        init(ctx)
        return owned || daysLeft() > 0
    }

    /** Texto del inicio: días de prueba que quedan, o que ha terminado. Nada si ya está comprada. */
    fun statusText(ctx: Context): String? {
        init(ctx)
        if (owned) return null
        val d = daysLeft()
        return if (d > 0) ctx.resources.getQuantityString(R.plurals.trial_days_left, d, d)
        else ctx.getString(R.string.trial_over_short)
    }

    fun settingsLabel(ctx: Context): String {
        init(ctx)
        return if (owned) ctx.getString(R.string.full_owned) else ctx.getString(R.string.full_buy_fmt, price())
    }

    fun onSettingsClick(activity: Activity, onUnlocked: () -> Unit) {
        if (owned) Toast.makeText(activity, R.string.full_thanks, Toast.LENGTH_LONG).show()
        else showPaywall(activity, onUnlocked)
    }

    private fun price() = product?.oneTimePurchaseOfferDetails?.formattedPrice ?: FALLBACK_PRICE

    // ---------- Pantalla de compra ----------

    fun showPaywall(activity: Activity, onUnlocked: () -> Unit) {
        init(activity)
        if (product == null) loadProduct()
        val d = daysLeft()
        val msg = if (d > 0) activity.resources.getQuantityString(R.plurals.paywall_trial_text, d, d, price())
        else activity.getString(R.string.paywall_over_text, price())
        MaterialAlertDialogBuilder(activity)
            .setTitle(if (d > 0) R.string.paywall_title else R.string.paywall_over_title)
            .setMessage(msg)
            .setPositiveButton(R.string.paywall_buy) { _, _ -> buy(activity, onUnlocked) }
            .setNeutralButton(R.string.paywall_restore) { _, _ -> restore(activity, onUnlocked) }
            .setNegativeButton(R.string.paywall_later, null)
            .show()
    }

    private fun buy(activity: Activity, onUnlocked: () -> Unit) {
        val c = client
        val pd = product
        if (c == null || !c.isReady || pd == null) {
            Toast.makeText(activity, R.string.billing_unavailable, Toast.LENGTH_LONG).show()
            if (c == null || !c.isReady) connect() else loadProduct()
            return
        }
        onBought = onUnlocked
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(pd).build()))
            .build()
        c.launchBillingFlow(activity, params)
    }

    /** "Ya lo compré": vuelve a preguntar a Play (otro aparato con la misma cuenta, reinstalación…). */
    private fun restore(activity: Activity, onUnlocked: () -> Unit) {
        val c = client
        if (c == null || !c.isReady) {
            Toast.makeText(activity, R.string.billing_unavailable, Toast.LENGTH_LONG).show()
            connect(); return
        }
        refreshPurchases { found ->
            if (activity.isFinishing) return@refreshPurchases
            if (found) {
                Toast.makeText(activity, R.string.full_thanks, Toast.LENGTH_LONG).show()
                onUnlocked()
            } else Toast.makeText(activity, R.string.restore_none, Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Google Play Billing ----------

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach { handle(it) }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refreshPurchases { if (it) finishBought() }
            BillingClient.BillingResponseCode.USER_CANCELED -> onBought = null
            else -> {
                onBought = null
                Toast.makeText(app, R.string.billing_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun connect() {
        val c = client ?: BillingClient.newBuilder(app)
            .setListener(purchasesListener)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
            .also { client = it }
        if (c.isReady) return
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    refreshPurchases(null)
                    loadProduct()
                }
            }
            override fun onBillingServiceDisconnected() = Unit
        })
    }

    private fun loadProduct() {
        val c = client?.takeIf { it.isReady } ?: return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()))
            .build()
        c.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                details.productDetailsList.firstOrNull()?.let { product = it }
            }
        }
    }

    /** Comprueba con Play si la versión completa está comprada; [done] recibe el resultado. */
    private fun refreshPurchases(done: ((Boolean) -> Unit)?) {
        val c = client?.takeIf { it.isReady } ?: return
        c.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        ) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            val p = purchases.firstOrNull {
                PRODUCT_ID in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED
            }
            setOwned(p != null)
            p?.let { acknowledge(it) }
            done?.invoke(p != null)
        }
    }

    private fun handle(p: Purchase) {
        if (PRODUCT_ID !in p.products) return
        when (p.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                acknowledge(p)
                setOwned(true)
                Toast.makeText(app, R.string.full_thanks, Toast.LENGTH_LONG).show()
                finishBought()
            }
            Purchase.PurchaseState.PENDING ->
                Toast.makeText(app, R.string.billing_pending, Toast.LENGTH_LONG).show()
            else -> Unit
        }
    }

    private fun finishBought() {
        val cb = onBought
        onBought = null
        cb?.invoke()
    }

    /** Play devuelve el dinero a los 3 días si la compra no se confirma. */
    private fun acknowledge(p: Purchase) {
        if (p.isAcknowledged) return
        client?.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()
        ) { }
    }

    private fun setOwned(value: Boolean) {
        owned = value
        prefs().edit().putBoolean("owned", value).apply()
    }
}
