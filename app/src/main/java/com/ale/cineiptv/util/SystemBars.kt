package com.ale.cineiptv.util

import android.graphics.Color
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Pantalla de extremo a extremo (obligatoria desde Android 15): la app se dibuja también detrás de la
 * barra de estado y la de navegación, y aquí se añade el margen justo para que nada quede tapado.
 * En las teles esas barras no existen, así que el margen es 0 y no cambia nada.
 */
object SystemBars {

    /** Llamar en onCreate antes de setContentView. */
    fun enable(activity: ComponentActivity) {
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
    }

    /** Añade al [root] el hueco de las barras del sistema (y del notch), conservando su padding propio. */
    fun pad(root: View) {
        val l = root.paddingLeft; val t = root.paddingTop
        val r = root.paddingRight; val b = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(l + s.left, t + s.top, r + s.right, b + s.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
