package com.ale.cineiptv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {

    private fun q(s: String) = SearchQuery.of(s)!!

    @Test fun sinAcentos() {
        assertTrue(q("proyecto salvacion").matches("Proyecto Salvación"))
        assertTrue(q("PROYECTO SALVACIÓN").matches("proyecto salvacion (2026)"))
        assertTrue(q("senor anillos").matches("El Señor de los Anillos: La Comunidad del Anillo"))
    }

    @Test fun palabrasEnCualquierOrden() {
        assertTrue(q("galaxias guerra").matches("La guerra de las galaxias"))
        assertTrue(q("guerra galax").matches("La guerra de las galaxias"))
    }

    @Test fun erratas() {
        assertTrue(q("proyecto salvasion").matches("Proyecto Salvación"))
        assertTrue(q("harry poter").matches("Harry Potter y la piedra filosofal"))
        assertTrue(q("slavacion").matches("Proyecto Salvación"))
        assertFalse(q("cine").matches("Cinco lobitos"))
    }

    @Test fun equivalenciasInglesEspanol() {
        assertTrue(q("star wars").matches("La guerra de las galaxias. Episodio IV"))
        assertTrue(q("star wars").matches("Star Wars: El ascenso de Skywalker"))
        assertTrue(q("guerra de las galaxias").matches("Star Wars: Andor"))
        assertTrue(q("project hail mary").matches("Proyecto Salvación"))
        assertTrue(q("spiderman").matches("Spider-Man: No Way Home"))
    }

    @Test fun romanos() {
        assertTrue(q("rocky 2").matches("Rocky II"))
        assertTrue(q("episodio 4").matches("Star Wars Episodio IV"))
    }

    @Test fun ordenPorParecido() {
        val s = q("salvacion")
        assertTrue(s.score("Salvación") > s.score("Proyecto Salvación"))
        assertTrue(s.score("Proyecto Salvación") > s.score("Salvasion final"))
        assertEquals(0, s.score("Matrix"))
    }

    @Test fun normaliza() {
        assertEquals("schindlers list", Search.normalize("Schindler's  List"))
        assertEquals("es rocky 3 4k", Search.normalize("|ES| Rocky III (4K)"))
        assertEquals(null, SearchQuery.of("  "))
    }
}
