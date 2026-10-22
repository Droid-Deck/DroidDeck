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

    private fun record(folder: File, vararg files: String, rules: Int = LogRedactor.RULES_VERSION) {
        File(folder, SessionArtifacts.SCRUBBED_TREE_MARKER).writeText(
            "rules $rules\n" + files.joinToString("") { val f = File(folder, it); "${f.length()}\t${f.lastModified()}\t$it\n" })
    }

    @Test fun aLogPastTheInMemoryCapIsStillScrubbedIntoTheZip() {
        val folder = tmp.newFolder("2026-10-18-01-steam")
        val line = ("x".repeat(1023) + "\n").toByteArray()
        File(folder, "session.log").outputStream().buffered().use { out ->
            out.write("$secret\n".toByteArray())
            repeat(65 * 1024) { out.write(line) }
        }
        assertTrue(File(folder, "session.log").length() >= LogRedactor.IN_MEMORY_SCRUB_MAX)
        val out = zip(folder)
        assertTrue(out.containsKey("session.log"))
        assertFalse(out.getValue("session.log").contains(leaked))
    }

    @Test fun aLogWithANulByteIsScrubbedNotCopiedRaw() {
        val folder = tmp.newFolder("2026-10-18-02-steam")
        File(folder, "crash.log").writeBytes(byteArrayOf(0) + "$secret\n".toByteArray())
        val out = zip(folder)
        assertTrue(out.containsKey("crash.log"))
        assertFalse(out.getValue("crash.log").contains(leaked))
    }

    @Test fun utf16SecretsDoNotEscapeInTheSharedZip() {
        val folder = tmp.newFolder("2026-10-18-03-steam")
        File(folder, "crash.log").writeBytes("sessionid=$leaked".toByteArray(Charsets.UTF_16LE))
        val output = zip(folder).getValue("crash.log")
        assertFalse(output.replace("\u0000", "").contains(leaked))
        assertTrue(output.contains("withheld"))
    }

    @Test fun aRecordedUnchangedFileIsScrubbedAgainOnTheWayIntoTheZip() {
        val folder = tmp.newFolder("2026-10-06-01-steam")
        File(folder, "session.log").writeText("$secret\n")
        record(folder, "session.log")
        assertFalse(zip(folder).getValue("session.log").contains(leaked))
    }

    @Test fun anythingTheRecordDoesNotVouchForIsScrubbed() {
        val folder = tmp.newFolder("2026-10-06-02-steam")
        File(folder, "app.log").writeText("ok\n")
        File(folder, "session.log").writeText("ok\n")
        File(folder, "steam/deep").mkdirs()
        File(folder, "steam/deep/nested.txt").writeText("$secret\n")
        File(folder, ".hidden.txt").writeText("$secret\n")
        record(folder, "app.log", "session.log")
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

    @Test fun olderRuleRecordReScrubsDeviceIdentifiers() {
        val folder = tmp.newFolder("2026-10-07-01-steam")
        File(folder, "network.txt").writeText("mac 02:ab:cd:ef:12:34\n")
        File(folder, "steam").mkdirs()
        File(folder, "steam/controller_support.txt").writeText("Serial number: controller-123456\n")
        record(folder, "network.txt", "steam/controller_support.txt", rules = 1)

        val out = zip(folder)
        assertEquals("mac <redacted:mac>\n", out.getValue("network.txt"))
        assertEquals("Serial number: <redacted:serial>\n", out.getValue("steam/controller_support.txt"))
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
        org.junit.Assume.assumeFalse(System.getProperty("os.name").startsWith("Windows"))
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
