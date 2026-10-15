package com.ale.cineiptv.data

/** Sección a la que pertenece cada entrada de la lista. */
enum class ContentType { LIVE, MOVIE, SERIES }

/** Origen de la lista cargada. */
enum class SourceType { URL, FILE, XTREAM }

/** Una entrada de la lista (canal, película o episodio). */
class Channel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String?,
    val userAgent: String?,
    val referrer: String?,
    val type: ContentType,
    /** Número de canal del proveedor (Xtream); 0 = sin número propio. */
    val number: Int = 0,
) {
    /**
     * Búsqueda sin guardar copias del texto (ahorra mucha memoria en listas enormes).
     * Cadena vacía = todo coincide.
     */
    fun matchesQuery(q: String): Boolean =
        q.isEmpty() || name.contains(q, ignoreCase = true) || group.contains(q, ignoreCase = true)
}

/**
 * Una serie agrupa varios episodios.
 * En modo Xtream los episodios no se descargan hasta abrir la serie ([seriesId]).
 */
class SeriesShow(
    val name: String,
    val group: String,
    val logo: String?,
    episodes: List<Channel>,
    val seriesId: String? = null,
) {
    @Volatile var episodes: List<Channel> = episodes

    val needsEpisodes: Boolean get() = episodes.isEmpty() && seriesId != null

    fun matchesQuery(q: String): Boolean =
        q.isEmpty() || name.contains(q, ignoreCase = true) || group.contains(q, ignoreCase = true)
}

/** Categoría de películas o series de Xtream (se descarga su contenido al abrirla). */
class Category(val id: String, val name: String)

/** Datos de la cuenta Xtream para mostrar en el menú. */
class XtreamAccount(
    val status: String,
    val expiresAt: Long,        // segundos Unix; 0 = sin caducidad
    val maxConnections: Int,
)

/** Error con mensaje legible para mostrar al usuario. */
open class PlaylistException(message: String) : Exception(message)

/** El servidor no tiene API Xtream (se puede intentar como lista M3U normal). */
class NotXtreamException(message: String) : PlaylistException(message)

/** Ficha de una película (Xtream get_vod_info). Los campos vacíos no se muestran. */
class MovieInfo(
    val title: String,
    val plot: String,
    val genre: String,
    val year: String,
    val durationMin: Int,
    val rating: String,
    val director: String,
    val cast: String,
    val poster: String?,
    val backdrop: String?,
)
