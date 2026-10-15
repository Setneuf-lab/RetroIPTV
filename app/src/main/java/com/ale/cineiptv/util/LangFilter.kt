package com.ale.cineiptv.util

/**
 * Detecta el idioma/país que los proveedores ponen delante de grupos y nombres:
 * "ES| DEPORTES", "|ES| La 1", "[ES] Cine", "ES - Toy Story", "SPAIN: …", "LATINO | …".
 * Sirve para el filtro "ver solo un idioma".
 */
object LangFilter {

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
    )

    /** Prefijos frecuentes que NO son idiomas. */
    private val NOT_LANG = setOf(
        "HD", "FHD", "UHD", "SD", "4K", "8K", "3D", "VIP", "PPV", "VOD", "TV", "XXX", "NEW", "TOP", "MIX",
        "KID", "KIDS", "ALL", "MAX", "NBA", "NFL", "NHL", "MLB", "UFC", "WWE", "F1", "LIVE", "HEVC", "RAW",
        "THE", "LA", "EL", "LOS", "LAS", "DEL", "TNT", "BBC", "ITV", "CNN", "FOX", "HBO", "AMC", "MTV",
    )

    private const val OPENERS = " |[(★•◉●▶-_:#*"
    private const val SEPARATORS = "|:]-)/•·"

    /** Código detectado al principio del texto (null si no hay). */
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
        // Códigos en mayúsculas en el original ("ES|"), no palabras normales ("Cine:")
        val original = text.substring(start, i)
        if (original != original.uppercase()) return null
        return word
    }

    /** ¿El elemento es del idioma elegido? Se mira el grupo y, si no lo indica, el nombre. */
    fun matches(lang: String, group: String, name: String?): Boolean {
        if (lang.isEmpty()) return true
        val code = codeOf(group) ?: name?.let { codeOf(it) }
        return code == lang
    }

    /** Nombre para mostrar en el selector. */
    fun label(code: String): String = when (code) {
        "ES" -> "Español (España)"
        "LAT" -> "Español latino"
        "MX" -> "México"
        "EN" -> "Inglés"
        "UK" -> "Reino Unido"
        "US" -> "Estados Unidos"
        "CA" -> "Canadá"
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

/** Comprobación rápida para listas grandes: recuerda el código de cada grupo. */
class LangMatcher(private val lang: String) {
    private val groupCodes = HashMap<String, String?>()

    fun ok(group: String, name: String?): Boolean {
        if (lang.isEmpty()) return true
        val g = if (groupCodes.containsKey(group)) groupCodes[group] else LangFilter.codeOf(group).also { groupCodes[group] = it }
        return (g ?: name?.let { LangFilter.codeOf(it) }) == lang
    }
}
