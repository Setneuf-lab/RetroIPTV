package com.ale.cineiptv.util

import android.app.Activity

/**
 * Versión de Google Play: las actualizaciones las hace la propia tienda.
 * No descarga ni instala nada (Play no permite que una app se instale a sí misma).
 */
object Updater {
    @Suppress("UNUSED_PARAMETER")
    fun onHomeResumed(activity: Activity) = Unit

    fun onHomePaused() = Unit
}
