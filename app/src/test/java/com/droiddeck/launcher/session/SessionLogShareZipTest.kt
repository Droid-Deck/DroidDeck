package com.droiddeck.launcher.session

import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A shared zip is scrubbed on the way in, whatever the files on disk still hold. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionLogShareZipTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun everyTextFileInTheZipIsScrubbedAndTheStoresAndToolsLogsComeAlong() {
        val logs = LinuxRuntime.logDir(app)
        val session = File(logs, "2026-10-08-01-steam").apply { mkdirs() }
        // Written raw, as a file from before a rule existed would be.
        File(session, "session.log").writeText(
            "fetch https://gog-cdn-fastly.gog.com/token=nva=1791500000~dirs=/x~token=0a1b2c3d4e5f/content-system/v2/store/1/ab/cd/f\n" +
                "fetch https://epicgames-download1.akamaized.net/Builds/Org/a/ChunksV4/12/ABCD.chunk?__token__=exp=1~hmac=deadbeefcafe\n" +
                "login refresh_token=abcdef123456 user someone@example.com code=AUTHCODE123\n" +
                "Authorization: Bearer abc.def.ghi\n" +
                "kept: EResult 5, app 1086940, steamapps/common/Game\n"
        )
        File(logs, "stores").apply { mkdirs() }.resolve("stores-2026-10-08.log").writeText("epic: f_token=zzzzzzzz12 window 32\n")
        File(logs, SessionPaths.TOOLS_DIR).apply { mkdirs() }.resolve("flatpak-install.log").writeText("== install\nCookie: steamLoginSecure=76561198000000000%7C%7Ceyabc\n")

        val zip = SessionLogShare.zipFolder(app, session)!!
        assertTrue(zip.path.startsWith(File(app.cacheDir, "share").path))
        val text = ZipFile(zip).use { z -> z.entries().toList().associate { e -> e.name to z.getInputStream(e).bufferedReader().readText() } }
        assertTrue(text.keys.containsAll(listOf("2026-10-08-01-steam/session.log", "stores/stores-2026-10-08.log", "tools/flatpak-install.log")))
        val all = text.values.joinToString("\n")
        for (secret in listOf("0a1b2c3d4e5f", "deadbeefcafe", "abcdef123456", "someone@example.com", "AUTHCODE123", "abc.def.ghi", "zzzzzzzz12", "eyabc")) {
            assertFalse("$secret survived", all.contains(secret))
        }
        // What makes a log worth reading stays.
        assertTrue(all.contains("kept: EResult 5, app 1086940, steamapps/common/Game"))
        assertTrue(all.contains("https://gog-cdn-fastly.gog.com/"))

        SessionLogShare.clear(app)
        assertFalse(zip.exists())
    }

    @Test fun theSharePassChangesNothingInACleanLine() {
        val line = "dxvk: Device 0x5 (Adreno 750) https://github.com/doitsujin/dxvk/releases code 403"
        assertEquals(line, com.droiddeck.launcher.core.LogRedactor.redactForShare(line))
    }

    // A folder an earlier build scrubbed and marked under older rules, its copied Steam UI log
    // still naming the account: the pass at app start (and after the move out of Download/)
    // scrubs it again on disk.
    private fun olderFolder(name: String): File {
        val dir = File(LinuxRuntime.logDir(app), name).apply { mkdirs() }
        File(dir, ".scrubbed-2").writeText("scrubbed earlier\n")
        File(dir, "steam").apply { mkdirs() }.resolve("webhelper_js.txt").writeText(
            "[2026-10-01 20:00:00] SteamUI: INFO: Login: OnLoginStateChange someone.masked@example.com 2 1 0 0\n" +
                "[2026-10-01 20:00:01] SteamUI: INFO: Login: OnLoginStateChange maskeduser42 2 1 0 0\n" +
                "[2026-10-01 20:00:02] SteamUI: INFO: Login: OnLoginStateChange  0 1 0 0\n"
        )
        return dir
    }

    private fun assertScrubbed(dir: File) {
        val text = File(dir, "steam/webhelper_js.txt").readText()
        for (secret in listOf("someone.masked", "example.com", "maskeduser42")) assertFalse("$secret survived", text.contains(secret))
        assertTrue(text.contains("Login: OnLoginStateChange <redacted:account> 2 1 0 0"))
        assertTrue(text.contains("Login: OnLoginStateChange  0 1 0 0"))
        assertFalse(File(dir, ".scrubbed-2").exists())
        assertTrue(File(dir, ".scrubbed-r${com.droiddeck.launcher.core.LogRedactor.RULES_VERSION}").exists())
    }

    @Test fun aFolderMarkedUnderOlderRulesIsScrubbedAgainAtStart() {
        val dir = olderFolder("2026-10-01-01-steam")
        SessionArtifacts.scrubOlder(app)
        assertScrubbed(dir)
    }

    @Test fun aMovedFolderIsScrubbedWhateverItsMarkersSay() {
        val dir = olderFolder("2026-10-01-02-steam")
        File(dir, ".scrubbed-r${com.droiddeck.launcher.core.LogRedactor.RULES_VERSION}").writeText("claimed\n")
        SessionArtifacts.scrubMoved(app, listOf(dir))
        assertScrubbed(dir)
    }
}
