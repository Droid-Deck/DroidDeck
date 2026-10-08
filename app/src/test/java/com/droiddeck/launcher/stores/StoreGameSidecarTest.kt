package com.droiddeck.launcher.stores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Under Robolectric for a real org.json; the plain unit-test android.jar stubs it out. */
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
        // An older sidecar's Steam switch is read past, not acted on.
        assertEquals("bin/game.exe", back.exe)
        assertNull(back.launcher)
        assertTrue(back.args.isEmpty())
    }

    @Test fun refusesWhatIsNotASidecar() {
        assertNull(StoreGameSidecar.parse("not json"))
        assertNull(StoreGameSidecar.parse("""{"store":"steam","id":"1","title":"T","exe":"a.exe"}"""))
        assertNull(StoreGameSidecar.parse("""{"store":"gog","id":"","title":"T","exe":"a.exe"}"""))
        // An exe outside the folder is not trusted: a sidecar must not point the shortcut anywhere.
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
        assertTrue(text.contains("\"%~dp0Binaries\\Win64\\Celeste.exe\" -EpicPortal -epicusername=\"Some One\" %DD_AUTH%\r\n"))
        // Epic reads the one-shot exchange code the app leaves beside the launcher, then deletes it.
        assertTrue(text.contains("set /p DD_CODE=<\"%~dp0.droiddeck-epic-code\""))
        assertTrue(text.contains("-AUTH_TYPE=exchangecode"))
    }

    @Test fun gogLauncherHasNoEpicBlockAndIsOnlyWrittenWhenNeeded() {
        val plain = StoreGameSidecar(Store.GOG, "1", "T", "game.exe")
        val text = StoreLaunch.launcherText(plain.copy(args = listOf("-windowed")))
        assertFalse(text.contains("exchangecode"))
        assertTrue(text.contains("\"%~dp0game.exe\" -windowed %DD_AUTH%"))
        assertEquals("\"two words\"", StoreLaunch.quoteArg("two words"))
        assertEquals("-x=\"a b\"", StoreLaunch.quoteArg("-x=\"a b\""))
    }
}
