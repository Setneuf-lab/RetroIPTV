package com.ale.cineiptv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Emisoras de radio de internet (directorio público y gratuito radio-browser.info),
 * para la "radio con imagen" aunque la lista IPTV no traiga radios en español.
 * Las direcciones se piden al momento, así que no se quedan anticuadas.
 */
object RadioDirectory {

    private val SERVERS = listOf(
        "https://de1.api.radio-browser.info",
        "https://de2.api.radio-browser.info",
        "https://fi1.api.radio-browser.info",
        "https://all.api.radio-browser.info",
    )

    private val client by lazy {
        Network.client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** Siempre arriba (las que más se piden para el fútbol). */
    private val PINNED = listOf("COPE", "Cadena SER")

    /**
     * Emisoras de España que contienen [query] (vacío = las más escuchadas).
     * COPE y SER van primero. Si el directorio no responde, se usan unas de reserva.
     */
    suspend fun search(query: String): List<Channel> = withContext(Dispatchers.IO) {
        val q = query.trim()
        val found = fetch(q, limit = 80)
        val pinned = if (q.isEmpty()) PINNED.flatMap { fetch(it, limit = 3).take(2) } else emptyList()
        val all = (pinned + found).distinctBy { it.url }
        all.ifEmpty { FALLBACK.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) } }
    }

    private fun fetch(name: String, limit: Int): List<Channel> {
        for (server in SERVERS) {
            val enc = { v: String -> java.net.URLEncoder.encode(v, "UTF-8") }
            val url = "$server/json/stations/search?countrycode=ES&hidebroken=true&order=clickcount&reverse=true" +
                "&limit=$limit" + (if (name.isNotEmpty()) "&name=" + enc(name) else "")
            val result = runCatching {
                client.newCall(Request.Builder().url(url).header("User-Agent", "RetroIPTV/1.0").build())
                    .execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        parse(JSONArray(resp.body?.string() ?: return@use null))
                    }
            }.getOrNull()
            if (result != null) return result
        }
        return emptyList()
    }

    private fun parse(arr: JSONArray): List<Channel> {
        val out = ArrayList<Channel>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }
            val name = o.optString("name").trim()
            if (url.isBlank() || name.isBlank()) continue
            out.add(radio(name, url, o.optString("favicon").ifBlank { null }))
        }
        return out
    }

    private fun radio(name: String, url: String, logo: String? = null) =
        Channel(name, url, "Radio · Internet", logo, null, null, ContentType.LIVE)

    /** Por si el directorio no responde (pueden dejar de funcionar con el tiempo). */
    private val FALLBACK = listOf(
        radio("COPE", "https://flucast-b02-04.flumotion.com/cope/net1.mp3"),
        radio("Cadena SER", "https://playerservices.streamtheworld.com/api/livestream-redirect/CADENASER.mp3"),
        radio("Onda Cero", "https://icecast-streaming.nice264.com/ondacero"),
        radio("Radio Marca", "https://playerservices.streamtheworld.com/api/livestream-redirect/RADIOMARCA_NACIONAL.mp3"),
        radio("RNE Radio Nacional", "https://rtvelivestream.rtve.es/rtvesec/rne/rne_r1_main.m3u8"),
        radio("esRadio", "https://livestreaming.esradio.fm/stream64.mp3"),
    )
}
