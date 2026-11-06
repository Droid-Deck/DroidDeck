package com.droiddeck.launcher.session

import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.util.UUID
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeFalse
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionLogShareZipTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun installSecureSourceOpener() = installJvmSecureSourceOpener()

    @After fun resetSourceOpener() = resetSecureSourceOpener()

    @Test fun clearingDiagnosticOutputsNeverFollowsSymlinksOrHardLinks() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        SessionLogShare.clear(app)
        val share = SessionLogShare.shareDir(app)
        val outside = File(app.cacheDir, "outside-diagnostic-tree").apply { mkdirs() }
        val marker = File(outside, "keep.txt").apply { writeText("keep") }
        Files.createSymbolicLink(share.toPath(), outside.toPath())

        SessionLogShare.clear(app)

        assertTrue(marker.isFile)
        assertTrue(Files.isSymbolicLink(share.toPath()))
        Files.delete(share.toPath())
        share.mkdirs()
        val childLink = File(share, "linked-directory")
        Files.createSymbolicLink(childLink.toPath(), outside.toPath())
        val outsideFile = File(outside, "hard-source.txt").apply { writeText("hard") }
        Files.createLink(File(share, "hard-link.txt").toPath(), outsideFile.toPath())
        File(share, "ordinary.txt").writeText("ordinary")

        SessionLogShare.clear(app)

        assertTrue(marker.isFile)
        assertTrue(outsideFile.isFile)
        assertFalse(share.exists())
        outside.deleteRecursively()
    }

    @Test fun outputHandleFailsIfItsDirectoryPathIsReplaced() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        SessionLogShare.clear(app)
        val share = SessionLogShare.shareDir(app)
        val output = checkNotNull(SecureDiagnosticOutput.open(app, share))
        val moved = File(app.cacheDir, "moved-share")
        val outside = File(app.cacheDir, "replacement-share").apply { mkdirs() }
        try {
            assertTrue(share.renameTo(moved))
            Files.createSymbolicLink(share.toPath(), outside.toPath())
            assertThrows(IllegalArgumentException::class.java) {
                output.write("logs.zip") { it.write("redacted".toByteArray()) }
            }
            assertFalse(File(outside, "logs.zip").exists())
        } finally {
            output.close()
            runCatching { Files.deleteIfExists(share.toPath()) }
            moved.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun outputHandleRefusesASymlinkAtTheArchiveName() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        SessionLogShare.clear(app)
        val share = SessionLogShare.shareDir(app)
        val output = checkNotNull(SecureDiagnosticOutput.open(app, share))
        val outside = File(app.cacheDir, "outside-archive.zip").apply { writeText("keep") }
        val link = File(share, "logs.zip")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertThrows(Exception::class.java) {
                output.write(link.name) { it.write("redacted".toByteArray()) }
            }
            assertEquals("keep", outside.readText())
        } finally {
            output.close()
            runCatching { Files.deleteIfExists(link.toPath()) }
            share.deleteRecursively()
            outside.delete()
        }
    }

    @Test fun deletingASessionDoesNotFollowSymlinkedChildren() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val session = File(LinuxRuntime.logDir(app), "2026-11-05-99-delete-test").apply { mkdirs() }
        val outside = File(app.cacheDir, "outside-session-tree").apply { mkdirs() }
        val marker = File(outside, "keep.txt").apply { writeText("keep") }
        Files.createSymbolicLink(File(session, "linked-directory").toPath(), outside.toPath())
        File(session, "session.log").writeText("session")

        assertTrue(SessionLogShare.deleteSession(app, session))
        assertFalse(session.exists())
        assertTrue(marker.isFile)
        outside.deleteRecursively()
    }

    @Test fun sessionDetailsIgnoreSymlinkedFiles() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val folder = File(app.cacheDir, "details-session").apply { mkdirs() }
        val inside = File(folder, "inside.log").apply { writeText("inside") }
        val outside = File(app.cacheDir, "details-private.log").apply { writeText("private-data") }
        Files.createSymbolicLink(File(folder, "linked.log").toPath(), outside.toPath())
        try {
            val details = SessionLogShare.details(folder)
            assertEquals(inside.length(), details.sizeBytes)
        } finally {
            folder.deleteRecursively()
            outside.delete()
        }
    }

    @Test fun startupRemovesAbandonedLegacyStagesWithoutTouchingAnActiveOne() {
        val abandoned = File(app.cacheDir, "share-${UUID.randomUUID()}.diagnostic-stage").apply { mkdirs() }
        File(abandoned, "raw.log").writeText("private raw diagnostic\n")
        val active = File(app.cacheDir, "share-${UUID.randomUUID()}.diagnostic-stage").apply { mkdirs() }
        val activeMarker = File(active, ".active").apply { writeText("") }
        val unlocked = File(app.cacheDir, "share-${UUID.randomUUID()}.diagnostic-stage").apply { mkdirs() }
        File(unlocked, ".active").writeText("")
        try {
            FileChannel.open(activeMarker.toPath(), StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    EnhancedSessionLogShare.cleanupAbandonedStages(app)
                    assertFalse(abandoned.exists())
                    assertTrue(active.isDirectory)
                    assertFalse(unlocked.exists())
                }
            }
            EnhancedSessionLogShare.cleanupAbandonedStages(app)
            assertFalse(active.exists())
        } finally {
            active.deleteRecursively()
            unlocked.deleteRecursively()
        }
    }

    @Test fun diagnosticCleanupLimitFailureDoesNotEscapeLifecycleBoundaries() {
        val throwingDirectory = object : DiagnosticOutputDirectory {
            override val file = File(app.cacheDir, "throwing-diagnostic-output")
            override fun clear() = throw IllegalArgumentException("entry limit")
            override fun child(name: String) = this
            override fun contains(name: String) = false
            override fun containsRegular(name: String) = false
            override fun write(name: String, body: (java.io.OutputStream) -> Unit) = File(file, name)
            override fun deleteIfEmpty() = false
            override fun close() = Unit
        }
        SecureDiagnosticOutput.setProviderForTest(object : DiagnosticOutputProvider {
            override fun open(context: android.content.Context, requested: File) = throwingDirectory
            override fun cleanupLegacyStages(context: android.content.Context) =
                throw IllegalArgumentException("depth limit")
            override fun <T> withExistingDirectory(
                root: File,
                block: (SecureDirectoryStream<Path>) -> T,
            ): T? = null
        })
        try {
            assertFalse(SecureDiagnosticOutput.clear(app, SessionLogShare.shareDir(app)))
            SecureDiagnosticOutput.cleanupLegacyStages(app)
        } finally {
            SecureDiagnosticOutput.setProviderForTest(JvmDiagnosticOutputProvider)
        }
    }

    @Test fun sessionDetailsTerminateAtEntryLimitButOnlySkipOverdeepTrees() {
        assertEquals(FileVisitResult.CONTINUE, SessionLogShare.detailsDirectoryDecision(16_384, 32))
        assertEquals(FileVisitResult.TERMINATE, SessionLogShare.detailsDirectoryDecision(16_385, 0))
        assertEquals(FileVisitResult.SKIP_SUBTREE, SessionLogShare.detailsDirectoryDecision(1, 33))
    }

    @Test fun legacyStageCleanupDoesNotFollowASymlinkedStage() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val outside = File(app.cacheDir, "outside-legacy-stage").apply { mkdirs() }
        val marker = File(outside, "keep.txt").apply { writeText("keep") }
        val stage = File(app.cacheDir, "share-${UUID.randomUUID()}.diagnostic-stage")
        try {
            Files.createSymbolicLink(stage.toPath(), outside.toPath())
            EnhancedSessionLogShare.cleanupAbandonedStages(app)
            assertTrue(marker.isFile)
            assertTrue(Files.isSymbolicLink(stage.toPath()))
        } finally {
            runCatching { Files.deleteIfExists(stage.toPath()) }
            outside.deleteRecursively()
        }
    }

    @Test fun anArchiveCanBeWrittenOutsideTheShareFolder() {
        SessionLogShare.clear(app)
        val session = File(LinuxRuntime.logDir(app), "2026-11-03-01-steam").apply { mkdirs() }
        val out = File(app.getExternalFilesDir(null), "agent-logs/request-1")
        val secondOut = File(app.getExternalFilesDir(null), "agent-logs/request-2")
        try {
            File(session, "session.log").writeText("booted\n")
            val zip = SessionLogShare.zipFolder(app, session, out)!!
            val secondZip = SessionLogShare.zipFolder(app, session, secondOut)!!
            assertEquals(out, zip.parentFile)
            assertFalse(File(app.cacheDir, "share").exists())
            assertTrue(zip.isFile)
            assertEquals(secondOut, secondZip.parentFile)
            assertTrue(secondZip.isFile)
        } finally {
            out.deleteRecursively()
            secondOut.deleteRecursively()
            session.deleteRecursively()
        }
    }

    @Test fun aLargeSteamLogIsRedactedInTheSharedArchive() {
        val session = File(LinuxRuntime.logDir(app), "2026-10-08-02-steam").apply { mkdirs() }
        val raw = File(session, "steam-42.log")
        val earlySecret = "early-sessionid-abcdef123456"
        val trailingSecret = "late-refresh-token-abcdef123456"
        try {
            raw.bufferedWriter().use { out ->
                out.write("sessionid=$earlySecret")
                val chunk = "x".repeat(8192)
                repeat(8192) { out.write(chunk) }
                out.write("\nrefresh_token=$trailingSecret\n")
            }
            assertTrue(raw.length() > 64L * 1024 * 1024)

            val zip = SessionLogShare.zipFolder(app, session)!!
            ZipFile(zip).use { archive ->
                val entry = archive.getEntry("${session.name}/steam-42.log")!!
                val shared = archive.getInputStream(entry).bufferedReader().readText()
                assertTrue(shared.contains("diagnostic file truncated"))
                assertFalse("early secret survived", shared.contains(earlySecret))
                assertFalse("trailing secret survived", shared.contains(trailingSecret))
                assertTrue(shared.contains("refresh_token=<redacted:token>"))
            }
        } finally {
            SessionLogShare.clear(app)
            session.deleteRecursively()
        }
    }

    @Test fun everyTextFileInTheZipIsScrubbedAndTheStoresAndToolsLogsComeAlong() {
        val logs = LinuxRuntime.logDir(app)
        val session = File(logs, "2026-10-08-01-steam").apply { mkdirs() }
        File(session, "session.log").writeText(
            "fetch https://gog-cdn-fastly.gog.com/token=nva=1791500000~dirs=/x~token=0a1b2c3d4e5f/content-system/v2/store/1/ab/cd/f\n" +
                "fetch https://epicgames-download1.akamaized.net/Builds/Org/a/ChunksV4/12/ABCD.chunk?__token__=exp=1~hmac=deadbeefcafe\n" +
                "login refresh_token=abcdef123456 user someone@example.com code=AUTHCODE123\n" +
                "Authorization: Bearer abc.def.ghi\n" +
                "kept: EResult 5, app 1086940, steamapps/common/Game\n"
        )
        val storesLog = "stores-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".log"
        File(logs, "stores").apply { mkdirs() }.resolve(storesLog).writeText("epic: f_token=zzzzzzzz12 window 32\n")
        File(logs, SessionPaths.TOOLS_DIR).apply { mkdirs() }.resolve("flatpak-install.log").writeText("== install\nCookie: steamLoginSecure=76561198000000000%7C%7Ceyabc\n")

        val zip = SessionLogShare.zipFolder(app, session)!!
        assertTrue(zip.path.startsWith(File(app.cacheDir, "share").path))
        val text = ZipFile(zip).use { z -> z.entries().toList().associate { e -> e.name to z.getInputStream(e).bufferedReader().readText() } }
        assertTrue(text.keys.containsAll(listOf("2026-10-08-01-steam/session.log", "stores/$storesLog", "tools/flatpak-install.log")))
        val all = text.values.joinToString("\n")
        for (secret in listOf("0a1b2c3d4e5f", "deadbeefcafe", "abcdef123456", "someone@example.com", "AUTHCODE123", "abc.def.ghi", "zzzzzzzz12", "eyabc")) {
            assertFalse("$secret survived", all.contains(secret))
        }
        assertTrue(all.contains("kept: EResult 5, app 1086940, steamapps/common/Game"))
        assertTrue(all.contains("https://gog-cdn-fastly.gog.com/"))

        SessionLogShare.clear(app)
        assertFalse(zip.exists())
    }

    @Test fun battleNetAndBlizzardLogsComeAlongScrubbedAndNewestFirst() {
        val session = File(LinuxRuntime.logDir(app), "2026-10-25-05-battle-net").apply { mkdirs() }
        File(session, "session.log").writeText("droiddeck-battlenet: open (from the menu)\n")
        val root = LinuxRuntime.rootDir(app)
        File(root, "root/.cache/droiddeck/battlenet").apply { mkdirs() }.apply {
            resolve("battlenet.log").writeText("launching WoW\n")
            resolve("other.log").writeText("other\n")
        }
        val drive = File(com.droiddeck.launcher.runtime.BattleNet.prefix(app), "pfx/drive_c")
        val client = File(drive, "users/steamuser/AppData/Local/Battle.net/Logs").apply { mkdirs() }
        val now = System.currentTimeMillis()
        for (i in 1..8) client.resolve("battle.net-$i.log").apply { writeText("client $i accountId=1:0:5ca1ab1e\n"); setLastModified(now - i * 60_000L) }
        client.resolve("battle.net-old.log").apply { writeText("old\n"); setLastModified(now - 30L * 86_400_000L) }
        File(drive, "ProgramData/Battle.net/Agent/Agent.9824/Logs").apply { mkdirs() }.resolve("Agent-1.log").writeText("agent someone@example.com\n")
        File(drive, "ProgramData/Battle.net/Agent/Agent.9900/Logs").apply { mkdirs() }.resolve("Agent-1.log").writeText("agent 9900\n")
        File(drive, "ProgramData/Battle.net/Setup/bna_2/Logs").apply { mkdirs() }.resolve("battle.net-setup-1.log").writeText("setup\n")

        val zip = SessionLogShare.zipFolder(app, session)!!
        val text = ZipFile(zip).use { z -> z.entries().toList().associate { e -> e.name to z.getInputStream(e).bufferedReader().readText() } }
        assertTrue(text.keys.containsAll(listOf("battlenet/droiddeck/battlenet.log", "battlenet/agent/Agent.9824/Agent-1.log", "battlenet/agent/Agent.9900/Agent-1.log", "battlenet/setup/bna_2/battle.net-setup-1.log")))
        assertFalse(text.containsKey("battlenet/droiddeck/other.log"))
        assertEquals((1..6).map { "battlenet/client/battle.net-$it.log" }.toSet(), text.keys.filter { it.startsWith("battlenet/client/") }.toSet())
        val all = text.values.joinToString("\n")
        assertFalse(all.contains("5ca1ab1e"))
        assertFalse(all.contains("someone@example.com"))
        SessionLogShare.clear(app)
    }

    @Test fun theSharePassChangesNothingInACleanLine() {
        val line = "dxvk: Device 0x5 (Adreno 750) https://github.com/doitsujin/dxvk/releases code 403"
        assertEquals(line, com.droiddeck.launcher.core.LogRedactor.redactForShare(line))
    }

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

    @Test fun everyCopiedLegacyFolderIsScrubbedUnderTheCurrentRulesInOneStart() {
        val legacy = LinuxRuntime.legacyLogDir().apply { mkdirs() }
        val names = (1..9).map { "2026-10-0${it}-01-steam" }
        names.forEachIndexed { i, name ->
            olderFolder(legacy, name, marker = listOf(".scrubbed-2", ".scrubbed-1", ".scrubbed-r${com.droiddeck.launcher.core.LogRedactor.RULES_VERSION}")[i % 3])
        }
        File(legacy, "Saves").apply { mkdirs() }.resolve("keep.txt").writeText("save\n")

        LogMigration.run(app)
        SessionArtifacts.scrubOlder(app)

        val logs = LinuxRuntime.logDir(app)
        for (name in names) assertScrubbed(File(logs, name))
        assertTrue(legacy.list()!!.toSet().containsAll(names + "Saves"))
    }

    @Test fun legacyMigrationRejectsSymlinkedFilesAndKeepsTheSource() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val legacy = LinuxRuntime.legacyLogDir().apply { mkdirs() }
        val name = "2026-10-20-01-unsafe"
        val source = File(legacy, name).apply { mkdirs() }
        val outside = File(app.cacheDir, "migration-private.txt").apply { writeText("private") }
        val nested = File(source, "nested").apply { mkdirs() }
        Files.createSymbolicLink(File(nested, "linked.log").toPath(), outside.toPath())
        val destination = File(LinuxRuntime.logDir(app), name)

        LogMigration.run(app)

        assertTrue(source.isDirectory)
        assertTrue(outside.isFile)
        assertFalse(destination.exists())
        assertFalse(File(LinuxRuntime.logDir(app), ".moved-from-downloads-v2").exists())
        runCatching { Files.deleteIfExists(File(nested, "linked.log").toPath()) }
        nested.delete()
        source.delete()
        File(LinuxRuntime.logDir(app), ".legacy-migration-pending-$name").delete()
        File(LinuxRuntime.logDir(app), ".legacy-migration-complete-$name").delete()
        outside.delete()
    }

    @Test fun completedLegacyMigrationIsNotRepeated() {
        val legacy = LinuxRuntime.legacyLogDir().apply { mkdirs() }
        val logs = LinuxRuntime.logDir(app).apply { mkdirs() }
        val marker = File(logs, ".moved-from-downloads-v2").apply { writeText("complete\n") }
        val name = "2026-10-21-01-after-marker"
        val source = File(legacy, name).apply { mkdirs() }
        File(source, "session.log").writeText("late legacy log\n")
        val destination = File(logs, name)
        try {
            LogMigration.run(app)
            assertFalse(destination.exists())
            assertTrue(source.isDirectory)
        } finally {
            source.deleteRecursively()
            marker.delete()
        }
    }

    @Test fun emptyDirectoryLeftByInterruptedMigrationIsRetriedBeforeCompletion() {
        val legacy = LinuxRuntime.legacyLogDir().apply { mkdirs() }
        val logs = LinuxRuntime.logDir(app).apply { mkdirs() }
        val marker = File(logs, ".moved-from-downloads-v2")
        marker.delete()
        val name = "2026-10-21-02-interrupted"
        val source = File(legacy, name).apply { mkdirs() }
        File(source, "session.log").writeText("retry me\n")
        val destination = File(logs, name).apply { mkdirs() }
        val pending = File(logs, ".legacy-migration-pending-$name")
        val complete = File(logs, ".legacy-migration-complete-$name")
        try {
            LogMigration.run(app)
            assertEquals("retry me\n", File(destination, "session.log").readText())
            assertTrue(complete.isFile)
            assertTrue(marker.isFile)
        } finally {
            source.deleteRecursively()
            destination.deleteRecursively()
            pending.delete()
            complete.delete()
            marker.delete()
        }
    }

    @Test fun legacyCompletionMarkerRecoversMissingFilesWithoutOverwritingPartialDestination() {
        val legacy = LinuxRuntime.legacyLogDir().apply { mkdirs() }
        val logs = LinuxRuntime.logDir(app).apply { mkdirs() }
        val oldMarker = File(logs, ".moved-from-downloads").apply { writeText("old completion\n") }
        val newMarker = File(logs, ".moved-from-downloads-v2")
        val name = "2026-10-21-04-old-partial"
        val source = File(legacy, name).apply { mkdirs() }
        File(source, "session.log").writeText("legacy value\n")
        File(source, "wayland.log").writeText("missing value\n")
        val sourceNested = File(source, "nested").apply { mkdirs() }
        val outside = File(app.cacheDir, "old-migration-private.txt").apply { writeText("private\n") }
        val sourceLink = File(sourceNested, "linked.log")
        Files.createSymbolicLink(sourceLink.toPath(), outside.toPath())
        val destination = File(logs, name).apply { mkdirs() }
        val existing = File(destination, "session.log").apply { writeText("preserve me\n") }
        val pending = File(logs, ".legacy-migration-pending-$name")
        try {
            LogMigration.run(app)
            assertEquals("preserve me\n", existing.readText())
            assertFalse(newMarker.exists())
            assertFalse(pending.exists())

            Files.delete(sourceLink.toPath())
            File(sourceNested, "game.log").writeText("recovered\n")
            LogMigration.run(app)

            assertEquals("preserve me\n", existing.readText())
            assertEquals("missing value\n", File(destination, "wayland.log").readText())
            assertEquals("recovered\n", File(destination, "nested/game.log").readText())
            assertTrue(newMarker.isFile)
        } finally {
            runCatching { Files.deleteIfExists(sourceLink.toPath()) }
            source.deleteRecursively()
            destination.deleteRecursively()
            pending.delete()
            File(logs, ".legacy-migration-complete-$name").delete()
            outside.delete()
            oldMarker.delete()
            newMarker.delete()
        }
    }

    @Test fun preexistingNonemptySessionWithoutMigrationStateIsPreserved() {
        val legacy = LinuxRuntime.legacyLogDir().apply { mkdirs() }
        val logs = LinuxRuntime.logDir(app).apply { mkdirs() }
        val marker = File(logs, ".moved-from-downloads-v2")
        marker.delete()
        val name = "2026-10-21-03-preexisting"
        val source = File(legacy, name).apply { mkdirs() }
        File(source, "session.log").writeText("legacy\n")
        val destination = File(logs, name).apply { mkdirs() }
        val existing = File(destination, "session.log").apply { writeText("current\n") }
        try {
            LogMigration.run(app)
            assertEquals("current\n", existing.readText())
            assertTrue(marker.isFile)
        } finally {
            source.deleteRecursively()
            destination.deleteRecursively()
            File(logs, ".legacy-migration-pending-$name").delete()
            File(logs, ".legacy-migration-complete-$name").delete()
            marker.delete()
        }
    }

    @Test fun traversalLimitDoesNotCreateACompletionMarker() {
        val legacy = LinuxRuntime.legacyLogDir().apply { mkdirs() }
        val marker = File(LinuxRuntime.logDir(app), ".moved-from-downloads-v2")
        marker.delete()
        val prefix = "migration-limit-${UUID.randomUUID()}-"
        val created = ArrayList<File>(16_385)
        try {
            repeat(16_385) { index ->
                created += File(legacy, "$prefix$index").apply { writeText("") }
            }
            LogMigration.run(app)
            assertFalse(marker.exists())
        } finally {
            created.forEach { it.delete() }
            marker.delete()
        }
    }

    @Test fun overdeepLegacyMigrationFailsWithoutEscapingStartupOrWritingTheMarker() {
        val legacy = LinuxRuntime.legacyLogDir().apply { mkdirs() }
        val logs = LinuxRuntime.logDir(app).apply { mkdirs() }
        val marker = File(logs, ".moved-from-downloads-v2")
        val name = "2026-10-22-01-overdeep"
        val source = File(legacy, name).apply { mkdirs() }
        var deepest = source
        repeat(34) { depth -> deepest = File(deepest, "depth-$depth").apply { mkdirs() } }
        File(deepest, "session.log").writeText("too deep\n")
        val destination = File(logs, name)
        try {
            LogMigration.run(app)
            assertTrue(source.isDirectory)
            assertFalse(marker.exists())
        } finally {
            source.deleteRecursively()
            destination.deleteRecursively()
            File(logs, ".legacy-migration-pending-$name").delete()
            File(logs, ".legacy-migration-complete-$name").delete()
            marker.delete()
        }
    }
}
