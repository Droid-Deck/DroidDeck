package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.LogRedactor
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
    private val leaked = "abcdef123456"

    private fun zip(folder: File): Map<String, String> =
        ZipFile(SessionLogShare.zipFolder(RuntimeEnvironment.getApplication(), folder)!!).use { z ->
            z.entries().toList().associate { it.name.substringAfter('/') to z.getInputStream(it).bufferedReader().readText() }
        }

    /** A record as the session's ending writes it, listing [files] as they stand now. */
    private fun record(folder: File, vararg files: String, rules: Int = LogRedactor.RULES_VERSION) {
        File(folder, SessionArtifacts.SCRUBBED_TREE_MARKER).writeText(
            "rules $rules\n" + files.joinToString("") { val f = File(folder, it); "${f.length()}\t${f.lastModified()}\t$it\n" })
    }

    @Test fun aRecordedUnchangedFileGoesInAsItIs() {
        val folder = tmp.newFolder("2026-10-06-01-steam")
        // Stands in for a file the ending scrubbed; the share must not pass it through again.
        File(folder, "session.log").writeText("$secret\n")
        record(folder, "session.log")
        assertTrue(zip(folder).getValue("session.log").contains(leaked))
    }

    @Test fun anythingTheRecordDoesNotVouchForIsScrubbed() {
        val folder = tmp.newFolder("2026-10-06-02-steam")
        File(folder, "app.log").writeText("ok\n")
        File(folder, "session.log").writeText("ok\n")
        File(folder, "steam/deep").mkdirs()
        File(folder, "steam/deep/nested.txt").writeText("$secret\n")
        File(folder, ".hidden.txt").writeText("$secret\n")
        record(folder, "app.log", "session.log")
        // Appended to after the ending (the app's log capture), and touched with an older time.
        File(folder, "app.log").appendText("$secret\n")
        File(folder, "session.log").apply { writeText("$secret\n".padEnd(3, ' ')); setLastModified(1_000L) }
        File(folder, "events.jsonl").writeText("$secret\n")

        val out = zip(folder)
        for (name in listOf("app.log", "session.log", "steam/deep/nested.txt", ".hidden.txt", "events.jsonl"))
            assertFalse(name, out.getValue(name).contains(leaked))
    }

    @Test fun aRecordUnderOtherRedactorRulesVouchesForNothing() {
        val folder = tmp.newFolder("2026-10-06-03-steam")
        File(folder, "session.log").writeText("$secret\n")
        record(folder, "session.log", rules = LogRedactor.RULES_VERSION - 1)
        assertFalse(zip(folder).getValue("session.log").contains(leaked))
    }

    @Test fun withoutARecordEveryFileIsScrubbed() {
        val folder = tmp.newFolder("2026-10-06-04-steam")
        File(folder, "session.log").writeText("$secret\n")
        File(folder, "steam").mkdirs()
        File(folder, "steam/console_log.txt").writeText("$secret\n")
        val out = zip(folder)
        assertFalse(out.getValue("session.log").contains(leaked))
        assertFalse(out.getValue("steam/console_log.txt").contains(leaked))
    }

    @Test fun theEndingScrubsEveryFolderAndRecordsWhatItScrubbed() {
        val folder = tmp.newFolder("2026-10-06-05-steam")
        File(folder, "session.log").writeText("$secret\n")
        File(folder, "droiddeck-esync").mkdirs()
        File(folder, "droiddeck-esync/launches.log").writeText("$secret\n")
        File(folder, "steam/deep").mkdirs()
        File(folder, "steam/deep/nested.txt").writeText("$secret\n")

        SessionArtifacts.scrubAndMark(folder)

        for (name in listOf("session.log", "droiddeck-esync/launches.log", "steam/deep/nested.txt"))
            assertFalse(name, File(folder, name).readText().contains(leaked))
        assertEquals(setOf("session.log", "droiddeck-esync/launches.log", "steam/deep/nested.txt"),
            SessionArtifacts.scrubbedFiles(folder))
        assertTrue(zip(folder).values.none { it.contains(leaked) })
    }

    @Test fun aFileThatCouldNotBeScrubbedLeavesTheFolderUnrecorded() {
        val folder = tmp.newFolder("2026-10-06-06-steam")
        File(folder, "session.log").writeText("$secret\n")
        // A folder the scrubbed copy cannot be written into: the scrub fails, the original stays.
        folder.setWritable(false)
        try {
            SessionArtifacts.scrubAndMark(folder)
        } finally {
            folder.setWritable(true)
        }
        assertTrue(File(folder, "session.log").readText().contains(leaked))
        assertFalse(File(folder, SessionArtifacts.SCRUBBED_TREE_MARKER).exists())
        assertTrue(SessionArtifacts.scrubbedFiles(folder).isEmpty())
        assertTrue(zip(folder).values.none { it.contains(leaked) })
    }

    @Test fun steamLogsPastTheSizeLimitStayOut() {
        val folder = tmp.newFolder("2026-10-06-07-steam")
        File(folder, "session.log").writeText("ok\n")
        File(folder, "steam").mkdirs()
        File(folder, "steam/console_log.txt").writeText("ok\n")
        File(folder, "steam/cef_log.previous.txt").outputStream().use { out ->
            val line = ByteArray(1024) { 'x'.code.toByte() }.also { it[it.size - 1] = '\n'.code.toByte() }
            repeat(9 * 1024) { out.write(line) }
        }
        val names = zip(folder).keys
        assertTrue("steam/console_log.txt" in names)
        assertFalse("steam/cef_log.previous.txt" in names)
    }
}
