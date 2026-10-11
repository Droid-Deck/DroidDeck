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

    // Folders an earlier build scrubbed and marked under older rules, their copied Steam UI log
    // still naming the account: one app start (the move out of Download/, then the pass over
    // older folders) leaves every one scrubbed on disk under the current rules.
    private fun olderFolder(parent: File, name: String, marker: String = ".scrubbed-2"): File {
        val dir = File(parent, name).apply { mkdirs() }
        File(dir, marker).writeText("scrubbed earlier\n")
        File(dir, SessionArtifacts.SCRUBBED_TREE_MARKER).writeText("rules 2\n")
        File(dir, "steam").apply { mkdirs() }.resolve("webhelper_js.txt").writeText(
            "[2026-10-01 20:00:00] SteamUI: INFO: Login: OnLoginStateChange someone.masked@example.com 2 1 0 0\n" +
                "[2026-10-01 20:00:01] SteamUI: INFO: Login: OnLoginStateChange maskeduser42 2 1 0 0\n" +
                "[2026-10-01 20:00:02] SteamUI: INFO: Login: OnLoginStateChange  0 1 0 0\n" +
                "0024:trace:seh:dispatch_exception code=c0000005 flags=0\n"
        )
        return dir
    }

    private fun assertScrubbed(dir: File) {
        val text = File(dir, "steam/webhelper_js.txt").readText()
        for (secret in listOf("someone.masked", "example.com", "maskeduser42")) assertFalse("$secret survived in $dir", text.contains(secret))
        assertTrue(text.contains("Login: OnLoginStateChange <redacted:account> 2 1 0 0"))
        assertTrue(text.contains("Login: OnLoginStateChange  0 1 0 0"))
        assertTrue(text.contains("code=c0000005"))
        val markers = dir.list()!!.filter { it.startsWith(".scrubbed-") && it != SessionArtifacts.SCRUBBED_TREE_MARKER }
        assertEquals("$dir", listOf(".scrubbed-r${com.droiddeck.launcher.core.LogRedactor.RULES_VERSION}"), markers)
        assertTrue(File(dir, SessionArtifacts.SCRUBBED_TREE_MARKER).readText()
            .startsWith("rules ${com.droiddeck.launcher.core.LogRedactor.RULES_VERSION}\n"))
    }

    @Test fun aFolderMarkedUnderOlderRulesIsScrubbedAgainAtStart() {
        val dir = olderFolder(LinuxRuntime.logDir(app), "2026-10-01-01-steam")
        SessionArtifacts.scrubOlder(app)
        assertScrubbed(dir)
    }

    @Test fun everyMigratedFolderIsScrubbedUnderTheCurrentRulesInOneStart() {
        val legacy = LinuxRuntime.legacyLogDir().apply { mkdirs() }
        val names = (1..9).map { "2026-10-0${it}-01-steam" }
        names.forEachIndexed { i, name ->
            // Old markers of every kind an earlier build left, one even claiming the current rules.
            olderFolder(legacy, name, marker = listOf(".scrubbed-2", ".scrubbed-1", ".scrubbed-r${com.droiddeck.launcher.core.LogRedactor.RULES_VERSION}")[i % 3])
        }
        File(legacy, "Saves").apply { mkdirs() }.resolve("keep.txt").writeText("save\n")

        // What MainActivity runs at start.
        LogMigration.run(app)
        SessionArtifacts.scrubOlder(app)

        val logs = LinuxRuntime.logDir(app)
        for (name in names) assertScrubbed(File(logs, name))
        assertEquals(listOf("Saves"), legacy.list()!!.toList())
    }
}
