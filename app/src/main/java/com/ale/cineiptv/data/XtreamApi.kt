package com.ale.cineiptv.data

import android.util.JsonReader
import android.util.JsonToken

import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder

/** Datos de acceso a un servidor Xtream Codes. */
data class XtreamCreds(val host: String, val user: String, val pass: String) {
    /** Firma para saber si la lista guardada corresponde a esta cuenta. */
    val signature: String get() = "xtream|$host|$user|$pass"

    private fun seg(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun liveUrl(id: String, ext: String) = "$host/live/${seg(user)}/${seg(pass)}/$id.$ext"
    fun movieUrl(id: String, ext: String) = "$host/movie/${seg(user)}/${seg(pass)}/$id.$ext"
    fun episodeUrl(id: String, ext: String) = "$host/series/${seg(user)}/${seg(pass)}/$id.$ext"

    companion object {
        fun of(host: String, user: String, pass: String) =
            XtreamCreds(Xtream.normalizeHost(host), user.trim(), pass.trim())

        /**
         * Detecta URLs M3U que en realidad son de un Xtream
         * (…/get.php?username=X&password=Y…) para usar la API, mucho más rápida.
         */
        /**
         * Deduce la cuenta a partir de la dirección de un canal de un servidor Xtream:
         * http://servidor:puerto/live/USUARIO/CLAVE/123.ts (o sin /live/).
         */
        fun fromStreamUrl(url: String): XtreamCreds? {
            val clean = url.trim().substringBefore('?')
            if (!clean.startsWith("http", ignoreCase = true)) return null
            val afterScheme = clean.substringAfter("://")
            val host = clean.substring(0, clean.length - afterScheme.length) + afterScheme.substringBefore('/')
            val segs = afterScheme.substringAfter('/', "").split('/').filter { it.isNotEmpty() }
            val (user, pass) = when {
                segs.size >= 4 && segs[0].lowercase() in setOf("live", "movie", "series", "timeshift") -> segs[1] to segs[2]
                segs.size == 3 && segs[2].substringBefore('.').all { it.isDigit() } -> segs[0] to segs[1]
                else -> return null
            }
            fun dec(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }
            return of(host, dec(user), dec(pass))
        }

        fun fromGetPhpUrl(url: String): XtreamCreds? {
            val clean = url.trim()
            if (!clean.startsWith("http", ignoreCase = true)) return null
            val idx = clean.indexOf("/get.php", ignoreCase = true)
            if (idx < 0) return null
            val query = clean.substringAfter('?', "").substringBefore('#')
            val params = HashMap<String, String>()
            query.split('&').forEach { pair ->
                val k = pair.substringBefore('=').lowercase()
                val v = pair.substringAfter('=', "")
                params[k] = try { URLDecoder.decode(v, "UTF-8") } catch (e: Exception) { v }
            }
            val user = params["username"]?.takeIf { it.isNotBlank() } ?: return null
            val pass = params["password"]?.takeIf { it.isNotBlank() } ?: return null
            return of(clean.substring(0, idx), user, pass)
        }
    }
}

/**
 * Cliente de la API de Xtream Codes (player_api.php).
 *
 * En lugar de descargar una lista M3U gigante con todo el catálogo, pide solo lo necesario:
 *  - al entrar: datos de la cuenta, categorías y canales en vivo
 *  - películas y series: solo las de la categoría que se abre
 *  - episodios: solo los de la serie que se abre
 *
 * El JSON se lee en streaming (sin cargar la respuesta entera en memoria).
 */
object XtreamApi {

    class LoginResult(val account: XtreamAccount, val liveExt: String)

    private const val NO_GROUP = "Sin categoría"

    // ---------------------------------------------------------------- Peticiones

    private fun apiUrl(c: XtreamCreds, action: String?, extra: String = ""): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val a = if (action != null) "&action=$action" else ""
        return "${c.host}/player_api.php?username=${enc(c.user)}&password=${enc(c.pass)}$a$extra"
    }

    /** Hace la petición y entrega un lector JSON en streaming al bloque. */
    private fun <T> call(c: XtreamCreds, ua: String, action: String?, extra: String = "", block: (JsonReader) -> T): T {
        val request = try {
            Request.Builder().url(apiUrl(c, action, extra)).header("User-Agent", ua).build()
        } catch (e: IllegalArgumentException) {
            throw PlaylistException("La dirección del servidor no es válida.")
        }
        Network.client.newCall(request).execute().use { resp ->
            if (resp.code == 404) throw notXtream() // no existe player_api.php
            if (!resp.isSuccessful) throw PlaylistException(Network.httpErrorMessage(resp.code))
            val body = resp.body ?: throw PlaylistException("El servidor devolvió una respuesta vacía.")
            val reader = JsonReader(body.charStream())
            reader.isLenient = true
            return try {
                block(reader)
            } catch (e: PlaylistException) {
                throw e
            } catch (e: IOException) {
                if (e.javaClass.simpleName.contains("Malformed")) throw notXtream()
                throw e
            } catch (e: IllegalStateException) {
                throw notXtream()
            }
        }
    }

    private fun notXtream() = NotXtreamException(
        "El servidor no responde como un Xtream Codes. Revisa la dirección (http://servidor:puerto) y los datos."
    )

    // ---------------------------------------------------------------- Lectura tolerante

    /** Lee cualquier valor simple como texto (los paneles mezclan números y textos). */
    private fun JsonReader.anyString(): String? = when (peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        JsonToken.BOOLEAN -> nextBoolean().toString()
        JsonToken.NULL -> { nextNull(); null }
        else -> { skipValue(); null }
    }

    private fun String?.http(): String? = this?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }

    /** Recorre un array de objetos llamando a [onObject] con los campos simples de cada uno. */
    private inline fun JsonReader.forEachObject(onObject: (Map<String, String>) -> Unit) {
        if (peek() != JsonToken.BEGIN_ARRAY) throw notXtream()
        beginArray()
        val fields = HashMap<String, String>(16)
        while (hasNext()) {
            if (peek() != JsonToken.BEGIN_OBJECT) { skipValue(); continue }
            fields.clear()
            beginObject()
            while (hasNext()) {
                val name = nextName()
                anyString()?.let { fields[name] = it }
            }
            endObject()
            onObject(fields)
        }
        endArray()
    }

    // ---------------------------------------------------------------- Cuenta

    /** [strict] = false: devuelve los datos aunque la cuenta esté caducada (para mostrarlo en el menú). */
    fun login(c: XtreamCreds, ua: String, strict: Boolean = true): LoginResult = call(c, ua, null) { r ->
        if (r.peek() != JsonToken.BEGIN_OBJECT) throw notXtream()
        var auth = ""
        var status = ""
        var exp = 0L
        var maxCons = 0
        val formats = ArrayList<String>()
        var sawUserInfo = false
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() != "user_info" || r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
            sawUserInfo = true
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "auth" -> auth = r.anyString().orEmpty()
                    "status" -> status = r.anyString().orEmpty()
                    "exp_date" -> exp = r.anyString()?.toLongOrNull() ?: 0L
                    "max_connections" -> maxCons = r.anyString()?.toIntOrNull() ?: 0
                    "allowed_output_formats" ->
                        if (r.peek() == JsonToken.BEGIN_ARRAY) {
                            r.beginArray()
                            while (r.hasNext()) r.anyString()?.let { formats.add(it.lowercase()) }
                            r.endArray()
                        } else r.skipValue()
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }
        r.endObject()

        if (!sawUserInfo) throw notXtream()
        if (!strict && auth != "0" && auth != "false") {
            return@call LoginResult(XtreamAccount(status.ifBlank { "Active" }, exp, maxCons), "ts")
        }
        if (auth != "1" && auth != "true") {
            throw PlaylistException("Usuario o contraseña incorrectos.")
        }
        if (status.equals("Expired", ignoreCase = true)) {
            throw PlaylistException("La cuenta ha caducado. Contacta con tu proveedor.")
        }
        if (status.equals("Banned", ignoreCase = true) || status.equals("Disabled", ignoreCase = true)) {
            throw PlaylistException("La cuenta está desactivada ($status).")
        }
        // "ts" va mejor en la mayoría de cajas; si el servidor no lo permite, HLS.
        val ext = if (formats.isEmpty() || "ts" in formats) "ts" else "m3u8"
        LoginResult(XtreamAccount(status.ifBlank { "Active" }, exp, maxCons), ext)
    }

    // ---------------------------------------------------------------- Categorías

    private fun categories(c: XtreamCreds, ua: String, action: String): List<Category> = call(c, ua, action) { r ->
        if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return@call emptyList() }
        val list = ArrayList<Category>()
        r.forEachObject { f ->
            val id = f["category_id"] ?: return@forEachObject
            val name = f["category_name"]?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEachObject
            list.add(Category(id, name))
        }
        list
    }

    fun liveCategories(c: XtreamCreds, ua: String) = categories(c, ua, "get_live_categories")
    fun vodCategories(c: XtreamCreds, ua: String) = categories(c, ua, "get_vod_categories")
    fun seriesCategories(c: XtreamCreds, ua: String) = categories(c, ua, "get_series_categories")

    // ---------------------------------------------------------------- Contenido

    /** Todos los canales en vivo, ordenados por su número de canal. */
    fun liveStreams(
        c: XtreamCreds, ua: String, ext: String, groupNames: Map<String, String>, onProgress: (Int) -> Unit,
    ): List<Channel> = call(c, ua, "get_live_streams") { r ->
        if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return@call emptyList() }
        val list = ArrayList<Channel>(4096)
        r.forEachObject { f ->
            val id = f["stream_id"] ?: return@forEachObject
            val name = f["name"]?.trim().orEmpty().ifEmpty { "Canal $id" }
            list.add(
                Channel(
                    name = name,
                    url = c.liveUrl(id, ext),
                    group = groupNames[f["category_id"]] ?: NO_GROUP,
                    logo = f["stream_icon"].http(),
                    userAgent = null,
                    referrer = null,
                    type = ContentType.LIVE,
                    number = f["num"]?.toDoubleOrNull()?.toInt() ?: 0,
                )
            )
            if (list.size % 2000 == 0) onProgress(list.size)
        }
        onProgress(list.size)
        list.trimToSize()
        if (list.any { it.number > 0 }) list.sortedBy { if (it.number > 0) it.number else Int.MAX_VALUE } else list
    }

    /** Películas de una categoría. */
    fun vodStreams(c: XtreamCreds, ua: String, category: Category): List<Channel> =
        call(c, ua, "get_vod_streams", "&category_id=${URLEncoder.encode(category.id, "UTF-8")}") { r ->
            readVod(r, c, { category.name }, {})
        }

    /** Todas las películas del proveedor (para el buscador global). */
    fun allVodStreams(c: XtreamCreds, ua: String, groupNames: Map<String, String>, onProgress: (Int) -> Unit): List<Channel> =
        call(c, ua, "get_vod_streams") { r -> readVod(r, c, { id -> groupNames[id] ?: NO_GROUP }, onProgress) }

    private fun readVod(r: JsonReader, c: XtreamCreds, groupOf: (String?) -> String, onProgress: (Int) -> Unit): List<Channel> {
        if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return emptyList() }
        val list = ArrayList<Channel>()
        r.forEachObject { f ->
            val id = f["stream_id"] ?: return@forEachObject
            val ext = f["container_extension"]?.trim()?.takeIf { it.isNotEmpty() } ?: "mp4"
            list.add(
                Channel(
                    name = f["name"]?.trim().orEmpty().ifEmpty { "Película $id" },
                    url = c.movieUrl(id, ext),
                    group = groupOf(f["category_id"]),
                    logo = f["stream_icon"].http(),
                    userAgent = null,
                    referrer = null,
                    type = ContentType.MOVIE,
                )
            )
            if (list.size % 2000 == 0) onProgress(list.size)
        }
        onProgress(list.size)
        list.trimToSize()
        return list
    }

    /** Series de una categoría (sin episodios todavía). */
    fun seriesList(c: XtreamCreds, ua: String, category: Category): List<SeriesShow> =
        call(c, ua, "get_series", "&category_id=${URLEncoder.encode(category.id, "UTF-8")}") { r ->
            readSeries(r, { category.name }, {})
        }

    /** Todas las series del proveedor (para el buscador global). */
    fun allSeries(c: XtreamCreds, ua: String, groupNames: Map<String, String>, onProgress: (Int) -> Unit): List<SeriesShow> =
        call(c, ua, "get_series") { r -> readSeries(r, { id -> groupNames[id] ?: NO_GROUP }, onProgress) }

    private fun readSeries(r: JsonReader, groupOf: (String?) -> String, onProgress: (Int) -> Unit): List<SeriesShow> {
        if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return emptyList() }
        val list = ArrayList<SeriesShow>()
        r.forEachObject { f ->
            val id = f["series_id"] ?: return@forEachObject
            list.add(
                SeriesShow(
                    name = f["name"]?.trim().orEmpty().ifEmpty { "Serie $id" },
                    group = groupOf(f["category_id"]),
                    logo = f["cover"].http(),
                    episodes = emptyList(),
                    seriesId = id,
                )
            )
            if (list.size % 2000 == 0) onProgress(list.size)
        }
        onProgress(list.size)
        list.trimToSize()
        return list
    }

    /** Episodios de una serie, ordenados por temporada y número. */
    /** Id de una película a partir de su dirección: …/movie/usuario/clave/123.mkv → 123 */
    fun vodIdOf(url: String): String? {
        val path = url.substringBefore('?')
        val i = path.indexOf("/movie/", ignoreCase = true)
        if (i < 0) return null
        return path.substringAfterLast('/').substringBefore('.').takeIf { id -> id.isNotEmpty() && id.all { it.isDigit() } }
    }

    /** Sinopsis, año, duración, nota, reparto… de una película. */
    fun vodInfo(c: XtreamCreds, ua: String, vodId: String): MovieInfo? =
        call(c, ua, "get_vod_info", "&vod_id=${URLEncoder.encode(vodId, "UTF-8")}") { r ->
            if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return@call null }
            val f = HashMap<String, String>()
            var backdrop: String? = null
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "info" || r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                r.beginObject()
                while (r.hasNext()) {
                    val name = r.nextName()
                    if (name == "backdrop_path" && r.peek() == JsonToken.BEGIN_ARRAY) {
                        r.beginArray()
                        while (r.hasNext()) { val v = r.anyString().http(); if (backdrop == null) backdrop = v }
                        r.endArray()
                    } else {
                        r.anyString()?.let { f[name] = it.trim() }
                    }
                }
                r.endObject()
            }
            r.endObject()
            if (f.isEmpty()) return@call null
            fun first(vararg keys: String) = keys.firstNotNullOfOrNull { k -> f[k]?.takeIf { it.isNotEmpty() && it != "null" } }.orEmpty()
            val secs = first("duration_secs").toIntOrNull()
            val minutes = secs?.let { it / 60 } ?: first("duration").let { d ->
                // "01:52:30" o "112"
                val parts = d.split(':').mapNotNull { it.trim().toIntOrNull() }
                when (parts.size) { 3 -> parts[0] * 60 + parts[1]; 2 -> parts[0] * 60 + parts[1]; 1 -> parts[0]; else -> 0 }
            }
            val rating = first("rating").toDoubleOrNull()?.takeIf { it > 0 }?.let { String.format(java.util.Locale.ROOT, "%.1f", it) }
                ?: first("rating_5based").toDoubleOrNull()?.takeIf { it > 0 }?.let { String.format(java.util.Locale.ROOT, "%.1f", it * 2) }
                ?: ""
            MovieInfo(
                title = first("name", "o_name", "title"),
                plot = first("plot", "description"),
                genre = first("genre"),
                year = first("releasedate", "release_date", "year").take(4).takeIf { y -> y.all { it.isDigit() } }.orEmpty(),
                durationMin = minutes,
                rating = rating,
                director = first("director"),
                cast = first("cast", "actors"),
                poster = first("movie_image", "cover_big").http(),
                backdrop = backdrop ?: first("backdrop_path", "backdrop").http(),
            )
        }

    fun seriesEpisodes(c: XtreamCreds, ua: String, show: SeriesShow): List<Channel> {
        val seriesId = show.seriesId ?: return emptyList()
        return call(c, ua, "get_series_info", "&series_id=${URLEncoder.encode(seriesId, "UTF-8")}") { r ->
            if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return@call emptyList() }
            class Ep(val season: Int, val num: Int, val ch: Channel)
            val eps = ArrayList<Ep>()

            fun readEpisode(seasonKey: Int) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return }
                var id: String? = null
                var title = ""
                var ext = "mp4"
                var num = 0
                var season = seasonKey
                var image: String? = null
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "id" -> id = r.anyString()
                        "title" -> title = r.anyString().orEmpty().trim()
                        "container_extension" -> ext = r.anyString()?.trim()?.takeIf { it.isNotEmpty() } ?: "mp4"
                        "episode_num" -> num = r.anyString()?.toDoubleOrNull()?.toInt() ?: 0
                        "season" -> season = r.anyString()?.toDoubleOrNull()?.toInt() ?: seasonKey
                        "info" -> if (r.peek() == JsonToken.BEGIN_OBJECT) {
                            r.beginObject()
                            while (r.hasNext()) {
                                if (r.nextName() == "movie_image") image = r.anyString().http() else r.skipValue()
                            }
                            r.endObject()
                        } else r.skipValue()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                val epId = id ?: return
                val label = "T%d · E%02d".format(season, num)
                val name = when {
                    title.isEmpty() -> label
                    Regex("""(?i)S\d+\s*E\d+""").containsMatchIn(title) -> title
                    else -> "$label · $title"
                }
                eps.add(Ep(season, num, Channel(name, c.episodeUrl(epId, ext), show.group, image ?: show.logo, null, null, ContentType.SERIES)))
            }

            fun readSeasonArray(seasonKey: Int) {
                r.beginArray()
                while (r.hasNext()) {
                    if (r.peek() == JsonToken.BEGIN_ARRAY) readSeasonArray(seasonKey) else readEpisode(seasonKey)
                }
                r.endArray()
            }

            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "episodes") { r.skipValue(); continue }
                when (r.peek()) {
                    // Formato habitual: { "1": [ … ], "2": [ … ] }
                    JsonToken.BEGIN_OBJECT -> {
                        r.beginObject()
                        while (r.hasNext()) {
                            val season = r.nextName().toIntOrNull() ?: 0
                            if (r.peek() == JsonToken.BEGIN_ARRAY) readSeasonArray(season) else r.skipValue()
                        }
                        r.endObject()
                    }
                    // Algunos paneles: [ [ … ], [ … ] ]
                    JsonToken.BEGIN_ARRAY -> readSeasonArray(0)
                    else -> r.skipValue()
                }
            }
            r.endObject()
            eps.sortWith(compareBy<Ep>({ it.season }, { it.num }))
            eps.map { it.ch }
        }
    }
}
