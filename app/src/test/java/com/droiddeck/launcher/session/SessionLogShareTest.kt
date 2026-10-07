package com.droiddeck.launcher.session

import com.droiddeck.launcher.runtime.LinuxRuntime
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.io.StringWriter
import java.io.Writer
import java.nio.file.Files
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SessionLogShareTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    private val secret = "Using JWT 25484942796017334 sessionid=abcdef123456 password=secret123"
    private val leaked = listOf("25484942796017334", "abcdef123456", "secret123")

    private fun write(folder: File, path: String, text: String = "$secret\n"): File =
        File(folder, path).apply { parentFile!!.mkdirs(); writeText(text) }

    private fun zip(folder: File): Map<String, String> =
        ZipFile(SessionLogShare.zipFolder(context, folder)!!).use { z ->
            z.entries().toList().associate { it.name.substringAfter('/') to z.getInputStream(it).bufferedReader().readText() }
        }

    private fun assertSafe(files: Map<String, String>) {
        files.forEach { (name, text) -> leaked.forEach { assertFalse(name, text.contains(it)) } }
    }

    @Test fun workingLogsAndCommandLogsUsePrivateStorage() {
        assertEquals(File(context.filesDir, "logs"), SessionFiles.logDirectory(context))
        assertEquals(SessionFiles.logDirectory(context), LinuxRuntime.debugLogDir(context))
        assertTrue(File(LinuxRuntime.debugLogDir(context), SessionPaths.TOOLS_DIR).path.startsWith(context.filesDir.path + "/"))
        val dir = SessionPaths.beginOrCurrent(context, "privacy-test")
        try {
            assertTrue(dir.path.startsWith(context.filesDir.path + "/") || dir.path.startsWith(context.cacheDir.path + "/"))
        } finally {
            SessionPaths.take()
            SessionPaths.release(context, dir)
            dir.deleteRecursively()
        }
    }

    @Test fun forgedAndStaleScrubRecordsNeverBypassRedaction() {
        val folder = tmp.newFolder("2026-10-06-01-steam")
        val file = write(folder, "session.log")
        write(folder, ".scrubbed-tree", "rules 1\n${file.length()}\t${file.lastModified()}\tsession.log\n")
        write(folder, ".complete", "done")
        assertSafe(zip(folder))
        val modified = file.lastModified()
        file.writeText("$secret\n")
        file.setLastModified(modified)
        assertSafe(zip(folder))
        assertEquals(setOf("session.log"), zip(folder).keys)
    }

    @Test fun allowlistIncludesDiagnosticsAndExcludesCredentialStoresAndUnknownFiles() {
        val folder = tmp.newFolder("2026-10-06-02-steam")
        val allowed = listOf("session.log", "app.log", "crash-app.txt", "events.jsonl",
            "steam/console_log.txt", "droiddeck-esync/launches.log", "droiddeck-fex/status.json")
        val excluded = listOf(".hidden.txt", ".session.log.scrub", "steam/deep/nested.txt",
            "steam/loginusers.vdf", "steam/config.vdf", "ssfn123", "arbitrary.txt",
            "steam/token.txt", "droiddeck-fex/config/secrets.json")
        (allowed + excluded).forEach { write(folder, it) }
        val files = zip(folder)
        assertEquals(allowed.toSet(), files.keys)
        assertSafe(files)
    }

    @Test fun oversizedLiveAndFinishedLogsAreOmittedWithoutRawFallback() {
        for (finished in listOf(false, true)) for (size in listOf(SessionLogFiles.MAX_BYTES, SessionLogFiles.MAX_BYTES + 1)) {
            val folder = tmp.newFolder("2026-10-06-${if (finished) "03" else "04"}-steam-$size")
            write(folder, "app.log")
            val large = write(folder, "session.log")
            RandomAccessFile(large, "rw").use { it.setLength(size) }
            if (finished) write(folder, ".complete", "done")
            val files = zip(folder)
            assertFalse(files.containsKey("session.log"))
            assertTrue(files.getValue("omitted.txt").contains("session.log"))
            assertSafe(files)
        }
    }

    @Test fun binaryDataPastTheProbeMalformedUtf8AndLongLinesAreOmitted() {
        val folder = tmp.newFolder("2026-10-06-05-steam")
        write(folder, "session.log", "safe\n".repeat(2000) + "$secret\n\u0000")
        File(folder, "app.log").writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        write(folder, "crash.log", "x".repeat(65537) + secret)
        val files = zip(folder)
        assertEquals(setOf("omitted.txt"), files.keys)
        assertSafe(files)
    }

    @Test fun symlinkFilesAndDirectoriesCannotExportOutsideData() {
        val folder = tmp.newFolder("2026-10-06-06-steam")
        val outside = tmp.newFolder("outside")
        val target = write(outside, "console_log.txt", "outside data\n")
        Files.createSymbolicLink(File(folder, "session.log").toPath(), target.toPath())
        Files.createSymbolicLink(File(folder, "steam").toPath(), outside.toPath())
        val files = zip(folder)
        assertEquals(setOf("omitted.txt"), files.keys)
        assertTrue(files.values.none { it.contains("outside data") })
        val alias = File(tmp.root, "2026-10-06-07-steam")
        Files.createSymbolicLink(alias.toPath(), folder.toPath())
        assertFalse(SessionPaths.isSessionFolder(alias))
        assertNull(SessionLogShare.zipFolder(context, alias))
    }

    @Test fun failedTeardownScrubbingStillRedactsExports() {
        val folder = tmp.newFolder("2026-10-06-08-steam")
        val file = write(folder, "session.log")
        folder.setWritable(false)
        try {
            SessionArtifacts.scrubTree(folder)
        } finally {
            folder.setWritable(true)
        }
        assertTrue(file.readText().contains(leaked.first()))
        assertSafe(zip(folder))
    }

    @Test fun unreadableAllowedFilesAreOmitted() {
        val folder = tmp.newFolder("2026-10-06-09-steam")
        val file = write(folder, "session.log")
        file.setReadable(false)
        try {
            val files = zip(folder)
            assertEquals(setOf("omitted.txt"), files.keys)
            assertSafe(files)
        } finally {
            file.setReadable(true)
        }
    }

    @Test fun successfulTeardownScrubsNestedDiagnosticsWithoutRecords() {
        val folder = tmp.newFolder("2026-10-06-10-steam")
        val paths = listOf("session.log", "crash-app.txt", "droiddeck-esync/launches.log", "steam/console_log.txt")
        paths.forEach { write(folder, it) }
        SessionArtifacts.scrubTree(folder)
        paths.forEach { path -> leaked.forEach { assertFalse(File(folder, path).readText().contains(it)) } }
        assertSafe(zip(folder))
        assertTrue(folder.walkTopDown().none { it.name.startsWith(".redacted-") })
    }

    @Test fun steamLogsAtTheSizeLimitStayOut() {
        val folder = tmp.newFolder("2026-10-06-11-steam")
        write(folder, "session.log")
        val file = write(folder, "steam/console_log.txt")
        RandomAccessFile(file, "rw").use { it.setLength(SessionLogFiles.STEAM_MAX_BYTES) }
        val files = zip(folder)
        assertFalse(files.containsKey("steam/console_log.txt"))
        assertTrue(files.containsKey("omitted.txt"))
        assertSafe(files)
    }

    @Test fun aGrowingFileIsLimitedToItsOpeningSnapshot() {
        val folder = tmp.newFolder("2026-10-06-12-steam")
        val file = write(folder, "session.log", "safe\n".repeat(2000) + "$secret\nunfinished")
        val output = StringWriter()
        var appended = false
        val writer = object : Writer() {
            override fun write(chars: CharArray, offset: Int, length: Int) {
                if (!appended) { appended = true; file.appendText("appended-marker $secret\n") }
                output.write(chars, offset, length)
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        SessionLogFiles.scrubTo(folder, file, writer)
        assertFalse(output.toString().contains("appended-marker"))
        assertFalse(output.toString().contains("unfinished"))
        assertTrue(output.toString().contains("<incomplete live line withheld>"))
        leaked.forEach { assertFalse(output.toString().contains(it)) }
    }

    @Test fun aTruncatedFileFailsInsteadOfExportingAPartialSnapshot() {
        val folder = tmp.newFolder("2026-10-06-13-steam")
        val file = write(folder, "session.log", "safe\n".repeat(20000))
        val writer = object : Writer() {
            override fun write(chars: CharArray, offset: Int, length: Int) { file.writeText("") }
            override fun flush() = Unit
            override fun close() = Unit
        }
        assertThrows(java.io.IOException::class.java) { SessionLogFiles.scrubTo(folder, file, writer) }
    }

    @Test fun anOutputFailureDoesNotLeaveAShareableArchive() {
        val folder = tmp.newFolder("2026-10-06-14-steam")
        write(folder, "session.log")
        assertThrows(IllegalStateException::class.java) {
            SessionLogShare.zipFolder(context, folder) { throw IllegalStateException("failed output") }
        }
        val out = File(context.cacheDir, "shared-logs")
        assertTrue(out.listFiles().orEmpty().isEmpty())
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("redacted-") })
    }
}
