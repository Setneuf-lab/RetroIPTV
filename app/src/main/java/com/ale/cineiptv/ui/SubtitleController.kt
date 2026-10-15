package com.ale.cineiptv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.ale.cineiptv.R
import com.ale.cineiptv.data.Prefs
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale
import kotlin.math.abs

/**
 * Subtítulos del reproductor:
 *  - elegir pista (las que ofrezca el proveedor dentro del vídeo) o desactivarlos
 *  - estilo: tamaño, tipo de letra, sombra/contorno/fondo y color (se recuerda)
 *  - sincronización: retrasar o adelantar los subtítulos (para el contenido actual)
 */
@OptIn(UnstableApi::class)
class SubtitleController(
    private val activity: PlayerActivity,
    private val playerView: PlayerView,
    private val prefs: Prefs,
    private val player: () -> ExoPlayer?,
    private val isLive: () -> Boolean,
) {
    /** Retraso de los subtítulos en ms (positivo = salen más tarde). Lo lee el hilo de reproducción. */
    @Volatile var delayMs: Long = 0L
        private set

    // ---------------------------------------------------------------- Reproductor

    /** Renderizadores del reproductor con el retraso de subtítulos aplicado. */
    fun renderersFactory(context: Context): DefaultRenderersFactory = DelayRenderersFactory(context) { delayMs * 1000 }

    /** Parámetros de pistas al empezar un contenido nuevo, según las preferencias guardadas. */
    fun initialParameters(current: TrackSelectionParameters): TrackSelectionParameters {
        val builder = current.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        return if (prefs.subEnabled) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setPreferredTextLanguage(prefs.subLang.ifEmpty { null })
                .setSelectUndeterminedTextLanguage(true)
                .build()
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        }
    }

    /** Aplica tamaño, letra, efecto y color guardados. */
    fun applyStyle() {
        val sv: SubtitleView = playerView.subtitleView ?: return
        sv.setApplyEmbeddedStyles(false)
        sv.setApplyEmbeddedFontSizes(false)
        val typeface = when (prefs.subFont) {
            1 -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            2 -> Typeface.SERIF
            3 -> Typeface.MONOSPACE
            else -> Typeface.SANS_SERIF
        }
        val foreground = if (prefs.subColor == 1) YELLOW else Color.WHITE
        val background = if (prefs.subEdge == 3) 0xB3000000.toInt() else Color.TRANSPARENT
        val edgeType = when (prefs.subEdge) {
            1 -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            2 -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            else -> CaptionStyleCompat.EDGE_TYPE_NONE
        }
        sv.setStyle(CaptionStyleCompat(foreground, background, Color.TRANSPARENT, edgeType, Color.BLACK, typeface))
        val scale = SIZE_SCALES.getOrElse(prefs.subSize) { 1f }
        sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
    }

    /** Se llama al cambiar de contenido: la sincronización es propia de cada vídeo. */
    fun resetDelay() {
        delayMs = 0L
    }

    // ---------------------------------------------------------------- Menús

    private class TextTrack(val label: String, val language: String?, val override: TrackSelectionOverride, val selected: Boolean)

    private fun textTracks(): List<TextTrack> {
        val p = player() ?: return emptyList()
        val out = ArrayList<TextTrack>()
        for (group in p.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                val lang = f.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
                val langName = lang?.let { Locale(it).getDisplayLanguage(Locale("es", "ES")) }
                    ?.replaceFirstChar { c -> c.uppercase() }
                val name = listOfNotNull(f.label?.takeIf { it.isNotBlank() }, langName).distinct().joinToString(" · ")
                    .ifEmpty { activity.getString(R.string.sub_track_fmt, out.size + 1) }
                out.add(TextTrack(name, lang, TrackSelectionOverride(group.mediaTrackGroup, i), group.isTrackSelected(i)))
            }
        }
        return out
    }

    fun openMenu() {
        val tracks = textTracks()
        val current = tracks.firstOrNull { it.selected }?.label
        val trackLine = when {
            tracks.isEmpty() -> activity.getString(R.string.sub_none_available)
            current == null -> activity.getString(R.string.sub_track_line, activity.getString(R.string.sub_off))
            else -> activity.getString(R.string.sub_track_line, current)
        }
        val items = arrayOf<CharSequence>(
            trackLine,
            activity.getString(R.string.sub_sync_line, formatDelay(delayMs)),
            activity.getString(R.string.sub_size_line, label(R.array.sub_sizes, prefs.subSize)),
            activity.getString(R.string.sub_font_line, label(R.array.sub_fonts, prefs.subFont)),
            activity.getString(R.string.sub_edge_line, label(R.array.sub_edges, prefs.subEdge)),
            activity.getString(R.string.sub_color_line, label(R.array.sub_colors, prefs.subColor)),
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.sub_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> chooseTrack(tracks)
                    1 -> openSync()
                    2 -> choose(R.string.sub_size, R.array.sub_sizes, prefs.subSize) { prefs.subSize = it }
                    3 -> choose(R.string.sub_font, R.array.sub_fonts, prefs.subFont) { prefs.subFont = it }
                    4 -> choose(R.string.sub_edge, R.array.sub_edges, prefs.subEdge) { prefs.subEdge = it }
                    5 -> choose(R.string.sub_color, R.array.sub_colors, prefs.subColor) { prefs.subColor = it }
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun label(arrayRes: Int, index: Int): String =
        activity.resources.getStringArray(arrayRes).getOrElse(index) { "" }

    private fun choose(titleRes: Int, arrayRes: Int, selected: Int, save: (Int) -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(titleRes)
            .setSingleChoiceItems(activity.resources.getStringArray(arrayRes), selected) { dialog, which ->
                save(which)
                applyStyle()
                dialog.dismiss()
                openMenu()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> openMenu() }
            .show()
    }

    private fun chooseTrack(tracks: List<TextTrack>) {
        if (tracks.isEmpty()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.sub_title)
                .setMessage(R.string.sub_none_help)
                .setPositiveButton(R.string.ok) { _, _ -> openMenu() }
                .show()
            return
        }
        val labels = (listOf(activity.getString(R.string.sub_off)) + tracks.map { it.label }).toTypedArray<CharSequence>()
        val selected = tracks.indexOfFirst { it.selected } + 1   // 0 = desactivados
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.sub_choose_track)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                val p = player()
                if (p != null) {
                    val builder = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    if (which == 0) {
                        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        prefs.subEnabled = false
                    } else {
                        val t = tracks[which - 1]
                        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(t.override)
                        prefs.subEnabled = true
                        prefs.subLang = t.language.orEmpty()   // la próxima vez se elige el mismo idioma
                    }
                    p.trackSelectionParameters = builder.build()
                }
                dialog.dismiss()
                openMenu()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> openMenu() }
            .show()
    }

    /** Ventana de sincronización: los cambios se aplican al momento para comprobarlos. */
    private fun openSync() {
        val density = activity.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val startDelay = delayMs

        val value = TextView(activity).apply {
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(activity.getColor(R.color.amber_light))
            text = formatDelay(delayMs)
        }
        val help = TextView(activity).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(activity.getColor(R.color.text_secondary))
            setText(R.string.sub_sync_help)
        }
        fun step(ms: Long, labelRes: Int) = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(labelRes)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (3 * density).toInt(); marginEnd = (3 * density).toInt()
            }
            setOnClickListener {
                delayMs = if (ms == 0L) 0L else (delayMs + ms).coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS)
                value.text = formatDelay(delayMs)
            }
        }
        val row1 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(step(-1000, R.string.sub_minus_1))
            addView(step(-100, R.string.sub_minus_01))
            addView(step(100, R.string.sub_plus_01))
            addView(step(1000, R.string.sub_plus_1))
        }
        val reset = step(0, R.string.sub_reset)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(value)
            addView(row1)
            addView(reset)
            addView(help)
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.sub_sync)
            .setView(box)
            .setPositiveButton(R.string.ok, null)
            .setOnDismissListener {
                // Vuelve a colocar los subtítulos en la posición actual con el nuevo retraso
                if (delayMs != startDelay && !isLive()) {
                    player()?.let { it.seekTo(it.currentPosition) }
                }
            }
            .show()
        // Arriba y con poca sombra, para ver los subtítulos mientras se ajustan
        dialog.window?.let { w ->
            w.setGravity(Gravity.TOP)
            w.setDimAmount(0.15f)
        }
        row1.getChildAt(2)?.requestFocus()
    }

    private fun formatDelay(ms: Long): String {
        val sign = if (ms > 0) "+" else if (ms < 0) "−" else ""
        return sign + String.format(Locale("es", "ES"), "%.1f s", abs(ms) / 1000.0)
    }

    // ---------------------------------------------------------------- Retraso de subtítulos

    /** Desplaza el reloj que ven los renderizadores de texto: así los subtítulos salen antes o después. */
    private class DelayedTextRenderer(renderer: Renderer, private val delayUs: () -> Long) : ForwardingRenderer(renderer) {
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
            super.render(positionUs - delayUs(), elapsedRealtimeUs)
        }
    }

    private class DelayRenderersFactory(context: Context, private val delayUs: () -> Long) : DefaultRenderersFactory(context) {
        init {
            setEnableDecoderFallback(true) // si el decodificador HW falla, prueba otro
            // Decodificadores del dispositivo y, si no soporta el audio (AC3, MP2…), FFmpeg por software
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }

        override fun buildTextRenderers(
            context: Context,
            output: TextOutput,
            outputLooper: Looper,
            extensionRendererMode: Int,
            out: ArrayList<Renderer>,
        ) {
            val start = out.size
            super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
            for (i in start until out.size) out[i] = DelayedTextRenderer(out[i], delayUs)
        }
    }

    private companion object {
        val SIZE_SCALES = floatArrayOf(0.8f, 1f, 1.3f, 1.65f)
        const val YELLOW = 0xFFFFE066.toInt()
        const val MAX_DELAY_MS = 30_000L
    }
}
