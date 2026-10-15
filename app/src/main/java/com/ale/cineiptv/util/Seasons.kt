package com.ale.cineiptv.util

/** Saca el número de temporada del nombre de un episodio ("T2 · E05", "S02E05", "2x05", "Temporada 2"). */
object Seasons {
    private val PATTERNS = listOf(
        Regex("""\bT(\d{1,3})\s*·\s*E\d+"""),
        Regex("""(?i)\bS(\d{1,3})\s*[._\- ]?\s*E\d{1,4}"""),
        Regex("""(?i)\b(\d{1,2})x\d{2,3}\b"""),
        Regex("""(?i)\b(?:temporada|season|saison|staffel)\s*(\d{1,3})"""),
    )

    /** 0 si no se reconoce. */
    fun of(name: String): Int {
        for (p in PATTERNS) {
            p.find(name)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        return 0
    }
}
