package com.droiddeck.launcher.session

import com.droiddeck.launcher.runtime.LinuxRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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

    @Test
    fun perGameChoicesPreserveCustomizeAndInheritSemantics() {
        val games = ProtonDefault.parseGames(
            """{"version":1,"games":{"42":{"valve":false,"dir":"GE-Proton11-7"},"43":null,"0":null,"bad":{},"44":{"valve":"false","dir":"GE-Proton9-1"}}}""",
        )
        assertEquals(ProtonDefault.GameChoice(false, "GE-Proton11-7"), games["42"])
        assertTrue(games.containsKey("43"))
        assertNull(games["43"])
        assertFalse(games.containsKey("0"))
        assertFalse(games.containsKey("bad"))
        assertFalse(games.containsKey("44"))
        assertTrue(ProtonDefault.parseGames("""{"version":2,"games":{"42":null}}""").isEmpty())
    }

    @Test
    fun perGameRequestsPersistCustomizeThenInherit() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(LinuxRuntime.rootDir(context), "root/.local/share/droiddeck-compat/games.json")
        file.parentFile?.deleteRecursively()
        val selected = proton("GE-Proton11-7", false)

        ProtonDefault.requestGame(context, "42", selected)
        assertEquals(ProtonDefault.GameChoice(false, "GE-Proton11-7"), ProtonDefault.parseGames(file.readText())["42"])

        ProtonDefault.requestGame(context, "42", null)
        val inherited = ProtonDefault.parseGames(file.readText())
        assertTrue(inherited.containsKey("42"))
        assertNull(inherited["42"])
    }

    @Test
    fun missingPerGameProtonIdentityRemainsStoredButUnresolved() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(LinuxRuntime.rootDir(context), "root/.local/share/droiddeck-compat/games.json")
        file.parentFile?.deleteRecursively()
        ProtonDefault.requestGame(context, "42", proton("GE-Proton9-1", false))

        assertEquals(ProtonDefault.GameChoice(false, "GE-Proton9-1"), ProtonDefault.gameChoice(context, "42"))
        assertNull(ProtonDefault.gameSelectedId(context, "42", listOf(proton("GE-Proton11-7", false))))
        assertEquals(ProtonDefault.GameChoice(false, "GE-Proton9-1"), ProtonDefault.parseGames(file.readText())["42"])
    }
}
