package com.ale.cineiptv.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Serie a la que pertenece el episodio que se está viendo (para "Seguir viendo"). */
data class ShowRef(val name: String, val logo: String?)

/** Una película o episodio a medias. */
class HistoryEntry(
    val channel: Channel,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val show: ShowRef?,
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)
}

/**
 * "Seguir viendo": películas y episodios que se dejaron a medias, con el punto exacto.
 * De cada serie se guarda solo el último episodio visto. Se guardan en el dispositivo.
 */
object WatchHistory {

    private const val KEY = "items_v1"
    private const val MAX_ITEMS = 30

    private var sp: SharedPreferences? = null
    /** Más reciente primero. */
    private val items = ArrayList<JSONObject>()

    fun init(context: Context) {
        if (sp != null) return
        val prefs = context.applicationContext.getSharedPreferences("retro_history", Context.MODE_PRIVATE)
        sp = prefs
        try {
            val arr = JSONArray(prefs.getString(KEY, "[]"))
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { items.add(it) }
        } catch (e: Exception) {
            items.clear()
        }
    }

    fun entries(): List<HistoryEntry> = synchronized(items) { items.map { it.toEntry() } }

    /** Punto donde se dejó este vídeo (0 si no está empezado). */
    fun positionOf(url: String): Long = synchronized(items) {
        items.firstOrNull { it.optString("url") == url }?.optLong("pos") ?: 0L
    }

    /** Guarda el punto actual. Si ya casi se ha terminado, lo quita de la lista. */
    fun save(ch: Channel, positionMs: Long, durationMs: Long, show: ShowRef?) {
        if (ch.type == ContentType.LIVE || durationMs <= 0) return
        val finished = positionMs > durationMs - END_MARGIN_MS || positionMs > durationMs * 0.95
        if (positionMs < MIN_POSITION_MS || finished) { remove(ch.url); return }
        val o = JSONObject()
            .put("name", ch.name).put("url", ch.url).put("group", ch.group)
            .put("logo", ch.logo ?: "").put("ua", ch.userAgent ?: "").put("ref", ch.referrer ?: "")
            .put("type", ch.type.name)
            .put("pos", positionMs).put("dur", durationMs).put("at", System.currentTimeMillis())
            .put("show", show?.name ?: "").put("showLogo", show?.logo ?: "")
        synchronized(items) {
            // Mismo vídeo, o episodio anterior de la misma serie → se sustituye
            items.removeAll { it.optString("url") == ch.url || (show != null && it.optString("show") == show.name) }
            items.add(0, o)
            while (items.size > MAX_ITEMS) items.removeAt(items.lastIndex)
        }
        persist()
    }

    fun remove(url: String) {
        val changed = synchronized(items) { items.removeAll { it.optString("url") == url } }
        if (changed) persist()
    }

    private fun persist() {
        val arr = JSONArray()
        synchronized(items) { items.forEach { arr.put(it) } }
        sp?.edit()?.putString(KEY, arr.toString())?.apply()
    }

    private fun JSONObject.toEntry() = HistoryEntry(
        channel = Channel(
            name = optString("name"),
            url = optString("url"),
            group = optString("group"),
            logo = optString("logo").ifEmpty { null },
            userAgent = optString("ua").ifEmpty { null },
            referrer = optString("ref").ifEmpty { null },
            type = runCatching { ContentType.valueOf(optString("type")) }.getOrDefault(ContentType.MOVIE),
        ),
        positionMs = optLong("pos"),
        durationMs = optLong("dur"),
        updatedAt = optLong("at"),
        show = optString("show").ifEmpty { null }?.let { ShowRef(it, optString("showLogo").ifEmpty { null }) },
    )

    /** Menos de esto visto no cuenta como empezado. */
    private const val MIN_POSITION_MS = 30_000L
    /** En los últimos minutos (créditos) se da por terminado. */
    private const val END_MARGIN_MS = 3 * 60_000L
}
