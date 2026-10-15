package com.ale.cineiptv.data

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Parser M3U/M3U8 propio, en streaming (línea a línea) para soportar listas
 * de cientos de miles de entradas sin cargar todo el texto en memoria.
 *
 * Optimizado para listas enormes:
 *  - sin expresiones regulares en la lectura de atributos
 *  - los textos repetidos (grupos, user-agents…) se guardan una sola vez
 *  - modo [liveOnly] para quedarse solo con la TV en vivo si la lista no cabe en memoria
 *
 * Soporta: atributos de #EXTINF (tvg-name, tvg-logo, group-title, user-agent…), títulos
 * con comas entre comillas, #EXTGRP, #EXTVLCOPT:http-user-agent/http-referrer,
 * cabeceras estilo Kodi (url|User-Agent=x&Referer=y), BOM UTF-8 y saltos de línea Windows.
 */
object M3uParser {

    private const val DEFAULT_GROUP = "Sin grupo"

    fun parse(input: InputStream, liveOnly: Boolean = false, onProgress: (Int) -> Unit = {}): List<Channel> {
        val result = ArrayList<Channel>(8192)
        val pool = HashMap<String, String>()          // textos repetidos compartidos
        fun shared(s: String): String = pool.getOrPut(s) { s }

        BufferedReader(InputStreamReader(input, Charsets.UTF_8), 128 * 1024).use { reader ->
            var info: String? = null
            var extGroup: String? = null
            var ua: String? = null
            var referrer: String? = null
            var firstLine = true
            var seen = 0

            while (true) {
                var line = reader.readLine() ?: break
                if (firstLine) {
                    line = line.removePrefix("﻿")
                    firstLine = false
                }
                line = line.trim()
                if (line.isEmpty()) continue

                if (line[0] == '#') {
                    when {
                        line.startsWith("#EXTINF", ignoreCase = true) -> info = line
                        line.startsWith("#EXTGRP:", ignoreCase = true) ->
                            extGroup = line.substring(8).trim()
                        line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                            val opt = line.substring(11).trim()
                            val key = opt.substringBefore('=').trim().lowercase()
                            val value = opt.substringAfter('=', "").trim()
                            when (key) {
                                "http-user-agent" -> ua = value
                                "http-referrer", "http-referer" -> referrer = value
                            }
                        }
                    }
                    continue
                }

                // Línea de URL
                buildChannel(info, extGroup, line, ua, referrer, liveOnly, ::shared)?.let { result.add(it) }
                info = null; extGroup = null; ua = null; referrer = null
                seen++
                if (seen % 5000 == 0) onProgress(seen)
            }
            onProgress(seen)
        }
        result.trimToSize()
        return result
    }

    private fun buildChannel(
        info: String?,
        extGroup: String?,
        rawUrl: String,
        vlcUa: String?,
        vlcRef: String?,
        liveOnly: Boolean,
        shared: (String) -> String,
    ): Channel? {
        // Cabeceras estilo Kodi tras "|"
        var url = rawUrl
        var ua = vlcUa
        var ref = vlcRef
        val pipe = rawUrl.indexOf('|')
        if (pipe >= 0) {
            url = rawUrl.substring(0, pipe).trim()
            rawUrl.substring(pipe + 1).split('&').forEach { pair ->
                val k = pair.substringBefore('=').trim().lowercase()
                val v = pair.substringAfter('=', "").trim()
                when (k) {
                    "user-agent" -> ua = v
                    "referer", "referrer" -> ref = v
                }
            }
        }
        if (!url.contains("://")) return null

        var attrs: Map<String, String> = emptyMap()
        var title = ""
        if (info != null) {
            val comma = titleComma(info)
            val colon = info.indexOf(':')
            val attrPart = if (colon >= 0 && colon < comma) info.substring(colon + 1, comma) else ""
            title = if (comma < info.length) info.substring(comma + 1).trim() else ""
            attrs = parseAttributes(attrPart)
        }

        val group = shared(
            attrs["group-title"]?.takeIf { it.isNotBlank() }
                ?: extGroup?.takeIf { it.isNotBlank() }
                ?: DEFAULT_GROUP
        )
        val name = title.ifBlank { attrs["tvg-name"].orEmpty() }
            .ifBlank { url.substringAfterLast('/').substringBefore('?') }
            .ifBlank { url }

        val type = ContentClassifier.classify(name, group, url)
        if (liveOnly && type != ContentType.LIVE) return null

        val logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http", ignoreCase = true) }
        ua = ua ?: attrs["user-agent"] ?: attrs["http-user-agent"]
        ref = ref ?: attrs["referer"] ?: attrs["http-referrer"]

        return Channel(
            name = name,
            url = url,
            group = group,
            logo = logo,
            userAgent = ua?.takeIf { it.isNotBlank() }?.let(shared),
            referrer = ref?.takeIf { it.isNotBlank() }?.let(shared),
            type = type,
        )
    }

    /** Posición de la coma que separa atributos y título (ignorando comas entre comillas). */
    private fun titleComma(info: String): Int {
        var inQuotes = false
        for (i in info.indices) {
            when (info[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return i
            }
        }
        return info.length
    }

    /** Lee pares clave="valor" (o clave=valor) sin expresiones regulares. */
    internal fun parseAttributes(s: String): Map<String, String> {
        if (s.indexOf('=') < 0) return emptyMap()
        val map = HashMap<String, String>(8)
        val n = s.length
        var i = 0
        while (i < n) {
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            // la clave es la palabra pegada a la izquierda del '='
            var keyEnd = eq
            while (keyEnd > i && s[keyEnd - 1] == ' ') keyEnd--
            var keyStart = keyEnd
            while (keyStart > i && !s[keyStart - 1].isWhitespace()) keyStart--
            val key = s.substring(keyStart, keyEnd).lowercase()

            var vs = eq + 1
            while (vs < n && s[vs] == ' ') vs++
            if (vs >= n) break
            if (s[vs] == '"') {
                val ve = s.indexOf('"', vs + 1)
                if (ve < 0) { map[key] = s.substring(vs + 1).trim(); break }
                map[key] = s.substring(vs + 1, ve).trim()
                i = ve + 1
            } else {
                var ve = vs
                while (ve < n && !s[ve].isWhitespace()) ve++
                map[key] = s.substring(vs, ve)
                i = ve
            }
        }
        return map
    }
}
