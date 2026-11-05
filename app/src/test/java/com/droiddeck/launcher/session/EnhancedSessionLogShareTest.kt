package com.droiddeck.launcher.session

import android.content.Intent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import org.junit.After
import org.junit.Before

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EnhancedSessionLogShareTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun installSecureSourceOpener() = installJvmSecureSourceOpener()

    @After fun resetSourceOpener() = resetSecureSourceOpener()

    private class CountingChannel(
        private val delegate: SeekableByteChannel,
        private val reads: AtomicInteger,
    ) : SeekableByteChannel by delegate {
        override fun read(dst: ByteBuffer): Int {
            reads.incrementAndGet()
            return delegate.read(dst)
        }
    }

    private fun installCountingSourceOpener(reads: AtomicInteger) {
        EnhancedSessionLogShare.setSourceOpenerForTest(object : SecureSourceOpener {
            override fun <T> withChannel(
                root: java.nio.file.Path,
                relative: java.nio.file.Path,
                block: (SeekableByteChannel) -> T,
            ): T? {
                val source = root.resolve(relative).normalize()
                if (!source.startsWith(root) || Files.isSymbolicLink(source)) return null
                return FileChannel.open(source, StandardOpenOption.READ).use { channel ->
                    block(CountingChannel(channel, reads))
                }
            }
        })
    }

    @Test fun diagnosticsAreSplitIntoManifestedArchivesWithOneSessionIdentity() {
        val session = tmp.newFolder("2026-11-04-01-steam")
        session.resolve("session.log").writeText("startup ok\n")
        session.resolve("wayland.log").writeText("frame import ok\n")
        session.resolve("steam-42.log").writeText("dxvk: present ok\n")

        val zips = EnhancedSessionLogShare.create(app, session)

        assertEquals(
            setOf("session", "graphics", "games"),
            zips.map { it.nameWithoutExtension.substringAfterLast('-') }.toSet(),
        )
        val expected = mapOf(
            "session" to "${session.name}/session.log",
            "graphics" to "${session.name}/wayland.log",
            "games" to "${session.name}/steam-42.log",
        )
        zips.forEach { zip ->
            val category = zip.nameWithoutExtension.substringAfterLast('-')
            ZipFile(zip).use { archive ->
                assertTrue(archive.getEntry(expected.getValue(category)) != null)
                val manifest = JSONObject(archive.getInputStream(archive.getEntry("diagnostics-manifest.json"))
                    .bufferedReader().readText())
                assertEquals(1, manifest.getInt("schema"))
                assertEquals(session.name, manifest.getString("sessionId"))
                assertEquals(category, manifest.getString("category"))
                val entries = manifest.getJSONArray("entries")
                val record = (0 until entries.length()).map { entries.getJSONObject(it) }
                    .single { it.optString("entry") == expected.getValue(category) }
                assertEquals("captured", record.getString("status"))
            }
        }
        SessionLogShare.clear(app)
    }

    @Test fun oversizedFilesKeepBoundedBeginningAndTailAndDeclareTruncation() {
        val session = tmp.newFolder("2026-11-04-02-game")
        session.resolve("steam-7.log").writeText("begin\n" + "middle\n".repeat(100) + "tail\n")
        val limits = EnhancedShareLimits(maxFilesPerArchive = 8, maxBytesPerFile = 96, maxBytesTotal = 512)

        val games = EnhancedSessionLogShare.create(app, session, limits = limits)
            .single { it.name.endsWith("-games.zip") }

        ZipFile(games).use { archive ->
            val text = archive.getInputStream(archive.getEntry("${session.name}/steam-7.log")).bufferedReader().readText()
            assertTrue(text.startsWith("begin"))
            assertTrue(text.contains("diagnostic file truncated"))
            assertTrue(text.endsWith("tail\n"))
            val manifest = JSONObject(archive.getInputStream(archive.getEntry("diagnostics-manifest.json"))
                .bufferedReader().readText())
            assertEquals("truncated", manifest.getJSONArray("entries").getJSONObject(0).getString("status"))
            assertTrue(manifest.getJSONArray("entries").getJSONObject(0).getLong("capturedBytes") <= 96)
        }
        SessionLogShare.clear(app)
    }

    @Test fun symbolicLinksAreNotReadAndTheirOmissionIsRecorded() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val session = tmp.newFolder("2026-11-04-03-steam")
        session.resolve("session.log").writeText("safe\n")
        val outside = tmp.newFile("private.log").apply { writeText("do-not-share\n") }
        val link = session.toPath().resolve("wayland.log")
        runCatching { Files.createSymbolicLink(link, outside.toPath()) }.getOrElse { return }

        val graphics = EnhancedSessionLogShare.create(app, session).single { it.name.endsWith("-graphics.zip") }

        ZipFile(graphics).use { archive ->
            assertFalse(archive.entries().toList().any { it.name.endsWith("wayland.log") })
            val manifest = JSONObject(archive.getInputStream(archive.getEntry("diagnostics-manifest.json"))
                .bufferedReader().readText())
            val entries = manifest.getJSONArray("entries")
            assertTrue((0 until entries.length()).map { entries.getJSONObject(it) }
                .any { it.optString("reason") == "unsafe-or-unavailable-source" })
        }
        SessionLogShare.clear(app)
    }

    @Test fun enhancedShareUsesOneMultipleAttachmentIntent() {
        val session = tmp.newFolder("2026-11-04-04-steam")
        session.resolve("session.log").writeText("safe\n")
        val zips = EnhancedSessionLogShare.create(app, session)

        val chooser = SessionLogShare.shareEnhancedIntent(app, zips)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!

        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        assertEquals("application/zip", send.type)
        assertEquals(3, send.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!.size)
        assertEquals(3, send.clipData!!.itemCount)
        SessionLogShare.clear(app)
    }

    @Test fun graphicsArchiveIncludesOnlyBoundedFrameTraceSummariesFromTheSessionLog() {
        val session = tmp.newFolder("2026-11-04-05-steam")
        session.resolve("session.log").writeText(
            "normal startup line\n" +
                "DROIDDECK_FRAME_TRACE xdamage=4 commits=2\n" +
                "unrelated account=test-user\n" +
                "DROIDDECK_FRAME_TRACE xdamage=9 commits=8\n" +
                "DROIDDECK_PROCESS_TRACE pid=42 gpuLibs=libvulkan.so\n",
        )

        val graphics = EnhancedSessionLogShare.create(app, session).single { it.name.endsWith("-graphics.zip") }

        ZipFile(graphics).use { archive ->
            val trace = archive.getInputStream(archive.getEntry("${session.name}/graphics/frame-trace.log"))
                .bufferedReader().readText()
            assertEquals(
                "DROIDDECK_FRAME_TRACE xdamage=4 commits=2\n" +
                    "DROIDDECK_FRAME_TRACE xdamage=9 commits=8\n",
                trace,
            )
            assertFalse(trace.contains("normal startup"))
            assertFalse(trace.contains("test-user"))
            val process = archive.getInputStream(archive.getEntry("${session.name}/graphics/process-trace.log"))
                .bufferedReader().readText()
            assertEquals("DROIDDECK_PROCESS_TRACE pid=42 gpuLibs=libvulkan.so\n", process)
        }
        SessionLogShare.clear(app)
    }

    @Test fun byteOrientedTraceAndTailScansUseBufferedChannelReads() {
        val traceSession = tmp.newFolder("2026-11-04-05-buffered-trace")
        traceSession.resolve("session.log").writeText("x".repeat(1024 * 1024))
        val traceReads = AtomicInteger()
        installCountingSourceOpener(traceReads)

        EnhancedSessionLogShare.create(app, traceSession)

        assertTrue("trace scan used ${traceReads.get()} channel reads", traceReads.get() < 200)
        SessionLogShare.clear(app)

        val tailSession = tmp.newFolder("2026-11-04-05-buffered-tail")
        tailSession.resolve("steam-7.log").writeText("x".repeat(1024 * 1024))
        val tailReads = AtomicInteger()
        installCountingSourceOpener(tailReads)

        EnhancedSessionLogShare.create(
            app,
            tailSession,
            limits = EnhancedShareLimits(maxBytesPerFile = 64 * 1024L, maxBytesTotal = 512 * 1024L),
        )

        assertTrue("tail scan used ${tailReads.get()} channel reads", tailReads.get() < 100)
        SessionLogShare.clear(app)
    }

    @Test fun truncationNeverSharesASecretWhoseLabelWasCutOffAtTheBoundary() {
        val session = tmp.newFolder("2026-11-04-06-game")
        val secret = "abcdef1234567890"
        session.resolve("steam-8.log").writeText(
            "padding-refresh_token=$secret" + "x".repeat(512) + "\nvisible tail\n",
        )
        val limits = EnhancedShareLimits(maxFilesPerArchive = 8, maxBytesPerFile = 128, maxBytesTotal = 512)

        val games = EnhancedSessionLogShare.create(app, session, limits = limits)
            .single { it.name.endsWith("-games.zip") }

        val shared = ZipFile(games).use { archive ->
            archive.getInputStream(archive.getEntry("${session.name}/steam-8.log")).bufferedReader().readText()
        }
        assertFalse(shared.contains(secret))
        assertTrue(shared.contains("diagnostic file truncated"))
        assertTrue(shared.endsWith("visible tail\n"))
        SessionLogShare.clear(app)
    }

    @Test fun discoveryStopsAtItsBoundAndDeclaresTheTruncatedInventory() {
        val session = tmp.newFolder("2026-11-04-07-steam")
        repeat(20) { session.resolve("log-$it.txt").writeText("line $it\n") }
        val limits = EnhancedShareLimits(
            maxFilesPerArchive = 8,
            maxDiscoveredFiles = 2,
            maxBytesPerFile = 1024,
            maxBytesTotal = 4096,
        )

        val zips = EnhancedSessionLogShare.create(app, session, limits = limits)

        val captured = zips.sumOf { zip ->
            ZipFile(zip).use { archive -> archive.entries().toList().count { it.name != "diagnostics-manifest.json" } }
        }
        assertTrue(captured <= 2)
        zips.forEach { zip ->
            ZipFile(zip).use { archive ->
                val manifest = JSONObject(archive.getInputStream(archive.getEntry("diagnostics-manifest.json"))
                    .bufferedReader().readText())
                val entries = manifest.getJSONArray("entries")
                assertTrue((0 until entries.length()).map { entries.getJSONObject(it) }
                    .any { it.optString("origin") == "discovery" && it.optString("status") == "truncated" })
            }
        }
        SessionLogShare.clear(app)
    }

    @Test fun aGuestForgedActivationMarkerIsNeverExportedAsTrustedMetadata() {
        val session = tmp.newFolder("2026-11-04-08-steam")
        session.resolve("session.log").writeText("safe\n")
        session.resolve(EnhancedDiagnostics.MANIFEST_FILE).writeText("{\"profile\":\"forged\"}\n")

        val zips = EnhancedSessionLogShare.create(app, session)

        zips.forEach { zip ->
            ZipFile(zip).use { archive ->
                assertFalse(archive.entries().toList().any { it.name.endsWith(EnhancedDiagnostics.MANIFEST_FILE) })
            }
        }
        SessionLogShare.clear(app)
    }

    @Test fun hardLinksAreRejectedInsteadOfFollowingASecondNameToItsContent() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val session = tmp.newFolder("2026-11-04-09-steam")
        session.resolve("session.log").writeText("safe\n")
        val outside = tmp.newFile("outside-hardlink.log").apply { writeText("private hard-link content\n") }
        runCatching { Files.createLink(session.toPath().resolve("wayland.log"), outside.toPath()) }.getOrElse { return }

        val graphics = EnhancedSessionLogShare.create(app, session).single { it.name.endsWith("-graphics.zip") }

        ZipFile(graphics).use { archive ->
            assertFalse(archive.entries().toList().any { it.name.endsWith("wayland.log") })
            val all = archive.entries().toList().joinToString("\n") { entry ->
                archive.getInputStream(entry).bufferedReader().readText()
            }
            assertFalse(all.contains("private hard-link content"))
        }
        SessionLogShare.clear(app)
    }

    @Test fun guestControlledAndControlCharacterNamesNeverEnterTheArchiveOrManifest() {
        val session = tmp.newFolder("2026-11-04-10-steam")
        session.resolve("private-window-title.log").writeText("safe diagnostic\n")
        session.resolve("wayland\nsecret.log").writeText("must not be named\n")

        val sessionZip = EnhancedSessionLogShare.create(app, session).single { it.name.endsWith("-session.zip") }

        ZipFile(sessionZip).use { archive ->
            val names = archive.entries().toList().map { it.name }
            assertTrue(names.any { it.contains("/entry-") && it.endsWith(".log") })
            assertFalse(names.any { it.contains("private-window-title") || it.contains('\n') })
            val manifest = archive.getInputStream(archive.getEntry("diagnostics-manifest.json")).bufferedReader().readText()
            assertFalse(manifest.contains("private-window-title"))
            assertFalse(manifest.contains("wayland\\nsecret"))
        }
        SessionLogShare.clear(app)
    }

    @Test fun oneCategoryCannotConsumeTheOtherCategoriesByteReservations() {
        val session = tmp.newFolder("2026-11-04-11-steam")
        session.resolve("session.log").writeText("session\n".repeat(100))
        session.resolve("wayland.log").writeText("graphics\n".repeat(100))
        session.resolve("steam-11.log").writeText("game\n".repeat(100))
        val limits = EnhancedShareLimits(maxBytesPerFile = 1024, maxBytesTotal = 300)

        val zips = EnhancedSessionLogShare.create(app, session, limits = limits)

        zips.forEach { zip ->
            ZipFile(zip).use { archive ->
                assertTrue(archive.entries().toList().any { it.name != "diagnostics-manifest.json" })
            }
        }
        SessionLogShare.clear(app)
    }

    @Test fun filteredTraceScanFindsLateSummariesAndMarksThePartialSource() {
        val session = tmp.newFolder("2026-11-04-12-steam")
        val source = session.resolve("session.log")
        RandomAccessFile(source, "rw").use { file ->
            file.setLength(17L * 1024 * 1024)
            file.seek(file.length() - 80)
            file.write("\nDROIDDECK_PROCESS_TRACE pid=99 gpuLibs=libGLES.so\n".toByteArray())
        }

        val graphics = EnhancedSessionLogShare.create(app, session).single { it.name.endsWith("-graphics.zip") }

        ZipFile(graphics).use { archive ->
            val trace = archive.getInputStream(archive.getEntry("${session.name}/graphics/process-trace.log"))
                .bufferedReader().readText()
            assertTrue(trace.contains("pid=99"))
            val manifest = JSONObject(archive.getInputStream(archive.getEntry("diagnostics-manifest.json"))
                .bufferedReader().readText())
            val entries = manifest.getJSONArray("entries")
            assertTrue((0 until entries.length()).map { entries.getJSONObject(it) }.any {
                it.optString("origin") == "guest-process-trace" && it.optString("status") == "truncated"
            })
        }
        SessionLogShare.clear(app)
    }

    @Test fun filteredTraceWithMatchesOnlyOutsideTheScannedRangesStaysTruncated() {
        val session = tmp.newFolder("2026-11-04-12-partial-empty")
        val source = session.resolve("session.log")
        RandomAccessFile(source, "rw").use { file ->
            file.setLength(17L * 1024 * 1024)
            file.seek(8L * 1024 * 1024 + 256 * 1024)
            file.write("DROIDDECK_FRAME_TRACE hidden-in-middle=1\n".toByteArray())
        }

        val graphics = EnhancedSessionLogShare.create(app, session).single { it.name.endsWith("-graphics.zip") }

        ZipFile(graphics).use { archive ->
            assertTrue(archive.getEntry("${session.name}/graphics/frame-trace.log") == null)
            val manifest = JSONObject(archive.getInputStream(archive.getEntry("diagnostics-manifest.json"))
                .bufferedReader().readText())
            val entries = manifest.getJSONArray("entries")
            val record = (0 until entries.length()).map { entries.getJSONObject(it) }
                .single { it.optString("origin") == "guest-frame-trace" }
            assertEquals("truncated", record.getString("status"))
            assertEquals("no-matching-lines-in-bounded-scan", record.getString("reason"))
        }
        SessionLogShare.clear(app)
    }

    @Test fun sessionNameCannotMisclassifyPrimarySessionAndGraphicsLogs() {
        val session = tmp.newFolder("2026-11-04-12-wine")
        session.resolve("session.log").writeText("session\n")
        session.resolve("wayland.log").writeText("graphics\n")

        val zips = EnhancedSessionLogShare.create(app, session)
        val sessionEntries = ZipFile(zips.single { it.name.endsWith("-session.zip") }).use { archive ->
            archive.entries().toList().map { it.name }
        }
        val graphicsEntries = ZipFile(zips.single { it.name.endsWith("-graphics.zip") }).use { archive ->
            archive.entries().toList().map { it.name }
        }
        val gameEntries = ZipFile(zips.single { it.name.endsWith("-games.zip") }).use { archive ->
            archive.entries().toList().map { it.name }
        }

        assertTrue(sessionEntries.contains("${session.name}/session.log"))
        assertTrue(graphicsEntries.contains("${session.name}/wayland.log"))
        assertFalse(gameEntries.contains("${session.name}/session.log"))
        assertFalse(gameEntries.contains("${session.name}/wayland.log"))
        SessionLogShare.clear(app)
    }

    @Test fun unreadableDiscoverySubtreeKeepsOtherLogsAndMarksTheManifestPartial() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val session = tmp.newFolder("2026-11-04-12-partial-discovery")
        session.resolve("session.log").writeText("safe\n")
        val blocked = session.resolve("blocked").apply { mkdir(); resolve("hidden.log").writeText("hidden\n") }
        val original = Files.getPosixFilePermissions(blocked.toPath())
        Files.setPosixFilePermissions(blocked.toPath(), PosixFilePermissions.fromString("---------"))
        val sessionZip = try {
            EnhancedSessionLogShare.create(app, session).single { it.name.endsWith("-session.zip") }
        } finally {
            Files.setPosixFilePermissions(blocked.toPath(), original)
        }

        ZipFile(sessionZip).use { archive ->
            assertTrue(archive.getEntry("${session.name}/session.log") != null)
            val manifest = JSONObject(archive.getInputStream(archive.getEntry("diagnostics-manifest.json"))
                .bufferedReader().readText())
            val entries = manifest.getJSONArray("entries")
            assertTrue((0 until entries.length()).map { entries.getJSONObject(it) }.any {
                it.optString("origin") == "discovery" && it.optString("status") == "truncated" &&
                    it.optString("reason") == "filesystem-error"
            })
        }
        SessionLogShare.clear(app)
    }

    @Test fun diagnosticLookingGuestNamesCannotExposeEmbeddedSecrets() {
        val session = tmp.newFolder("2026-11-04-13-game")
        val tokenName = "dxvk-refresh_token_abcdef123456.log"
        val accountName = "battle.net-secretvalue.log"
        session.resolve(tokenName).writeText("dxvk diagnostic\n")
        session.resolve(accountName).writeText("battle net diagnostic\n")

        val games = EnhancedSessionLogShare.create(app, session).single { it.name.endsWith("-games.zip") }

        ZipFile(games).use { archive ->
            val names = archive.entries().toList().map { it.name }
            assertFalse(names.any { it.contains("refresh_token") || it.contains("secretvalue") })
            assertTrue(names.count { it.substringAfterLast('/').startsWith("entry-") } >= 2)
            val manifest = archive.getInputStream(archive.getEntry("diagnostics-manifest.json")).bufferedReader().readText()
            assertFalse(manifest.contains("refresh_token"))
            assertFalse(manifest.contains("secretvalue"))
        }
        SessionLogShare.clear(app)
    }

    @Test fun gamesArchiveIncludesOnlyGameLifecycleSummariesFromTheSessionLog() {
        val session = tmp.newFolder("2026-11-04-14-game")
        session.resolve("session.log").writeText(
            "DROIDDECK_DIAGNOSTICS event=game_start appId=42 pid=123\n" +
                "unrelated launch detail\n" +
                "DROIDDECK_DIAGNOSTICS event=game_exit appId=42 status=139\n",
        )

        val games = EnhancedSessionLogShare.create(app, session).single { it.name.endsWith("-games.zip") }

        ZipFile(games).use { archive ->
            val lifecycle = archive.getInputStream(archive.getEntry("${session.name}/games/game-lifecycle.log"))
                .bufferedReader().readText()
            assertEquals(
                "DROIDDECK_DIAGNOSTICS event=game_start appId=42 pid=123\n" +
                    "DROIDDECK_DIAGNOSTICS event=game_exit appId=42 status=139\n",
                lifecycle,
            )
            assertFalse(lifecycle.contains("unrelated launch detail"))
            val manifest = JSONObject(archive.getInputStream(archive.getEntry("diagnostics-manifest.json"))
                .bufferedReader().readText())
            assertTrue(manifest.toString().contains("guest-game-lifecycle"))
        }
        SessionLogShare.clear(app)
    }

    @Test fun injectedSecureOpenFailureFailsClosedAndTheOverrideCanBeReset() {
        val session = tmp.newFolder("2026-11-04-15-steam")
        session.resolve("session.log").writeText("must not be shared\n")
        EnhancedSessionLogShare.setSourceOpenerForTest(object : SecureSourceOpener {
            override fun <T> withChannel(
                root: java.nio.file.Path,
                relative: java.nio.file.Path,
                block: (java.nio.channels.SeekableByteChannel) -> T,
            ): T? = null
        })
        try {
            val sessionZip = EnhancedSessionLogShare.create(app, session).single { it.name.endsWith("-session.zip") }
            ZipFile(sessionZip).use { archive ->
                assertFalse(archive.entries().toList().any { it.name.endsWith("session.log") })
                val manifest = archive.getInputStream(archive.getEntry("diagnostics-manifest.json"))
                    .bufferedReader().readText()
                assertTrue(manifest.contains("unsafe-or-unavailable-source"))
            }
        } finally {
            resetSecureSourceOpener()
        }
        assertFalse(EnhancedSessionLogShare.hasSourceOpenerForTest())
        SessionLogShare.clear(app)
    }
}
