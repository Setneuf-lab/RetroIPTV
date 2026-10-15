package com.ale.cineiptv.util

/**
 * Separa la calidad que muchos proveedores ponen al final del nombre
 * ("La 1 FHD", "DAZN 1 (1080p)", "Antena 3 ᴴᴰ", "Cine 4K HEVC") para mostrarla
 * como una etiqueta que siempre se ve, aunque el nombre no quepa entero.
 */
object ChannelName {

    private val QUALITY = setOf(
        "4K", "8K", "UHD", "FHD", "HD", "SD", "HQ", "LQ", "FULLHD", "RAW", "HDR", "HDR10", "DV",
        "HEVC", "H265", "H.265", "X265", "H264", "H.264", "AVC",
        "2160P", "1440P", "1080P", "1080I", "720P", "576P", "480P", "2160", "1080", "720",
        "4K+", "UHD+", "FHD+", "HD+", "50FPS", "60FPS", "25FPS",
    )

    /** Letras pequeñas que usan algunas listas (ᴴᴰ, ᶠᴴᴰ…). */
    private val SUPERSCRIPT = mapOf(
        'ᴬ' to 'A', 'ᴮ' to 'B', 'ᶜ' to 'C', 'ᴰ' to 'D', 'ᴱ' to 'E', 'ᶠ' to 'F', 'ᴳ' to 'G', 'ᴴ' to 'H',
        'ᴵ' to 'I', 'ᴶ' to 'J', 'ᴷ' to 'K', 'ᴸ' to 'L', 'ᴹ' to 'M', 'ᴺ' to 'N', 'ᴼ' to 'O', 'ᴾ' to 'P',
        'ᴿ' to 'R', 'ˢ' to 'S', 'ᵀ' to 'T', 'ᵁ' to 'U', 'ⱽ' to 'V', 'ᵂ' to 'W', 'ˣ' to 'X',
        '⁰' to '0', '¹' to '1', '²' to '2', '³' to '3', '⁴' to '4', '⁵' to '5', '⁶' to '6', '⁷' to '7', '⁸' to '8', '⁹' to '9',
    )

    private const val SEPARATORS = " -|:·_/•◉●▶"

    private fun normalize(token: String): String =
        token.map { SUPERSCRIPT[it] ?: it }.joinToString("").uppercase()

    private fun isQuality(token: String): Boolean {
        val t = normalize(token.trim(*SEPARATORS.toCharArray()))
        return t.isNotEmpty() && t in QUALITY
    }

    private val YEAR_END = Regex("""\s*[(\[]((19|20)\d\d)[)\]]\s*$""")

    /**
     * Devuelve (nombre sin calidad, calidad) o (nombre, null) si no hay calidad.
     * Busca la calidad al final ("Dune FHD", "Dune 4K (2021)") y al principio ("[4K] Dune").
     */
    fun split(name: String): Pair<String, String?> {
        // El año al final se conserva: "Dune 4K (2021)" → "Dune (2021)" + 4K
        val year = YEAR_END.find(name)
        if (year != null) {
            val (n, q) = splitEnd(name.substring(0, year.range.first))
            if (q != null) return "$n (${year.groupValues[1]})" to q
        }
        val (n, q) = splitEnd(name)
        val (n2, q2) = splitStart(n)
        val all = listOfNotNull(q2, q).joinToString(" ").ifEmpty { null }
        return n2 to all
    }

    /** "[4K] Dune", "4K | Dune", "UHD - Dune". */
    private fun splitStart(name: String): Pair<String, String?> {
        val t = name.trimStart()
        if (t.startsWith("[") || t.startsWith("(")) {
            val close = t.indexOf(if (t[0] == '[') ']' else ')')
            if (close > 1) {
                val tokens = t.substring(1, close).split(' ', '|', '/', ',').filter { it.isNotBlank() }
                val rest = t.substring(close + 1).trimStart(*SEPARATORS.toCharArray())
                if (tokens.isNotEmpty() && tokens.all(::isQuality) && rest.isNotEmpty())
                    return rest to tokens.joinToString(" ") { normalize(it) }
            }
            return name to null
        }
        val cut = t.indexOfFirst { it in SEPARATORS }
        if (cut <= 0) return name to null
        val token = t.substring(0, cut)
        val rest = t.substring(cut).trimStart(*SEPARATORS.toCharArray())
        // Solo si va seguido de un separador claro ("4K | Dune"), no "HD Show"
        val sep = t.substring(cut).trimStart(' ')
        if (isQuality(token) && rest.isNotEmpty() && sep.isNotEmpty() && sep[0] in "-|:·•")
            return rest to normalize(token)
        return name to null
    }

    private fun splitEnd(name: String): Pair<String, String?> {
        var rest = name.trimEnd()
        val found = ArrayList<String>()
        while (found.size < 4) {
            rest = rest.trimEnd(*SEPARATORS.toCharArray())
            if (rest.isEmpty()) break
            val last = rest.last()
            if (last == ')' || last == ']') {
                // "(1080p)", "[FHD HEVC]"
                val open = rest.lastIndexOf(if (last == ')') '(' else '[')
                if (open < 0) break
                val inner = rest.substring(open + 1, rest.length - 1)
                val tokens = inner.split(' ', '|', '/', ',').filter { it.isNotBlank() }
                if (tokens.isEmpty() || !tokens.all(::isQuality)) break
                found.addAll(0, tokens.map { normalize(it) })
                rest = rest.substring(0, open)
            } else {
                val cut = rest.indexOfLast { it in SEPARATORS || it == '(' || it == '[' }
                val token = rest.substring(cut + 1)
                // "4K", "1080p"… y también "ᴴᴰ" pegado al nombre ("Antena3ᴴᴰ")
                if (isQuality(token)) {
                    found.add(0, normalize(token))
                    rest = rest.substring(0, cut + 1)
                } else {
                    val supIdx = token.indexOfFirst { it in SUPERSCRIPT }
                    if (supIdx <= 0 || !isQuality(token.substring(supIdx))) break
                    found.add(0, normalize(token.substring(supIdx)))
                    rest = rest.substring(0, cut + 1 + supIdx)
                }
            }
        }
        val clean = rest.trimEnd(*SEPARATORS.toCharArray()).trimEnd()
        // Nombres que son solo un número ("Canal 1080") o se quedarían vacíos: se dejan como están
        if (found.isEmpty() || clean.isEmpty() || found.all { it.all(Char::isDigit) }) return name to null
        return clean to found.distinct().joinToString(" ")
    }
}
