package com.ale.cineiptv.util

import android.app.Activity
import android.content.Context

/**
 * Versión familia: sin prueba ni pagos, todo abierto siempre.
 * (La versión Google Play tiene su propio License con la prueba de 7 días y la compra.)
 */
@Suppress("UNUSED_PARAMETER")
object License {
    fun init(ctx: Context) = Unit
    fun canPlay(ctx: Context) = true
    fun statusText(ctx: Context): String? = null
    fun settingsLabel(ctx: Context): String? = null
    fun onSettingsClick(activity: Activity, onUnlocked: () -> Unit) = Unit
    fun showPaywall(activity: Activity, onUnlocked: () -> Unit) = Unit
}
