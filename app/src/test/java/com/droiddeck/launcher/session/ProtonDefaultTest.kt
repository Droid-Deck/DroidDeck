package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ProtonDefaultTest {
    private val state = """
        {"default": "droiddeck-proton-11-arm64", "applied": 5, "live": true,
         "tools": [{"name": "droiddeck-proton-arm64", "display": "Proton Experimental ARM64", "dir": "Proton Experimental (ARM64)", "valve": true},
                   {"name": "droiddeck-proton-11-arm64", "display": "Proton 11.0 ARM64", "dir": "Proton 11.0 (ARM64)", "valve": true},
                   {"name": "GE-Proton11-7", "display": "GE-Proton11-7", "dir": "GE-Proton11-7", "valve": false}]}
    """.trimIndent()

    private fun proton(dir: String, valve: Boolean) =
        ComponentsManager.Proton(dir, dir, File("/x/$dir"), "/x/$dir", "1", valve)

    @Test
    fun theGuestAnswerNamesTheProtonGamesUse() {
        val parsed = ProtonDefault.parseState(state)
        assertEquals(true to "Proton 11.0 (ARM64)", ProtonDefault.chosen(parsed, null))
        assertTrue(parsed.live)
        assertEquals(3, parsed.tools.size)
    }

    @Test
    fun aRequestNotYetTakenWinsAndATakenOneDoesNot() {
        val parsed = ProtonDefault.parseState(state)
        val newer = ProtonDefault.parseRequest("""{"seq": 6, "valve": false, "dir": "GE-Proton11-7"}""")
        assertEquals(false to "GE-Proton11-7", ProtonDefault.chosen(parsed, newer))
        val taken = ProtonDefault.parseRequest("""{"seq": 5, "valve": false, "dir": "GE-Proton11-7"}""")
        assertEquals(true to "Proton 11.0 (ARM64)", ProtonDefault.chosen(parsed, taken))
    }

    @Test
    fun onlyToolsTheGuestCanStartAreOffered() {
        val parsed = ProtonDefault.parseState(state)
        assertTrue(ProtonDefault.runnable(parsed, proton("GE-Proton11-7", false)))
        assertFalse(ProtonDefault.runnable(parsed, proton("Proton Experimental", true)))
        assertTrue(ProtonDefault.runnable(ProtonDefault.parseState(null), proton("Proton Experimental", true)))
    }

    @Test
    fun aNewRequestAlwaysOrdersAfterTheOldOnes() {
        val parsed = ProtonDefault.parseState(state)
        assertEquals(1_000L, ProtonDefault.nextSeq(1_000L, parsed, null))
        assertEquals(6L, ProtonDefault.nextSeq(2L, parsed, null))
        assertEquals(10_001L, ProtonDefault.nextSeq(2L, parsed, ProtonDefault.parseRequest("""{"seq": 10000, "dir": "x"}""")))
    }

    @Test
    fun brokenFilesMeanNoAnswer() {
        assertNull(ProtonDefault.chosen(ProtonDefault.parseState("{"), ProtonDefault.parseRequest("[]")))
        assertNull(ProtonDefault.parseRequest("""{"seq": 0, "dir": "x"}"""))
    }
}
