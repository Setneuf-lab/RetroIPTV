package com.ale.cineiptv.data

/**
 * Decide si una entrada es TV en vivo, película o episodio de serie.
 *
 * Orden de prioridad:
 *  1. Ruta de la URL estilo Xtream (/live/, /movie/, /series/) → fiable al 100 %.
 *  2. Extensión de archivo de vídeo (.mp4, .mkv…) → VOD (película o episodio).
 *  3. Palabras clave del grupo, solo si la URL no es claramente un stream (.ts/.m3u8).
 *  4. Por defecto: TV en vivo.
 */
object ContentClassifier {

    private val EPISODE = Regex(
        """(?i)\bS\d{1,2}\s*E\d{1,4}\b|\b\d{1,2}x\d{1,3}\b|\b(temporada|season|cap[ií]tulo|episodio)\s*\d+"""
    )

    private val VIDEO_EXT = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "m4v", "webm", "mpg", "mpeg", "divx"
    )

    private val SERIES_WORDS = listOf(
        "serie", "temporada", "season", "tv show", "tvshow", "novela", "anime"
    )

    private val MOVIE_WORDS = listOf(
        "vod", "pelicula", "película", "movie", "film", "estreno", "cinema"
    )

    fun classify(name: String, group: String, url: String): ContentType {
        val u = url.lowercase()
        if ("/series/" in u) return ContentType.SERIES
        if ("/movie/" in u || "/movies/" in u) return ContentType.MOVIE
        if ("/live/" in u) return ContentType.LIVE

        val ext = extensionOf(u)
        // .ts / .m3u8 casi siempre son canales en directo (p. ej. grupo "Serie A" de fútbol).
        // Comprobarlo primero evita trabajo caro en la mayoría de entradas.
        if (ext == "ts" || ext == "m3u8") return ContentType.LIVE

        val g = group.lowercase()
        val hasSeriesWord = SERIES_WORDS.any { g.contains(it) }

        if (ext in VIDEO_EXT) {
            return if (hasSeriesWord || EPISODE.containsMatchIn(name)) ContentType.SERIES else ContentType.MOVIE
        }
        if (hasSeriesWord && EPISODE.containsMatchIn(name)) return ContentType.SERIES
        if (MOVIE_WORDS.any { g.contains(it) }) return ContentType.MOVIE
        return ContentType.LIVE
    }

    /** Extensión del último segmento de la ruta, sin parámetros. Vacío si no hay. */
    fun extensionOf(lowerUrl: String): String {
        val path = lowerUrl.substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/')
        if (!last.contains('.')) return ""
        val ext = last.substringAfterLast('.')
        return if (ext.length in 2..5) ext else ""
    }

    private val EPISODE_TAIL = Regex(
        """(?i)\s*[-–:|(\[]?\s*(S\d{1,2}\s*E\d{1,4}|\d{1,2}x\d{1,3}|(temporada|season|cap[ií]tulo|episodio)\s*\d+).*$"""
    )

    /** "Breaking Bad S01E03 - Título" → "Breaking Bad". */
    fun seriesNameOf(episode: Channel): String {
        val cleaned = episode.name.replace(EPISODE_TAIL, "").trim(' ', '-', '|', ':', '.', '–')
        return cleaned.ifBlank { episode.group }
    }
}
