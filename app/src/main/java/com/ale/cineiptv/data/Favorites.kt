package com.ale.cineiptv.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Favoritos guardados en el dispositivo con todos sus datos (nombre, URL, logo…),
 * para poder mostrarlos aunque su categoría Xtream todavía no se haya abierto.
 * Canales, películas y episodios se identifican por su URL; las series por grupo + nombre.
 */
object Favorites {

    private var sp: SharedPreferences? = null
    private val items = LinkedHashMap<String, JSONObject>()

    @Volatile private var cachedChannels: List<Channel>? = null
    @Volatile private var cachedShows: List<SeriesShow>? = null

    fun init(context: Context) {
        if (sp != null) return
        val prefs = context.applicationContext.getSharedPreferences("retro_favorites", Context.MODE_PRIVATE)
        sp = prefs
        try {
            val arr = JSONArray(prefs.getString(KEY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                items[o.optString("k")] = o
            }
        } catch (e: Exception) {
            items.clear()
        }
    }

    fun keyOf(ch: Channel) = "c:" + ch.url
    fun keyOf(show: SeriesShow) = "s:" + show.group + "|" + show.name

    fun isFavorite(ch: Channel) = synchronized(items) { keyOf(ch) in items }
    fun isFavorite(show: SeriesShow) = synchronized(items) { keyOf(show) in items }

    val count: Int get() = synchronized(items) { items.size }

    /** Cambia cada vez que se añade o quita un favorito (para saber si hay que repintar). */
    @Volatile var version = 0; private set

    /** Cambia el estado y devuelve true si ahora es favorito. */
    fun toggle(ch: Channel): Boolean = toggle(keyOf(ch)) {
        JSONObject()
            .put("kind", "c").put("name", ch.name).put("url", ch.url).put("group", ch.group)
            .put("logo", ch.logo ?: "").put("ua", ch.userAgent ?: "").put("ref", ch.referrer ?: "")
            .put("type", ch.type.name).put("num", ch.number)
    }

    /**
     * Pone o quita de favoritos varios canales a la vez (todas las calidades de un canal
     * agrupado: SD, HD, FHD…). Devuelve el nuevo estado.
     */
    fun setAll(channels: List<Channel>, favorite: Boolean): Boolean {
        synchronized(items) {
            for (ch in channels) {
                val key = keyOf(ch)
                if (favorite) {
                    if (key !in items) items[key] = channelJson(ch).put("k", key)
                } else {
                    items.remove(key)
                }
            }
        }
        cachedChannels = null
        cachedShows = null
        version++
        save()
        return favorite
    }

    private fun channelJson(ch: Channel) = JSONObject()
        .put("kind", "c").put("name", ch.name).put("url", ch.url).put("group", ch.group)
        .put("logo", ch.logo ?: "").put("ua", ch.userAgent ?: "").put("ref", ch.referrer ?: "")
        .put("type", ch.type.name).put("num", ch.number)

    fun toggle(show: SeriesShow): Boolean = toggle(keyOf(show)) {
        JSONObject()
            .put("kind", "s").put("name", show.name).put("group", show.group)
            .put("logo", show.logo ?: "").put("sid", show.seriesId ?: "")
    }

    /** Canales, películas y episodios favoritos (en el orden en que se añadieron). */
    fun channels(): List<Channel> {
        cachedChannels?.let { return it }
        val list = synchronized(items) {
            items.values.filter { it.optString("kind") == "c" }.map { o ->
                Channel(
                    name = o.optString("name"),
                    url = o.optString("url"),
                    group = o.optString("group"),
                    logo = o.optString("logo").ifEmpty { null },
                    userAgent = o.optString("ua").ifEmpty { null },
                    referrer = o.optString("ref").ifEmpty { null },
                    type = runCatching { ContentType.valueOf(o.optString("type")) }.getOrDefault(ContentType.LIVE),
                    number = o.optInt("num"),
                )
            }
        }
        cachedChannels = list
        return list
    }

    /** Series favoritas (sus episodios se cargan al abrirlas). */
    fun shows(): List<SeriesShow> {
        cachedShows?.let { return it }
        val list = synchronized(items) {
            items.values.filter { it.optString("kind") == "s" }.map { o ->
                SeriesShow(
                    name = o.optString("name"),
                    group = o.optString("group"),
                    logo = o.optString("logo").ifEmpty { null },
                    episodes = emptyList(),
                    seriesId = o.optString("sid").ifEmpty { null },
                )
            }
        }
        cachedShows = list
        return list
    }

    private fun toggle(key: String, data: () -> JSONObject): Boolean {
        val nowFav = synchronized(items) {
            if (items.remove(key) != null) {
                false
            } else {
                items[key] = data().put("k", key)
                true
            }
        }
        cachedChannels = null
        cachedShows = null
        version++
        save()
        return nowFav
    }

    private fun save() {
        val arr = JSONArray()
        synchronized(items) { items.values.forEach { arr.put(it) } }
        sp?.edit()?.putString(KEY, arr.toString())?.apply()
    }

    private const val KEY = "items_v2"
}
