package com.ale.cineiptv.util

/**
 * Búsqueda "como la de Google" para la barra de búsqueda:
 *  - sin distinguir mayúsculas, acentos ni signos ("proyecto salvacion" = "Proyecto Salvación");
 *  - por palabras en cualquier orden ("galaxias guerra"), aceptando el principio de una palabra;
 *  - con erratas pequeñas ("salvasion", "harri poter");
 *  - con equivalencias inglés/español de títulos conocidos ("star wars" ↔ "la guerra de las galaxias");
 *  - y con una puntuación para poner primero lo que más se parece.
 *
 * No guarda copias de los títulos (las listas pueden tener decenas de miles): normaliza al vuelo.
 */
class SearchQuery private constructor(
    /** Variantes de la búsqueda: la escrita y sus equivalencias (la primera es la escrita). */
    private val variants: List<Variant>,
) {

    private class Variant(val phrase: String, val compact: String, val words: List<String>, val required: List<String>, val penalty: Int)

    /** ¿Coincide este título (o su grupo)? */
    fun matches(name: String, group: String? = null): Boolean = score(name, group) > 0

    /** 0 = no coincide; cuanto más alto, más se parece. */
    fun score(name: String, group: String? = null): Int {
        val title = Search.normalize(name)
        val titleWords = Search.words(title)
        var best = 0
        for (v in variants) {
            val s = scoreVariant(v, title, titleWords) - v.penalty
            if (s > best) best = s
        }
        if (best == 0 && group != null) {
            // El grupo solo cuenta por coincidencia directa (sin erratas): "deportes", "infantil"…
            val g = Search.normalize(group)
            if (variants.any { v -> v.required.all { w -> g.contains(w) } }) best = 10
        }
        return best
    }

    private fun scoreVariant(v: Variant, title: String, titleWords: List<String>): Int {
        if (title == v.phrase) return 100
        if (title.startsWith(v.phrase)) return 90
        if (containsPhrase(title, v.phrase)) return 80
        // Todas las palabras importantes presentes (en cualquier orden, vale el principio de palabra)
        var allPrefix = true
        var fuzzyCost = 0
        for (w in v.required) {
            if (titleWords.any { it.startsWith(w) }) continue
            allPrefix = false
            if (title.contains(w)) { fuzzyCost += 1; continue }
            val d = titleWords.minOfOrNull { Search.wordDistance(w, it) } ?: Int.MAX_VALUE
            if (d > Search.allowedErrors(w)) {
                // "spiderman" frente a "Spider-Man": se comparan sin espacios
                return if (v.compact.length >= 4 && Search.compact(title).contains(v.compact)) 60 else 0
            }
            fuzzyCost += d * 4
        }
        if (allPrefix) return 70
        return (55 - fuzzyCost).coerceAtLeast(20)
    }

    private fun containsPhrase(title: String, phrase: String): Boolean {
        var i = title.indexOf(phrase)
        while (i >= 0) {
            if (i == 0 || title[i - 1] == ' ') return true
            i = title.indexOf(phrase, i + 1)
        }
        return false
    }

    companion object {
        /** null si la búsqueda está vacía (todo coincide). */
        fun of(raw: String): SearchQuery? {
            val phrase = Search.normalize(raw)
            if (phrase.isEmpty()) return null
            val list = ArrayList<Variant>()
            list.add(variant(phrase, 0))
            for (alt in Search.equivalents(phrase)) {
                if (list.none { it.phrase == alt }) list.add(variant(alt, 5))
            }
            return SearchQuery(list)
        }

        private fun variant(phrase: String, penalty: Int): Variant {
            val words = Search.words(phrase)
            val important = words.filter { it !in Search.STOP_WORDS }
            return Variant(phrase, Search.compact(phrase), words, important.ifEmpty { words }, penalty)
        }
    }
}

object Search {

    /** Palabras que no hace falta que aparezcan ("guerra galaxias" encuentra "La guerra de las galaxias"). */
    val STOP_WORDS = setOf(
        "el", "la", "los", "las", "un", "una", "de", "del", "y", "e", "en", "a", "al", "o", "por", "con",
        "the", "of", "and", "an", "in", "on", "to", "for",
    )

    /** Números romanos más comunes en sagas ("Rocky II" = "Rocky 2"). "I" y "V" se dejan: son ambiguos. */
    private val ROMAN = mapOf(
        "ii" to "2", "iii" to "3", "iv" to "4", "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9",
    )

    /** Minúsculas, sin acentos ni signos, con un solo espacio entre palabras. */
    fun normalize(s: String): String {
        val out = StringBuilder(s.length)
        var wordStart = -1          // inicio de la palabra en curso dentro de [out]; -1 = entre palabras
        fun endWord() {
            if (wordStart < 0) return
            val len = out.length - wordStart
            if (len in 2..4) {      // números romanos sueltos → cifras
                ROMAN[out.substring(wordStart)]?.let { out.setLength(wordStart); out.append(it) }
            }
            wordStart = -1
        }
        for (ch in s) {
            val c = fold(ch) ?: continue
            if (c == ' ') {
                endWord()
            } else {
                if (wordStart < 0) {
                    if (out.isNotEmpty()) out.append(' ')
                    wordStart = out.length
                }
                out.append(c)
            }
        }
        endWord()
        return out.toString()
    }

    fun words(normalized: String): List<String> =
        if (normalized.isEmpty()) emptyList() else normalized.split(' ')

    fun compact(normalized: String): String = normalized.replace(" ", "")

    /** Errores de letra que se aceptan en una palabra según su longitud. */
    fun allowedErrors(word: String): Int = when {
        word.length < 5 -> 0
        word.length < 8 -> 1
        else -> 2
    }

    /**
     * Distancia entre lo escrito y una palabra del título: la menor entre la palabra entera
     * y su principio (para cuando aún se está escribiendo: "salvas" ≈ "salvacion").
     */
    fun wordDistance(typed: String, word: String): Int {
        val max = allowedErrors(typed)
        if (max == 0) return if (word.startsWith(typed)) 0 else Int.MAX_VALUE
        var best = levenshtein(typed, word, max)
        if (word.length > typed.length) {
            best = minOf(best, levenshtein(typed, word.substring(0, typed.length), max))
            if (word.length > typed.length + 1) best = minOf(best, levenshtein(typed, word.substring(0, typed.length + 1), max))
        }
        return best
    }

    /**
     * Distancia de edición (letra de más, de menos, cambiada, o dos letras cambiadas de sitio)
     * con corte: devuelve un valor > [max] en cuanto se sabe que lo supera.
     */
    fun levenshtein(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var before = IntArray(b.length + 1)
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                // "slavacion" → "salvacion": dos letras cambiadas de sitio son un solo error
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, before[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val t = before; before = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Letra normalizada; ' ' separa palabras; null = se quita sin separar (apóstrofos). */
    private fun fold(ch: Char): Char? = when (ch) {
        in 'a'..'z', in '0'..'9' -> ch
        in 'A'..'Z' -> ch + 32
        'á', 'à', 'â', 'ä', 'ã', 'å', 'Á', 'À', 'Â', 'Ä', 'Ã', 'Å' -> 'a'
        'é', 'è', 'ê', 'ë', 'É', 'È', 'Ê', 'Ë' -> 'e'
        'í', 'ì', 'î', 'ï', 'Í', 'Ì', 'Î', 'Ï' -> 'i'
        'ó', 'ò', 'ô', 'ö', 'õ', 'Ó', 'Ò', 'Ô', 'Ö', 'Õ', 'ø', 'Ø' -> 'o'
        'ú', 'ù', 'û', 'ü', 'Ú', 'Ù', 'Û', 'Ü' -> 'u'
        'ñ', 'Ñ' -> 'n'
        'ç', 'Ç' -> 'c'
        'ý', 'ÿ', 'Ý' -> 'y'
        // Los apóstrofos se quitan sin separar palabras: "schindler's" = "schindlers"
        '\'', '’', '´', '`' -> null
        else -> if (ch.isLetterOrDigit()) ch.lowercaseChar() else ' '
    }

    // ---------------------------------------------------------------- Equivalencias

    /**
     * Títulos conocidos con nombre distinto en inglés y en español.
     * Cada grupo son formas equivalentes (ya normalizadas: minúsculas, sin acentos).
     */
    private val EQUIVALENTS: List<List<String>> = listOf(
        listOf("star wars", "la guerra de las galaxias"),
        listOf("project hail mary", "proyecto salvacion"),
        listOf("the lord of the rings", "el senor de los anillos"),
        listOf("the hobbit", "el hobbit"),
        listOf("the godfather", "el padrino"),
        listOf("back to the future", "regreso al futuro"),
        listOf("jaws", "tiburon"),
        listOf("home alone", "solo en casa"),
        listOf("the avengers", "los vengadores"),
        listOf("avengers", "vengadores"),
        listOf("the lion king", "el rey leon"),
        listOf("pirates of the caribbean", "piratas del caribe"),
        listOf("the hunger games", "los juegos del hambre"),
        listOf("fast and furious", "fast furious", "a todo gas"),
        listOf("despicable me", "gru mi villano favorito"),
        listOf("minions", "los minions"),
        listOf("the simpsons", "los simpson"),
        listOf("ghostbusters", "cazafantasmas"),
        listOf("jurassic park", "parque jurasico"),
        listOf("jurassic world", "mundo jurasico"),
        listOf("mission impossible", "mision imposible"),
        listOf("spider man", "spiderman", "hombre arana"),
        listOf("guardians of the galaxy", "guardianes de la galaxia"),
        listOf("the incredibles", "los increibles"),
        listOf("finding nemo", "buscando a nemo"),
        listOf("finding dory", "buscando a dory"),
        listOf("inside out", "del reves"),
        listOf("how to train your dragon", "como entrenar a tu dragon"),
        listOf("beauty and the beast", "la bella y la bestia"),
        listOf("the little mermaid", "la sirenita"),
        listOf("snow white", "blancanieves"),
        listOf("sleeping beauty", "la bella durmiente"),
        listOf("the jungle book", "el libro de la selva"),
        listOf("frozen", "frozen el reino del hielo"),
        listOf("tangled", "enredados"),
        listOf("the exorcist", "el exorcista"),
        listOf("the shining", "el resplandor"),
        listOf("gone with the wind", "lo que el viento se llevo"),
        listOf("the good the bad and the ugly", "el bueno el feo y el malo"),
        listOf("schindlers list", "la lista de schindler"),
        listOf("the silence of the lambs", "el silencio de los corderos"),
        listOf("fight club", "el club de la lucha"),
        listOf("the shawshank redemption", "cadena perpetua"),
        listOf("the green mile", "la milla verde"),
        listOf("planet of the apes", "el planeta de los simios"),
        listOf("men in black", "hombres de negro"),
        listOf("die hard", "jungla de cristal"),
        listOf("lethal weapon", "arma letal"),
        listOf("the hangover", "resacon en las vegas"),
        listOf("the expendables", "los mercenarios"),
        listOf("the chronicles of narnia", "las cronicas de narnia"),
        listOf("ice age", "la edad de hielo"),
        listOf("the matrix", "matrix"),
        listOf("the terminator", "terminator"),
        listOf("the dark knight", "el caballero oscuro"),
        listOf("wonder woman", "mujer maravilla"),
        listOf("black panther", "pantera negra"),
        listOf("ant man", "antman", "el hombre hormiga"),
        listOf("doctor strange", "doctor extrano"),
        listOf("the karate kid", "karate kid"),
        listOf("the princess bride", "la princesa prometida"),
        listOf("the neverending story", "la historia interminable"),
        listOf("the goonies", "los goonies"),
        listOf("gremlins", "los gremlins"),
        listOf("raiders of the lost ark", "en busca del arca perdida"),
        listOf("the sixth sense", "el sexto sentido"),
        listOf("the pianist", "el pianista"),
        listOf("the revenant", "el renacido"),
        listOf("the wolf of wall street", "el lobo de wall street"),
        listOf("the martian", "marte"),
        listOf("interstellar", "interestelar"),
        listOf("inception", "origen"),
        listOf("the boss baby", "el bebe jefazo"),
        listOf("the secret life of pets", "mascotas"),
        listOf("zootopia", "zootropolis"),
        listOf("coco", "coco"),
        listOf("up", "up"),
        listOf("monsters inc", "monstruos sa"),
        listOf("toy story", "toy story"),
        listOf("game of thrones", "juego de tronos"),
        listOf("house of the dragon", "la casa del dragon"),
        listOf("the rings of power", "los anillos de poder"),
        listOf("money heist", "la casa de papel"),
        listOf("squid game", "el juego del calamar"),
        listOf("the walking dead", "los muertos vivientes"),
        listOf("the big bang theory", "big bang"),
        listOf("prison break", "fuga de la prision"),
        listOf("the mandalorian", "el mandaloriano"),
        listOf("wednesday", "miercoles"),
        listOf("peppa pig", "peppa la cerdita"),
        listOf("paw patrol", "la patrulla canina"),
        listOf("sponge bob", "spongebob", "bob esponja"),
    ).map { g -> g.distinct() }.filter { it.size > 1 }

    /**
     * Otras formas de la búsqueda: si contiene un título conocido, se cambia por sus equivalentes
     * ("star wars episodio 4" → "la guerra de las galaxias episodio 4").
     */
    fun equivalents(phrase: String): List<String> {
        // Se comparan sin artículos ni preposiciones: "guerra de las galaxias" = "la guerra de las galaxias"
        val padded = " ${core(phrase)} "
        val out = ArrayList<String>()
        for (group in EQUIVALENTS_CORE) {
            for (form in group) {
                if (form.isEmpty() || !padded.contains(" $form ")) continue
                for (other in group) {
                    if (other != form) out.add(padded.replace(" $form ", " $other ").trim())
                }
            }
        }
        return out
    }

    private fun core(phrase: String): String = words(phrase).filter { it !in STOP_WORDS }.joinToString(" ")

    private val EQUIVALENTS_CORE: List<List<String>> by lazy {
        EQUIVALENTS.map { g -> g.map { core(it) }.distinct() }.filter { it.size > 1 }
    }
}
