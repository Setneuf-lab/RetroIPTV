package com.ale.cineiptv.data

import okhttp3.OkHttpClient
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object Network {

    /** Muchos paneles IPTV bloquean User-Agents desconocidos; el de VLC es el más aceptado. */
    const val DEFAULT_UA = "VLC/3.0.20 LibVLC/3.0.20"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .dns(SmartDns)   // salta los bloqueos de la operadora (Ajustes → DNS)
            .build()
    }

    fun httpErrorMessage(code: Int): String = when (code) {
        401, 403 -> "Acceso denegado ($code). Usuario/contraseña incorrectos, cuenta caducada o el servidor bloquea este User-Agent."
        404 -> "No encontrado (404). Revisa la URL o el host."
        429 -> "Demasiadas peticiones (429). Espera un minuto y vuelve a intentarlo."
        in 500..599 -> "Error del servidor ($code). Puede estar caído; inténtalo más tarde."
        else -> "Error HTTP $code al descargar la lista."
    }
}

object Xtream {

    /** Acepta "midominio.com:8080", "http://midominio.com:8080/" o incluso una URL get.php pegada. */
    fun normalizeHost(raw: String): String {
        var h = raw.trim()
        if (!h.startsWith("http://", true) && !h.startsWith("https://", true)) h = "http://$h"
        h = h.substringBefore("/get.php").substringBefore("/player_api.php").substringBefore("?")
        return h.trimEnd('/')
    }

    fun buildM3uUrl(host: String, user: String, pass: String): String {
        fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")
        return "${normalizeHost(host)}/get.php?username=${enc(user)}&password=${enc(pass)}&type=m3u_plus&output=ts"
    }
}
