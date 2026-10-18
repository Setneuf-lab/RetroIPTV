package com.ale.cineiptv.util

import android.media.MediaCodecList
import android.media.MediaFormat

/** Qué puede reproducir este aparato (se calcula una vez). */
object DeviceCaps {

    /**
     * true si el aparato tiene un decodificador capaz de vídeo 4K (3840×2160) en H.265 o H.264.
     * Muchos sticks "Full HD" (Mi TV Stick, Fire TV Stick básico…) no pueden, ni siquiera con VLC.
     */
    val supports4K: Boolean by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { type ->
                    (type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) || type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true)) &&
                        runCatching {
                            info.getCapabilitiesForType(type).videoCapabilities?.isSizeSupported(3840, 2160) == true
                        }.getOrDefault(false)
                }
            }
        }.getOrDefault(true)   // si no se puede saber, no se bloquea nada
    }

    /** ¿El nombre indica que es 4K? ("Dune [4K]", "Dune UHD", "Dune 2160p") */
    fun is4K(name: String): Boolean {
        val q = ChannelName.split(name).second?.uppercase() ?: return false
        return q.split(' ').any { it in setOf("4K", "UHD", "2160P", "2160", "4K+", "UHD+", "8K") }
    }
}

/**
 * Clave para reconocer la misma película en distintas versiones:
 * "ES| Michael - 2026 [4K]", "EN| Michael - 2026" y "Michael - 2026 [MULTI-SUB]" → "michael" + 2026.
 */
object TitleKey {
    private val YEAR = Regex("""(19|20)\d\d""")
    private val BRACKETS = Regex("""[\[(][^\])]*[\])]""")

    data class Key(val title: String, val year: String?)

    fun of(name: String): Key {
        var s = ChannelName.split(name).first
        // Prefijo de idioma "ES|", "[EN]", "LAT -"…
        if (LangFilter.codeOf(s) != null) {
            val cut = s.indexOfFirst { it == '|' || it == ':' || it == ']' || it == '-' }
            if (cut in 0..6) s = s.substring(cut + 1)
        }
        val year = YEAR.findAll(s).lastOrNull()?.value
        s = BRACKETS.replace(s, " ")
        if (year != null) s = s.replace(year, " ")
        val norm = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
        return Key(norm, year)
    }

    fun sameMovie(a: Key, b: Key): Boolean =
        a.title.isNotEmpty() && a.title == b.title && (a.year == null || b.year == null || a.year == b.year)
}
