package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SessionLogShareTest {
    @get:Rule val tmp = TemporaryFolder()

    private val secret = "GET /x?sessionid=abcdef123456 200"

    private fun entries(zip: File): Map<String, String> = ZipFile(zip).use { z ->
        z.entries().toList().associate { it.name to z.getInputStream(it).bufferedReader().readText() }
    }

    @Test fun filesScrubbedAtTheSessionsEndGoInAsTheyAreAndNewerOnesAreScrubbed() {
        val folder = tmp.newFolder("2026-10-06-01-steam")
        val old = File(folder, "session.log").apply { writeText("$secret\n"); setLastModified(1_000_000L) }
        File(folder, SessionArtifacts.SCRUBBED_TREE_MARKER).apply { writeText("scrubbed\n"); setLastModified(2_000_000L) }
        File(folder, "events.jsonl").apply { writeText("$secret\n"); setLastModified(3_000_000L) }

        val zip = SessionLogShare.zipFolder(RuntimeEnvironment.getApplication(), folder)!!
        val out = entries(zip)

        // Older than the marker: taken as already scrubbed, so the bytes are copied unchanged.
        assertEquals(old.readText(), out["${folder.name}/session.log"])
        // Newer than the marker: through the redactor.
        assertFalse(out.getValue("${folder.name}/events.jsonl").contains("abcdef123456"))
    }

    @Test fun withoutTheMarkerEveryFileIsScrubbed() {
        val folder = tmp.newFolder("2026-10-06-02-steam")
        File(folder, "session.log").writeText("$secret\n")
        File(folder, "steam").mkdirs()
        File(folder, "steam/console_log.txt").writeText("$secret\n")

        val out = entries(SessionLogShare.zipFolder(RuntimeEnvironment.getApplication(), folder)!!)

        assertFalse(out.getValue("${folder.name}/session.log").contains("abcdef123456"))
        assertFalse(out.getValue("${folder.name}/steam/console_log.txt").contains("abcdef123456"))
    }

    @Test fun steamLogsPastTheSizeLimitStayOut() {
        val folder = tmp.newFolder("2026-10-06-03-steam")
        File(folder, "session.log").writeText("ok\n")
        File(folder, "steam").mkdirs()
        File(folder, "steam/console_log.txt").writeText("ok\n")
        File(folder, "steam/cef_log.previous.txt").outputStream().use { out ->
            val line = ByteArray(1024) { 'x'.code.toByte() }.also { it[it.size - 1] = '\n'.code.toByte() }
            repeat(9 * 1024) { out.write(line) }
        }

        val names = entries(SessionLogShare.zipFolder(RuntimeEnvironment.getApplication(), folder)!!).keys

        assertTrue("${folder.name}/steam/console_log.txt" in names)
        assertFalse("${folder.name}/steam/cef_log.previous.txt" in names)
    }
}
