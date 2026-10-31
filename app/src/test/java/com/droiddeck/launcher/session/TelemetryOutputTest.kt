package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TelemetryOutputTest {
    @get:Rule val tmp = TemporaryFolder()

    // DiRT Showdown's own line, as found in its prefix on the Retroid Pocket Duo.
    private val showdown = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<hardware_settings_config>\n" +
        "\t<audio mono=\"false\" />\n\t<motion enabled=\"true\" ip=\"dbox\" port=\"20777\" delay=\"1\" extradata=\"0\" />\n" +
        "</hardware_settings_config>\n"

    @Test fun turningOnPointsTheMotionLineHereAndKeepsTheRest() {
        val on = TelemetryOutput.turnedOn(showdown)!!
        assertTrue(on.contains("<motion enabled=\"true\" ip=\"127.0.0.1\" port=\"20777\" delay=\"1\" extradata=\"3\" />"))
        assertEquals(showdown.replace("ip=\"dbox\"", "ip=\"127.0.0.1\"").replace("extradata=\"0\"", "extradata=\"3\""), on)
        assertTrue(TelemetryOutput.pointsHere(TelemetryOutput.element(on)!!.value))
        assertFalse(TelemetryOutput.pointsHere(TelemetryOutput.element(showdown)!!.value))
    }

    @Test fun theUdpLineWinsOverTheMotionLineAndLongerTagsAreIgnored() {
        val xml = "<x><motion_platform><dbox enabled=\"true\" /></motion_platform>" +
            "<motion enabled=\"true\" ip=\"dbox\" port=\"20777\" />" +
            "<udp enabled=\"false\" extradata=\"0\" ip=\"127.0.0.1\" port=\"20777\" delay=\"1\" /></x>"
        assertTrue(TelemetryOutput.element(xml)!!.value.startsWith("<udp "))
        val on = TelemetryOutput.turnedOn(xml)!!
        assertTrue(on.contains("<udp enabled=\"true\" extradata=\"3\" ip=\"127.0.0.1\" port=\"20777\" delay=\"1\" />"))
        assertTrue(on.contains("<motion enabled=\"true\" ip=\"dbox\" port=\"20777\" />"))
        assertNull(TelemetryOutput.element("<x><motion_platform enabled=\"true\" /></x>"))
    }

    @Test fun turningOffPutsTheOriginalLineBackOrSwitchesItOff() {
        val on = TelemetryOutput.turnedOn(showdown)!!
        assertEquals(showdown, TelemetryOutput.turnedOff(on, showdown))
        assertTrue(TelemetryOutput.turnedOff(on, null)!!.contains("<motion enabled=\"false\" ip=\"127.0.0.1\""))
    }

    @Test fun missingAttributesAreAdded() {
        assertEquals("<udp enabled=\"true\" ip=\"127.0.0.1\" />", TelemetryOutput.withAttr("<udp enabled=\"true\" />", "ip", "127.0.0.1"))
        assertEquals("<udp a=\"1\" port=\"20777\">", TelemetryOutput.withAttr("<udp a=\"1\">", "port", "20777"))
        assertTrue(TelemetryOutput.pointsHere("<udp enabled=\"true\" ip=\"127.0.0.1\" port=\"20777\" />"))
    }

    @Test fun scanFindsTheGameAndARoundTripRestoresTheFile() {
        val compatdata = tmp.newFolder("steamapps", "compatdata")
        val config = configFile(compatdata, "2848447563", "DiRT Showdown")
        config.writeText(showdown)
        configFile(compatdata, "12345", "Some Other Game").writeText("<hardware_settings_config />")

        val games = TelemetryOutput.scan(listOf(compatdata))
        assertEquals(listOf("DiRT Showdown"), games.map { it.name })
        assertEquals("2848447563", games[0].prefixId)
        assertFalse(games[0].on)

        assertTrue(TelemetryOutput.set(games[0], true))
        assertTrue(File(config.path + TelemetryOutput.ORIGINAL_SUFFIX).isFile)
        assertTrue(TelemetryOutput.scan(listOf(compatdata))[0].on)

        assertTrue(TelemetryOutput.set(games[0], false))
        assertEquals(showdown, config.readText())
        assertFalse(File(config.path + TelemetryOutput.ORIGINAL_SUFFIX).exists())
        assertFalse(TelemetryOutput.scan(listOf(compatdata))[0].on)
    }

    @Test fun theRunningGameIsMatchedByItsPrefixId() {
        assertTrue(TelemetryOutput.isRunning("2848447563", "2848447563"))
        assertTrue(TelemetryOutput.isRunning("-1446519733", "2848447563"))
        assertFalse(TelemetryOutput.isRunning("2848447563", "690790"))
        assertFalse(TelemetryOutput.isRunning("2848447563", null))
        assertTrue(TelemetryOutput.isRunning("2848447563", "program:/games/x.exe"))
    }

    @Test fun automaticSkipsGamesThatAreOnSwitchedOffOrRunning() {
        val f = File("x")
        val dirt4 = TelemetryOutput.Game("DiRT 4", "421020", f, on = false)
        val rally = TelemetryOutput.Game("DiRT Rally 2.0", "690790", f, on = false)
        val grid = TelemetryOutput.Game("GRID", "703860", f, on = true)
        val games = listOf(dirt4, rally, grid)
        assertEquals(listOf(dirt4, rally), TelemetryOutput.automaticTargets(games, emptySet(), null))
        assertEquals(listOf(rally), TelemetryOutput.automaticTargets(games, setOf("421020/DiRT 4"), null))
        assertEquals(listOf(dirt4), TelemetryOutput.automaticTargets(games, emptySet(), "690790"))
        assertEquals("690790/DiRT Rally 2.0", TelemetryOutput.key(rally))
    }

    @Test fun switchingAutomaticOffRestoresOnlyGamesDroidDeckChanged() {
        val compatdata = tmp.newFolder("steamapps", "compatdata")
        val changed = configFile(compatdata, "2848447563", "DiRT Showdown").apply { writeText(showdown) }
        val own = configFile(compatdata, "690790", "DiRT Rally 2.0")
            .apply { writeText("<x><udp enabled=\"true\" ip=\"127.0.0.1\" port=\"20777\" /></x>") }
        assertTrue(TelemetryOutput.set(TelemetryOutput.scan(listOf(compatdata)).first { it.name == "DiRT Showdown" }, true))
        val targets = TelemetryOutput.restoreTargets(TelemetryOutput.scan(listOf(compatdata)), null)
        assertEquals(listOf(changed), targets.map { it.file })
        assertTrue(own.isFile)
        assertTrue(TelemetryOutput.restoreTargets(TelemetryOutput.scan(listOf(compatdata)), "2848447563").isEmpty())
    }

    @Test fun automaticLeavesTheRaceEndCrashGamesToTheUser() {
        val f = File("x")
        val showdownGame = TelemetryOutput.Game("DiRT Showdown", "2848447563", f, on = false)
        val dirt3 = TelemetryOutput.Game("DiRT3", "321040", f, on = false)
        val rally = TelemetryOutput.Game("DiRT Rally 2.0", "690790", f, on = false)
        assertTrue(showdownGame.raceEndCrash)
        assertTrue(dirt3.raceEndCrash)
        assertFalse(rally.raceEndCrash)
        assertEquals(listOf(rally), TelemetryOutput.automaticTargets(listOf(showdownGame, dirt3, rally), emptySet(), null))
    }

    @Test fun automaticPutsBackAShowdownItSwitchedOnButNotOneTheUserDid() {
        val compatdata = tmp.newFolder("steamapps", "compatdata")
        val config = configFile(compatdata, "2848447563", "DiRT Showdown").apply { writeText(showdown) }
        val game = TelemetryOutput.scan(listOf(compatdata)).single()
        assertTrue(TelemetryOutput.set(game, true))
        val on = TelemetryOutput.scan(listOf(compatdata))
        assertEquals(on, TelemetryOutput.automaticRestores(on, emptySet(), null))
        assertTrue(TelemetryOutput.automaticRestores(on, setOf("2848447563/DiRT Showdown"), null).isEmpty())
        assertTrue(TelemetryOutput.automaticRestores(on, emptySet(), "2848447563").isEmpty())
        on.forEach { TelemetryOutput.set(it, false) }
        assertEquals(showdown, config.readText())
    }

    private fun configFile(compatdata: File, id: String, game: String): File =
        File(compatdata, "$id/pfx/drive_c/users/steamuser/Documents/My Games/$game/hardwaresettings/hardware_settings_config.xml")
            .apply { parentFile!!.mkdirs() }
}
