package com.droiddeck.launcher.stores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StoreGameSidecarTest {
    private val sample = StoreGameSidecar(
        Store.EPIC, "abc123", "Celeste", "Celeste.exe", launcher = ".droiddeck-launch.bat",
        args = listOf("-EpicPortal", "-epicusername=\"Some One\""), env = mapOf("FOO" to "bar"),
        installVersion = "1.4.0", installedAt = 1700000000L, cover = "https://x/c.jpg",
        extra = mapOf("namespace" to "ns"),
    )

    @Test fun roundTripsThroughJson() {
        val back = StoreGameSidecar.parse(sample.toJson().toString())!!
        assertEquals(Store.EPIC, back.store)
        assertEquals("abc123", back.id)
        assertEquals("Celeste", back.title)
        assertEquals("Celeste.exe", back.exe)
        assertEquals(".droiddeck-launch.bat", back.launcher)
        assertEquals(sample.args, back.args)
        assertEquals(sample.env, back.env)
        assertEquals("1.4.0", back.installVersion)
        assertEquals(1700000000L, back.installedAt)
        assertEquals("https://x/c.jpg", back.cover)
        assertEquals("ns", back.extra["namespace"])
    }

    @Test fun defaultsWhenFieldsAreAbsent() {
        val back = StoreGameSidecar.parse("""{"store":"gog","id":"1","title":"T","exe":"bin\\game.exe","addToSteam":false}""")!!
        assertEquals("bin/game.exe", back.exe)
        assertNull(back.launcher)
        assertTrue(back.args.isEmpty())
        assertTrue(back.isInstalled)
    }

    @Test fun epicLaunchChoicesRoundTripAndDefaultOn() {
        val plain = StoreGameSidecar.parse(StoreGameSidecar(Store.EPIC, "m", "Metalstorm", "M.exe").toJson().toString())!!
        assertEquals(EpicOptions(eos = true, offline = false), plain.epic)
        assertTrue(plain.epic.wantsCode)
        val set = StoreGameSidecar(Store.EPIC, "m", "Metalstorm", "M.exe", epic = EpicOptions(eos = true, offline = true))
        val back = StoreGameSidecar.parse(set.toJson().toString())!!
        assertEquals(EpicOptions(eos = true, offline = true), back.epic)
        assertFalse(back.epic.wantsCode)
        assertEquals(EpicOptions(), StoreGameSidecar.parse("""{"store":"epic","id":"m","title":"M","exe":"M.exe"}""")!!.epic)
        assertEquals(EpicOptions(), StoreGameSidecar.parse("""{"store":"epic","id":"m","title":"M","exe":"M.exe","epic":{"v":2,"eos":true,"offline":false,"overlay":true}}""")!!.epic)
    }

    @Test fun aSwitchOnTheEpicCardChangesTheSidecarOnDisk() {
        val folder = java.io.File(org.robolectric.RuntimeEnvironment.getApplication().filesDir, "Games/Stores/Epic/Metalstorm").apply { mkdirs() }
        StoreGameSidecar(Store.EPIC, "m", "Metalstorm", "Metalstorm.exe", launcher = ".droiddeck-launch.bat").write(folder)
        val written = StoreGameSidecar.updateEpic(folder) { it.copy(offline = true) }!!
        assertTrue(written.epic.offline)
        val onDisk = org.json.JSONObject(java.io.File(folder, StoreGameSidecar.FILE_NAME).readText())
        assertTrue(onDisk.getJSONObject("epic").getBoolean("offline"))
        assertTrue(onDisk.getJSONObject("epic").getBoolean("eos"))
        assertFalse(onDisk.getJSONObject("epic").has("overlay"))
        assertEquals(".droiddeck-launch.bat", StoreGameSidecar.read(folder)!!.launcher)
        assertEquals(null, StoreGameSidecar.updateEpic(java.io.File(folder, "missing")) { it })
    }

    @Test fun gogAchievementsAreIndependentOfCloudAndSurviveEdits() {
        val folder = java.io.File(org.robolectric.RuntimeEnvironment.getApplication().filesDir, "gog-achievements").apply { mkdirs() }
        StoreGameSidecar(Store.GOG, "1", "Game", "game.exe", cloud = false).write(folder)
        assertTrue(StoreGameSidecar.read(folder)!!.achievements)
        StoreGameSidecar.updateAchievements(folder, false)
        StoreGameSidecar.updateCloud(folder, true)
        assertFalse(StoreGameSidecar.read(folder)!!.achievements)
        assertTrue(StoreGameSidecar.read(folder)!!.cloud)
    }

    @Test fun anInstallUnderWayHasNoExeYetAndIsNotInstalled() {
        val started = StoreGameSidecar(Store.EPIC, "Metalstorm", "Metalstorm", exe = "", state = StoreGameSidecar.STATE_INSTALLING)
        val back = StoreGameSidecar.parse(started.toJson().toString())!!
        assertFalse(back.isInstalled)
        assertEquals("", back.exe)
        assertNull(StoreGameSidecar.parse("""{"store":"gog","id":"1","title":"T","exe":""}"""))
        assertTrue(StoreGameSidecar.parse(back.copy(exe = "Metalstorm.exe", state = StoreGameSidecar.STATE_INSTALLED).toJson().toString())!!.isInstalled)
    }

    @Test fun refusesWhatIsNotASidecar() {
        assertNull(StoreGameSidecar.parse("not json"))
        assertNull(StoreGameSidecar.parse("""{"store":"steam","id":"1","title":"T","exe":"a.exe"}"""))
        assertNull(StoreGameSidecar.parse("""{"store":"gog","id":"","title":"T","exe":"a.exe"}"""))
        assertNull(StoreGameSidecar.parse("""{"store":"gog","id":"1","title":"T","exe":"../other/a.exe"}"""))
        assertNull(StoreGameSidecar.parse("""{"store":"gog","id":"1","title":"T","exe":"/abs/a.exe"}"""))
        assertNull(StoreGameSidecar.parse("""{"store":"gog","id":"1","title":"T","exe":"C:\\a.exe"}"""))
    }

    @Test fun folderNamesAreSafeAndStable() {
        assertEquals("Heroes of Might and Magic 3 Complete", StoreInstallRoot.folderName("Heroes of Might and Magic 3: Complete", "1"))
        assertEquals("What Remains of Edith Finch", StoreInstallRoot.folderName("What  Remains of Edith Finch...", "1"))
        assertEquals("1207658924", StoreInstallRoot.folderName("日本語のみ", "1207658924"))
        assertTrue(StoreInstallRoot.folderName("x".repeat(200), "1").length <= 60)
    }

    @Test fun launcherStartsTheExeFromItsFolderWithArgsAndEnv() {
        val text = StoreLaunch.launcherText(sample.copy(exe = "Binaries/Win64/Celeste.exe"))
        assertTrue(text.contains("cd /d \"%~dp0Binaries\\Win64\"\r\n"))
        assertTrue(text.contains("set \"FOO=bar\"\r\n"))
        assertTrue(text.contains("\r\n\"%~dp0Binaries\\Win64\\Celeste.exe\" -EpicPortal -epicusername=\"Some One\"\r\n"))
        assertTrue(text.contains("set /p DD_X=<\"%~dp0.droiddeck-epic-code\""))
        assertTrue(text.contains("set \"DD_X=\" & \"%~dp0Binaries\\Win64\\Celeste.exe\" -EpicPortal -epicusername=\"Some One\" -AUTH_LOGIN=unused -AUTH_PASSWORD=%DD_X% -AUTH_TYPE=exchangecode\r\n"))
        assertFalse(text.contains("AUTH="))
        assertFalse(text.contains("DD_AUTH"))
    }

    @Test fun epicLauncherPrefersTheClientsArguments() {
        val text = StoreLaunch.launcherText(sample.copy(exe = "Binaries/Win64/Celeste.exe"))
        val lines = text.split("\r\n")
        val jump = lines.indexOf("if exist \"%~dp0.droiddeck-epic-args\" goto client")
        assertTrue(jump in 0 until lines.indexOf("set \"DD_X=\""))
        val client = lines.indexOf(":client")
        assertTrue(client > lines.indexOfFirst { it.contains("-AUTH_TYPE=exchangecode") })
        assertEquals("goto :eof", lines[client - 1])
        assertEquals(
            listOf(
                "set \"DD_A=\"",
                "set /p DD_A=<\"%~dp0.droiddeck-epic-args\"",
                "del \"%~dp0.droiddeck-epic-args\"",
                "if exist \"%~dp0.droiddeck-epic-code\" del \"%~dp0.droiddeck-epic-code\"",
                "set \"DD_A=\" & \"%~dp0Binaries\\Win64\\Celeste.exe\" %DD_A%",
            ),
            lines.subList(client + 1, client + 6),
        )
        assertTrue(text.endsWith("%DD_A%\r\n"))
    }

    @Test fun gogLauncherHasNoEpicBlockAndIsOnlyWrittenWhenNeeded() {
        val plain = StoreGameSidecar(Store.GOG, "1", "T", "game.exe")
        val text = StoreLaunch.launcherText(plain.copy(args = listOf("-windowed")))
        assertFalse(text.contains("exchangecode"))
        assertTrue(text.contains("\"%~dp0game.exe\" -windowed\r\n"))
        assertFalse(text.contains("DD_X"))
        assertFalse(text.contains("droiddeck-epic-args"))
        assertEquals("\"two words\"", StoreLaunch.quoteArg("two words"))
        assertEquals("-x=\"a b\"", StoreLaunch.quoteArg("-x=\"a b\""))
    }
}
