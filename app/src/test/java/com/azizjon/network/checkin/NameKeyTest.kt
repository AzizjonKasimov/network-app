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

    @Test
    fun fullWidthBracketsFromAnAsianKeyboardAreBracketsToo() {
        assertEquals("김민수", NameKey.of("김민수（루멘）"))
        assertEquals(NameKey.of("Minsu Kim"), NameKey.of("Minsu Kim［Lumen］"))
    }

    @Test
    fun theExactKeyKeepsWhatIsInBrackets() {
        assertEquals("cto lumen minsu", NameKey.exact("Minsu (Lumen CTO)"))
        assertEquals(NameKey.exact("minsu (lumen cto) 🚀"), NameKey.exact("Minsu (Lumen CTO)"))
        assertNotEquals(NameKey.exact("Minsu (Gym)"), NameKey.exact("Minsu (Lumen CTO)"))
        // Without brackets the two keys agree.
        assertEquals(NameKey.of("José Müller"), NameKey.exact("José Müller"))
    }

    @Test
    fun wordsKeepTheirWrittenOrder() {
        assertEquals(listOf("minsu", "k"), NameKey.words("Minsu K. (Lumen)"))
        assertEquals(listOf("minsu", "k", "lumen"), NameKey.words("Minsu K. (Lumen)", keepBrackets = true))
    }
}
