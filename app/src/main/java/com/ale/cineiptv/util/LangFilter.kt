package com.ale.cineiptv.util

/**
 * Detecta el idioma/país que los proveedores ponen en grupos y nombres, para el filtro
 * "ver solo un idioma":
 *  - delante:  "ES| DEPORTES", "|ES| La 1", "[ES] Cine", "ES - Toy Story", "SPAIN: …", "LATINO | …";
 *  - detrás o entre corchetes: "Dune (2021) [ES]", "Dune - Castellano", "Dune (VOSE)", "Dune [ES/EN]";
 *  - versiones: VOSE (original con subtítulos en español) y DUAL/MULTI (varios idiomas).
 *
 * Al filtrar se agrupan variantes del mismo idioma (EN = UK, US, CA; LAT = MX) y lo que no
 * indica idioma se sigue mostrando (no se sabe de qué idioma es, así que no se esconde).
 */
object LangFilter {

    /** Varios idiomas en el mismo vídeo: vale para cualquier filtro. */
    const val MULTI = "MULTI"

    /** Palabras completas que equivalen a un código. */
    private val WORDS = mapOf(
        "ESPAÑA" to "ES", "ESPANA" to "ES", "SPAIN" to "ES", "SPANISH" to "ES", "ESPAÑOL" to "ES",
        "ESPANOL" to "ES", "CASTELLANO" to "ES", "ESP" to "ES", "SPA" to "ES",
        "LATINO" to "LAT", "LATAM" to "LAT", "LATIN" to "LAT", "LATINOAMERICA" to "LAT", "MX" to "MX", "MEXICO" to "MX",
        "ENGLISH" to "EN", "ENG" to "EN", "UK" to "UK", "GB" to "UK", "USA" to "US", "US" to "US", "CANADA" to "CA",
        "FRANCE" to "FR", "FRENCH" to "FR", "FRA" to "FR",
        "GERMANY" to "DE", "GERMAN" to "DE", "DEUTSCH" to "DE", "GER" to "DE", "DEU" to "DE",
        "ITALY" to "IT", "ITALIA" to "IT", "ITALIAN" to "IT", "ITA" to "IT",
        "PORTUGAL" to "PT", "POR" to "PT", "BRASIL" to "BR", "BRAZIL" to "BR",
        "ARABIC" to "AR", "ARAB" to "AR", "TURKEY" to "TR", "TURKISH" to "TR", "TÜRK" to "TR",
        "NETHERLANDS" to "NL", "DUTCH" to "NL", "POLAND" to "PL", "POLSKA" to "PL",
        "ROMANIA" to "RO", "ALBANIA" to "AL", "GREECE" to "GR", "RUSSIA" to "RU",
        "VOSE" to "VOSE", "VOS" to "VOSE", "SUBTITULADA" to "VOSE", "SUBTITULADO" to "VOSE",
        "DUAL" to MULTI, "MULTI" to MULTI, "MULTILANG" to MULTI, "MULTIAUDIO" to MULTI,
    )

    /**
     * Códigos que también se aceptan detrás o entre corchetes. Aquí se es más estricto que
     * delante (un "(IT)" al final es fácil de confundir), así que solo los habituales.
     */
    private val TAIL_CODES = setOf(
        "ES", "LAT", "MX", "EN", "UK", "US", "FR", "DE", "IT", "PT", "BR", "AR", "TR", "NL", "PL", "RO", "RU", "GR",
    )

    /** Prefijos frecuentes que NO son idiomas. */
    private val NOT_LANG = setOf(
        "HD", "FHD", "UHD", "SD", "4K", "8K", "3D", "VIP", "PPV", "VOD", "TV", "XXX", "NEW", "TOP", "MIX",
        "KID", "KIDS", "ALL", "MAX", "NBA", "NFL", "NHL", "MLB", "UFC", "WWE", "F1", "LIVE", "HEVC", "RAW",
        "THE", "LA", "EL", "LOS", "LAS", "DEL", "TNT", "BBC", "ITV", "CNN", "FOX", "HBO", "AMC", "MTV",
    )

    /** Variantes que al filtrar cuentan como el mismo idioma. */
    private val FAMILY = mapOf("UK" to "EN", "US" to "EN", "CA" to "EN", "MX" to "LAT")

    private const val OPENERS = " |[(★•◉●▶-_:#*"
    private const val SEPARATORS = "|:]-)/•·"

    /** Código escrito al principio del texto (null si no hay). Sin agrupar variantes. */
    fun codeOf(text: String): String? {
        var i = 0
        while (i < text.length && text[i] in OPENERS) i++
        val start = i
        while (i < text.length && (text[i].isLetter())) i++
        if (i == start) return null
        val word = text.substring(start, i).uppercase()
        // Debe ir seguido de un separador claro (no "ES CINE" pero sí "ES | CINE" o "ES: CINE")
        var j = i
        while (j < text.length && text[j] == ' ') j++
        val separated = j >= text.length || text[j] in SEPARATORS
        WORDS[word]?.let { code -> return if (separated || word.length > 3) code else null }
        if (!separated) return null
        if (word.length !in 2..3 || word in NOT_LANG) return null
        // Un código desconocido seguido de "/" suele ser parte del nombre ("AC/DC"), no un idioma
        if (word !in TAIL_CODES && j < text.length && text[j] == '/') return null
        // Códigos en mayúsculas en el original ("ES|"), no palabras normales ("Cine:")
        val original = text.substring(start, i)
        if (original != original.uppercase()) return null
        return word
    }

    /**
     * Código del texto mirando delante y, si no hay, detrás / entre corchetes.
     * Sin agrupar variantes (sirve para mostrar "UK", "MX"…).
     */
    fun tagOf(text: String): String? = codeOf(text) ?: tailCodeOf(text)

    /** Igual que [tagOf] pero agrupando variantes (UK/US/CA → EN, MX → LAT): lo que usa el filtro. */
    fun detect(text: String): String? = tagOf(text)?.let { family(it) }

    fun family(code: String): String = FAMILY[code] ?: code

    /** "Dune (2021) [ES]", "Dune [VOSE]", "Dune (Castellano)", "Dune - ES", "Dune | LAT". */
    private fun tailCodeOf(text: String): String? {
        // 1) Entre corchetes o paréntesis, en cualquier sitio: [ES] (ES/EN) [Castellano] (VOSE)
        var open = text.indexOfAny(charArrayOf('[', '('))
        while (open >= 0) {
            val close = text.indexOfAny(charArrayOf(']', ')'), open + 1)
            if (close < 0) break
            val inside = text.substring(open + 1, close)
            if (inside.length <= 24) {
                for (token in inside.split('/', ',', '-', '|', ' ', '+')) {
                    tokenCode(token)?.let { return it }
                }
            }
            open = text.indexOfAny(charArrayOf('[', '('), close + 1)
        }
        // 2) Al final tras un separador: "Dune - ES", "Dune | Castellano", "Dune / LAT"
        val trimmed = text.trimEnd()
        val sep = trimmed.lastIndexOfAny(charArrayOf('-', '|', '/', '•', '·'))
        if (sep > 0 && sep < trimmed.length - 1) {
            val last = trimmed.substring(sep + 1).trim()
            if (last.isNotEmpty() && last.all { it.isLetter() }) tokenCode(last)?.let { return it }
        }
        return null
    }

    private fun tokenCode(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val up = t.uppercase()
        WORDS[up]?.let { return it }
        // Los códigos cortos solo si están en mayúsculas en el original ("[ES]", no "(es)" de una frase)
        return if (up in TAIL_CODES && t == up) up else null
    }

    /**
     * ¿Se muestra con el idioma elegido? Se mira el grupo y, si no lo indica, el nombre.
     * Sin etiqueta o con varios idiomas (DUAL/MULTI) se muestra siempre.
     */
    fun matches(lang: String, group: String, name: String?): Boolean = LangMatcher(lang).ok(group, name)

    /** Nombre para mostrar en el selector. */
    fun label(code: String): String = when (code) {
        "ES" -> "Español (España)"
        "LAT" -> "Español latino (incluye México)"
        "MX" -> "México"
        "EN" -> "Inglés (incluye Reino Unido, EE. UU. y Canadá)"
        "UK" -> "Reino Unido"
        "US" -> "Estados Unidos"
        "CA" -> "Canadá"
        "VOSE" -> "Versión original subtitulada"
        MULTI -> "Varios idiomas"
        "FR" -> "Francés"
        "DE" -> "Alemán"
        "IT" -> "Italiano"
        "PT" -> "Portugués"
        "BR" -> "Brasil"
        "AR" -> "Árabe"
        "TR" -> "Turco"
        "NL" -> "Neerlandés"
        "PL" -> "Polaco"
        "RO" -> "Rumano"
        "AL" -> "Albanés"
        "GR" -> "Griego"
        "RU" -> "Ruso"
        else -> code
    }
}

/**
 * Comprobación rápida para listas grandes: recuerda el código de cada grupo.
 * Lo que no indica idioma, o tiene varios (DUAL/MULTI), se muestra con cualquier filtro.
 */
class LangMatcher(lang: String) {
    private val lang = if (lang.isEmpty()) "" else LangFilter.family(lang)
    private val groupCodes = HashMap<String, String?>()

    fun ok(group: String, name: String?): Boolean {
        if (lang.isEmpty()) return true
        val g = if (groupCodes.containsKey(group)) groupCodes[group] else LangFilter.detect(group).also { groupCodes[group] = it }
        val code = g ?: name?.let { LangFilter.detect(it) }
        return code == null || code == LangFilter.MULTI || code == lang
    }
}
