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

    @Test
    fun aReplacedBuildsPerGamePicksMoveToTheNewest() {
        val old = ProtonDefault.GameChoice(false, "DroidDeck-Proton 11-11.2")
        val ge = ProtonDefault.GameChoice(false, "GE-Proton11-7")
        val valve = ProtonDefault.GameChoice(true, "DroidDeck-Proton 11-11.2")
        val moved = ProtonDefault.movedGames(mapOf("42" to old, "43" to ge, "44" to null, "45" to valve), setOf("DroidDeck-Proton 11-11.2"), "DroidDeck-Proton 11-11.4")
        assertEquals(mapOf("42" to ProtonDefault.GameChoice(false, "DroidDeck-Proton 11-11.4"), "43" to ge, "44" to null, "45" to valve), moved)
        assertNull(ProtonDefault.movedGames(mapOf("43" to ge, "44" to null), setOf("DroidDeck-Proton 11-11.2"), "DroidDeck-Proton 11-11.4"))
    }

    @Test
    fun movingPicksRewritesTheGamesFile() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(LinuxRuntime.rootDir(context), "root/.local/share/droiddeck-compat/games.json")
        file.parentFile?.deleteRecursively()
        ProtonDefault.requestGame(context, "42", proton("DroidDeck-Proton 11-11.2", false))
        ProtonDefault.requestGame(context, "43", null)
        ProtonDefault.moveGames(context, setOf("DroidDeck-Proton 11-11.2"), "DroidDeck-Proton 11-11.4")
        val games = ProtonDefault.parseGames(file.readText())
        assertEquals(ProtonDefault.GameChoice(false, "DroidDeck-Proton 11-11.4"), games["42"])
        assertTrue(games.containsKey("43"))
        assertNull(games["43"])
    }

    @Test
    fun anUpdateMovesTheDefaultThatNamesTheReplacedTool() {
        val bionic = """
            {"default": "DroidDeck-Bionic-12.8-r30-Proton", "applied": 7, "live": false,
             "tools": [{"name": "DroidDeck-Bionic-12.8-r30-Proton", "display": "r30", "dir": "DroidDeck-Bionic-12.8-r30", "valve": false},
                       {"name": "DroidDeck-Proton-11-12.8", "display": "DD", "dir": "DroidDeck-Proton 11-12.8", "valve": false}]}
        """.trimIndent()
        val parsed = ProtonDefault.parseState(bionic)
        val from = setOf("DroidDeck-Bionic-12.8-r30")
        // applied default, no request yet
        assertEquals(ProtonDefault.Request(100, false, "DroidDeck-Bionic-12.8-r31"),
            ProtonDefault.movedDefault(parsed, null, from, "DroidDeck-Bionic-12.8-r31", 100))
        // a pending request for the replaced tool wins over the applied default and the new seq is above both
        assertEquals(ProtonDefault.Request(51, false, "DroidDeck-Bionic-12.8-r31"),
            ProtonDefault.movedDefault(parsed, ProtonDefault.Request(50, false, "DroidDeck-Bionic-12.8-r30"), from, "DroidDeck-Bionic-12.8-r31", 3))
        // a default on another tool stays as it is
        assertNull(ProtonDefault.movedDefault(parsed, ProtonDefault.Request(50, false, "DroidDeck-Proton 11-12.8"), from, "DroidDeck-Bionic-12.8-r31", 100))
        assertNull(ProtonDefault.movedDefault(ProtonDefault.parseState(state), null, from, "DroidDeck-Bionic-12.8-r31", 100))
        // an applied request (seq not above "applied") no longer decides: the applied default does
        assertEquals("DroidDeck-Bionic-12.8-r31",
            ProtonDefault.movedDefault(parsed, ProtonDefault.Request(7, false, "DroidDeck-Proton 11-12.8"), from, "DroidDeck-Bionic-12.8-r31", 1)?.dir)
    }

    @Test
    fun moveDefaultRewritesTheRequestFile() {
        val context = RuntimeEnvironment.getApplication()
        val d = File(LinuxRuntime.rootDir(context), "root/.local/share/droiddeck-compat").apply { mkdirs() }
        File(d, "state.json").writeText("""{"default": "DroidDeck-Bionic-12.8-r30-Proton", "applied": 7,
            "tools": [{"name": "DroidDeck-Bionic-12.8-r30-Proton", "display": "r30", "dir": "DroidDeck-Bionic-12.8-r30", "valve": false}]}""")
        File(d, "request.json").delete()
        ProtonDefault.moveDefault(context, setOf("DroidDeck-Bionic-12.8-r30"), "DroidDeck-Bionic-12.8-r31")
        val written = ProtonDefault.parseRequest(File(d, "request.json").readText())
        assertEquals("DroidDeck-Bionic-12.8-r31", written?.dir)
        assertFalse(written!!.valve)
        assertTrue(written.seq > 7)
        val before = File(d, "request.json").readText()
        ProtonDefault.moveDefault(context, setOf("DroidDeck-Bionic-12.8-r29"), "DroidDeck-Bionic-12.8-r31")
        assertEquals(before, File(d, "request.json").readText())
    }
}
