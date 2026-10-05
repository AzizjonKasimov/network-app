package com.azizjon.network.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NameKeyTest {
    @Test
    fun caseWordOrderAccentsAndDecorationDoNotMatter() {
        val key = NameKey.of("Minsu Kim")

        assertEquals(key, NameKey.of("kim minsu"))
        assertEquals(key, NameKey.of("  Minsu   KIM 🚀 "))
        assertEquals(key, NameKey.of("Minsu Kim (Lumen Labs)"))
        assertEquals(NameKey.of("Jose Muller"), NameKey.of("José Müller"))
    }

    @Test
    fun hangulStaysWhole() {
        assertEquals("김민수", NameKey.of("김민수"))
        assertEquals(NameKey.of("김민수"), NameKey.of(" 김민수 ✨"))
    }

    @Test
    fun differentNamesStayDifferent() {
        assertNotEquals(NameKey.of("Ana Lee"), NameKey.of("Ana"))
        assertEquals("", NameKey.of("🎉🎉"))
    }
}
