package com.ale.cineiptv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LangFilterTest {

    @Test fun delante() {
        assertEquals("ES", LangFilter.detect("ES| DEPORTES"))
        assertEquals("ES", LangFilter.detect("|ES| La 1"))
        assertEquals("ES", LangFilter.detect("[ES] Cine"))
        assertEquals("LAT", LangFilter.detect("LATINO | Acción"))
        assertNull(LangFilter.detect("ES CINE"))
        assertNull(LangFilter.detect("Cine: estrenos"))
        assertNull(LangFilter.detect("HD | Deportes"))
    }

    @Test fun detrasYEntreCorchetes() {
        assertEquals("ES", LangFilter.detect("Dune (2021) [ES]"))
        assertEquals("ES", LangFilter.detect("Dune - Castellano"))
        assertEquals("LAT", LangFilter.detect("Dune | LAT"))
        assertEquals("ES", LangFilter.detect("Dune [ES/EN]"))
        assertEquals("ES", LangFilter.detect("Dune (En español)"))
        assertEquals("VOSE", LangFilter.detect("Dune (VOSE)"))
        assertEquals(LangFilter.MULTI, LangFilter.detect("Dune [DUAL]"))
    }

    @Test fun noConfundeTitulos() {
        assertNull(LangFilter.detect("Spider-Man"))
        assertNull(LangFilter.detect("Alien (1979)"))
        assertNull(LangFilter.detect("Mission: Impossible - Dead Reckoning"))
        assertNull(LangFilter.detect("AC/DC Live"))
        assertNull(LangFilter.detect("Algo (es) raro"))
    }

    @Test fun agrupaVariantes() {
        assertEquals("EN", LangFilter.detect("UK| Sky News"))
        assertEquals("EN", LangFilter.detect("USA | CNN"))
        assertEquals("LAT", LangFilter.detect("MX| Televisa"))
        assertEquals("UK", LangFilter.tagOf("UK| Sky News"))   // para mostrar se mantiene
    }

    @Test fun filtro() {
        val es = LangMatcher("ES")
        assertTrue(es.ok("ES| Cine", "Dune"))
        assertFalse(es.ok("EN| Movies", "Dune"))
        assertTrue(es.ok("Estrenos 2024", "Dune"))          // sin etiqueta: se ve
        assertTrue(es.ok("Estrenos 2024", "Dune [DUAL]"))   // varios idiomas: se ve
        assertFalse(es.ok("Estrenos 2024", "Dune [EN]"))
        val en = LangMatcher("UK")                          // filtro antiguo guardado
        assertTrue(en.ok("USA | Movies", "Dune"))
    }
}
