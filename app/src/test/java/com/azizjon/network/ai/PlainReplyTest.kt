package com.azizjon.network.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainReplyTest {
    @Test
    fun boldNamesLoseTheirAsterisks() {
        // The reply that showed its asterisks in the thread on v0.15.0.
        assertEquals(
            "Two people currently have open needs: Jordan Kim wants to meet founders working on climate tech, " +
                "and Priya Raman needs a bookkeeper.",
            PlainReply.from(
                "Two people currently have open needs: **Jordan Kim** wants to meet founders working on climate tech, " +
                    "and **Priya Raman** needs a bookkeeper.",
            ),
        )
    }

    @Test
    fun otherEmphasisAndCodeKeepOnlyTheirText() {
        assertEquals("Dana advises Lumen Labs.", PlainReply.from("*Dana* advises __Lumen Labs__."))
        assertEquals("Marta's need is still open.", PlainReply.from("***Marta***'s need is `still open`."))
        assertEquals("(see the note from May)", PlainReply.from("(*see the note from May*)"))
    }

    @Test
    fun headingsAndStarListsBecomePlainLines() {
        val reply = """
            ## Open needs
            * **Jordan Kim** wants climate-tech founders
              * met at a meetup
            - Priya Raman needs a bookkeeper
            1. Tomas Reyes
        """.trimIndent()

        assertEquals(
            """
            Open needs
            - Jordan Kim wants climate-tech founders
              - met at a meetup
            - Priya Raman needs a bookkeeper
            1. Tomas Reyes
            """.trimIndent(),
            PlainReply.from(reply),
        )
    }

    @Test
    fun marksThatAreNotFormattingStay() {
        listOf(
            "Tomas rated it 5 * 3 = 15.",
            "The code is 2*3*4.",
            "Her handle is dana__lee__x and snake_case stays.",
            "#1 priority is the bookkeeper.",
            "**Jordan never closed the bold.",
            "*Emphasis does not\nspan lines*",
            "Only one ` backtick.",
        ).forEach { reply -> assertEquals(reply, PlainReply.from(reply)) }
    }
}
