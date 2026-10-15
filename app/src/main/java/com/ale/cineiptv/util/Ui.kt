package com.ale.cineiptv.util

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.view.View
import com.ale.cineiptv.ui.MainActivity

/** Pequeño zoom al recibir el foco del mando, típico de interfaces de TV. */
fun View.addFocusScale(scale: Float = 1.05f) {
    val previous = onFocusChangeListener
    setOnFocusChangeListener { v, hasFocus ->
        previous?.onFocusChange(v, hasFocus)
        val target = if (hasFocus) scale else 1f
        v.animate().scaleX(target).scaleY(target).setDuration(140).start()
        v.translationZ = if (hasFocus) 8f else 0f
    }
}

object AppRestarter {
    /** Reinicia el proceso por completo y vuelve a abrir la pantalla inicial. */
    fun restart(activity: Activity) {
        val component = ComponentName(activity, MainActivity::class.java)
        val intent = Intent.makeRestartActivityTask(component)
        activity.startActivity(intent)
        activity.finishAffinity()
        Runtime.getRuntime().exit(0)
    }
}
