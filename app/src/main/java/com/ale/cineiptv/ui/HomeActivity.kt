package com.ale.cineiptv.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ale.cineiptv.R
import com.ale.cineiptv.data.Favorites
import com.ale.cineiptv.data.HistoryEntry
import com.ale.cineiptv.data.WatchHistory
import com.ale.cineiptv.data.XtreamAccount
import com.ale.cineiptv.ui.adapters.ContinueAdapter
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import com.ale.cineiptv.data.ParentalControl
import com.ale.cineiptv.data.PlaylistRepository
import com.ale.cineiptv.data.Prefs
import com.ale.cineiptv.databinding.ActivityHomeBinding
import com.ale.cineiptv.util.AppRestarter
import com.ale.cineiptv.util.Updater
import androidx.lifecycle.lifecycleScope
import com.ale.cineiptv.util.addFocusScale
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/** Menú principal: TV en Vivo, Películas, Series y Favoritos + acciones abajo. */
class HomeActivity : AppCompatActivity() {

    private lateinit var b: ActivityHomeBinding
    private lateinit var continueAdapter: ContinueAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Si Android cerró el proceso, la lista en memoria se pierde: volvemos a cargarla.
        if (!PlaylistRepository.isLoaded) {
            MainActivity.open(this)
            finish()
            return
        }
        Favorites.init(this)
        ParentalControl.init(this)
        WatchHistory.init(this)
        com.ale.cineiptv.data.PlayLog.init(this)
        com.ale.cineiptv.data.PlayLog.flush()   // lo que quedara pendiente de la última vez

        b = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(b.root)

        val prefs = Prefs(this)
        b.textDeviceId.text = getString(R.string.device_id_fmt, prefs.deviceId)
        b.textSource.text = sourceText(prefs.sourceLabel)

        updateCounts()
        val repo = PlaylistRepository
        b.cardLive.setOnClickListener { openSection(ContentActivity.Section.LIVE, repo.live.size) }
        b.cardMovies.setOnClickListener { openSection(ContentActivity.Section.MOVIE, movieCount()) }
        b.cardSeries.setOnClickListener { openSection(ContentActivity.Section.SERIES, seriesCount()) }
        b.cardFavorites.setOnClickListener { ContentActivity.start(this, ContentActivity.Section.FAVORITES) }

        listOf<View>(b.cardLive, b.cardMovies, b.cardSeries, b.cardFavorites).forEach { it.addFocusScale(1.06f) }

        b.btnChangeList.setOnClickListener { MainActivity.open(this, edit = true) }
        b.btnRefresh.setOnClickListener {
            PlaylistRepository.clear()
            MainActivity.open(this, refresh = true)
            finish()
        }
        b.btnRestart.setOnClickListener { confirmRestart() }
        b.btnParental.setOnClickListener { openSettings(prefs) }

        fitHeader()
        setupContinue()
        showExpiry(PlaylistRepository.account)
        // Caducidad actualizada (también para listas M3U de servidores Xtream)
        lifecycleScope.launch {
            showExpiry(PlaylistRepository.refreshAccount(prefs.m3uUrl, prefs.effectiveUserAgent))
            b.textSource.text = sourceText(prefs.sourceLabel)
        }

        b.cardLive.requestFocus()

        // Una vez al día la lista se actualiza sola en segundo plano
        PlaylistRepository.refreshDailyIfNeeded(this) { if (!isFinishing) updateCounts() }

        // ¿Hay una versión nueva en GitHub?
        Updater.checkOnStart(this, lifecycleScope)
    }

    override fun onResume() {
        super.onResume()
        Updater.resumePendingInstall(this)
        // Al volver al menú, el contenido protegido se vuelve a bloquear
        ParentalControl.sessionUnlocked = false
        if (::b.isInitialized) {
            b.countFavorites.text = if (Favorites.count == 0) getString(R.string.fav_hint)
            else countText(Favorites.count, R.string.unit_favorites)
            refreshContinue()
        }
    }

    /** Nombre de la lista + conexiones de la cuenta. */
    private fun sourceText(label: String): String {
        val acc = PlaylistRepository.account ?: return label
        if (acc.maxConnections <= 0) return label
        return label + "  ·  " + getString(R.string.account_cons_fmt, acc.maxConnections)
    }

    /** Etiqueta con la caducidad de la suscripción; ámbar si queda poco, roja si ha caducado. */
    private fun showExpiry(acc: XtreamAccount?) {
        if (acc == null) { b.textExpiry.isVisible = false; return }
        val now = System.currentTimeMillis()
        val expMs = acc.expiresAt * 1000
        val date = java.text.SimpleDateFormat("dd/MM/yyyy", Locale("es", "ES")).format(java.util.Date(expMs))
        val days = ((expMs - now) / 86_400_000L).toInt()
        val expired = acc.status.equals("Expired", ignoreCase = true) || (acc.expiresAt > 0 && expMs < now)
        val (text, color) = when {
            acc.expiresAt <= 0 && !expired -> getString(R.string.expiry_never) to R.color.text_secondary
            expired -> getString(R.string.expiry_expired, date) to R.color.danger
            days < 1 -> getString(R.string.expiry_today, java.text.SimpleDateFormat("HH:mm", Locale("es", "ES")).format(java.util.Date(expMs))) to R.color.danger
            days == 1 -> getString(R.string.expiry_one_day, date) to R.color.danger
            days <= 15 -> getString(R.string.expiry_days, date, days) to R.color.warn
            else -> getString(R.string.expiry_days, date, days) to R.color.text_primary
        }
        b.textExpiry.text = text
        b.textExpiry.setTextColor(getColor(color))
        b.textExpiry.isVisible = true
    }

    // ---------------------------------------------------------------- Seguir viendo

    private fun setupContinue() {
        continueAdapter = ContinueAdapter(
            onClick = { e -> resume(e) },
            onLongClick = { e -> confirmRemove(e) },
        )
        b.recyclerContinue.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        b.recyclerContinue.adapter = continueAdapter
        refreshContinue()
    }

    private fun refreshContinue() {
        if (!::continueAdapter.isInitialized) return
        // El contenido de grupos bloqueados (control parental) no se muestra
        val list = WatchHistory.entries().filter { !ParentalControl.isLocked(it.channel.group) }
        continueAdapter.submit(list)
        b.sectionContinue.isVisible = list.isNotEmpty()
    }

    private fun resume(e: HistoryEntry) {
        PlaylistRepository.playQueue = listOf(e.channel)
        PlaylistRepository.playShow = e.show
        PlayerActivity.start(this, 0, isLive = false, resume = true)
    }

    private fun confirmRemove(e: HistoryEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.continue_remove_title)
            .setMessage(getString(R.string.continue_remove_msg, e.channel.name))
            .setPositiveButton(R.string.continue_remove) { _, _ ->
                WatchHistory.remove(e.channel.url)
                refreshContinue()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * En móvil vertical no caben el título y las etiquetas (ID, caducidad) en una fila:
     * el título se quedaba aplastado letra a letra. Se apilan uno debajo de otro.
     */
    private fun fitHeader() {
        if (resources.configuration.screenWidthDp >= 600) return
        b.header.orientation = android.widget.LinearLayout.VERTICAL
        b.header.gravity = android.view.Gravity.START
        (b.headerTitle.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
            lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            lp.weight = 0f
            b.headerTitle.layoutParams = lp
        }
        b.headerBadges.gravity = android.view.Gravity.START
        (b.headerBadges.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.marginStart = 0
            lp.topMargin = (10 * resources.displayMetrics.density).toInt()
            b.headerBadges.layoutParams = lp
        }
    }

    // En modo Xtream las películas y series se cuentan por categorías (se cargan al abrirlas)
    private fun movieCount() = PlaylistRepository.let { if (it.isXtream) it.vodCategories.size else it.movies.size }
    private fun seriesCount() = PlaylistRepository.let { if (it.isXtream) it.seriesCategories.size else it.shows.size }

    private fun updateCounts() {
        val repo = PlaylistRepository
        b.countLive.text = countText(repo.live.size, R.string.unit_channels)
        b.countMovies.text = countText(movieCount(), if (repo.isXtream) R.string.unit_categories else R.string.unit_movies)
        b.countSeries.text = countText(seriesCount(), if (repo.isXtream) R.string.unit_categories else R.string.unit_series)
    }

    /** Ajustes: control parental y ajuste automático de la imagen. */
    private fun openSettings(prefs: Prefs) {
        val items = arrayOf(
            getString(R.string.settings_parental),
            getString(if (prefs.afrEnabled) R.string.settings_afr_on else R.string.settings_afr_off),
            getString(if (prefs.groupDuplicates) R.string.settings_group_on else R.string.settings_group_off),
            getString(R.string.settings_engine_fmt, getString(when (prefs.engineMode) {
                "vlc" -> R.string.engine_vlc_short
                "exo" -> R.string.engine_exo_short
                else -> R.string.engine_auto_short
            })),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> PinDialogs.openSettings(this)
                    1 -> {
                        prefs.afrEnabled = !prefs.afrEnabled
                        Toast.makeText(
                            this,
                            if (prefs.afrEnabled) R.string.settings_afr_enabled else R.string.settings_afr_disabled,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    3 -> chooseEngine(prefs)
                    2 -> {
                        prefs.groupDuplicates = !prefs.groupDuplicates
                        Toast.makeText(
                            this,
                            if (prefs.groupDuplicates) R.string.settings_group_on else R.string.settings_group_off,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Automático (VLC solo si el principal falla), siempre VLC o solo el principal. */
    private fun chooseEngine(prefs: Prefs) {
        val modes = arrayOf("auto", "vlc", "exo")
        val labels = arrayOf(getString(R.string.engine_auto), getString(R.string.engine_vlc), getString(R.string.engine_exo))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.engine_title)
            .setSingleChoiceItems(labels, modes.indexOf(prefs.engineMode).coerceAtLeast(0)) { d, which ->
                prefs.engineMode = modes[which]
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun countText(n: Int, unitRes: Int): String =
        String.format(Locale("es", "ES"), "%,d ", n) + getString(unitRes)

    private fun openSection(section: ContentActivity.Section, count: Int) {
        if (count == 0) {
            Toast.makeText(this, R.string.section_empty, Toast.LENGTH_SHORT).show()
            return
        }
        ContentActivity.start(this, section)
    }

    private fun confirmRestart() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restart_title)
            .setMessage(R.string.restart_message)
            .setPositiveButton(R.string.restart_confirm) { _, _ -> AppRestarter.restart(this) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
