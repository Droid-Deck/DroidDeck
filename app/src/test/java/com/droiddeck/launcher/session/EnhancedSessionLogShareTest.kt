package com.droiddeck.launcher.session

import android.content.ContentProvider
import android.content.ContentValues
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import androidx.core.content.FileProvider
import org.robolectric.shadows.ShadowContentResolver
import java.io.IOException
import java.io.RandomAccessFile
import java.io.ByteArrayOutputStream
import java.io.File
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

    private fun resetFileProviderRoots() {
        val field = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = field.get(null) as MutableMap<String, Any>
        synchronized(cache) { cache.remove(app.packageName + ".logs") }
    }

    @Before fun installTestFileOpeners() {
        installJvmSecureSourceOpener()
        // AndroidX caches FileProvider roots by authority, while Robolectric gives every test a
        // different temporary cacheDir. Production has one stable cacheDir for the app process.
        resetFileProviderRoots()
        SecureLogFileProvider.setOpenerForTest(JvmSecureLogOpener(SessionLogShare.shareDir(app)))
    }

    @After fun resetTestOverrides() {
        resetSecureSourceOpener()
        SecureLogFileProvider.setOpenerForTest(null)
        SessionLogShare.setSaveDestinationValidatorForTest(null)
        resetFileProviderRoots()
    }

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

    @Test fun rawSnapshotsNeverAppearInTheGuestVisibleCache() {
        val session = tmp.newFolder("2026-11-04-01-memory-snapshot")
        session.resolve("session.log").writeText("account=test-user token=synthetic_private_value\n")
        var inspected = false

        val zips = EnhancedSessionLogShare.create(app, session, onProgress = {
            inspected = true
            assertFalse(app.cacheDir.walkTopDown().any { file ->
                file.name.endsWith(".diagnostic-stage") || file.name.startsWith("diagnostic-")
            })
        })

        assertTrue(inspected)
        assertEquals(3, zips.size)
        assertFalse(app.cacheDir.walkTopDown().any { it.name.endsWith(".diagnostic-stage") })
        ZipFile(zips.single { it.name.endsWith("-session.zip") }).use { archive ->
            val shared = archive.getInputStream(archive.getEntry("${session.name}/session.log"))
                .bufferedReader().readText()
            assertFalse(shared.contains("synthetic_private_value"))
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
        val uris = send.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
        assertEquals(3, uris.size)
        assertEquals(3, send.clipData!!.itemCount)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val header = app.contentResolver.openInputStream(uris.first())!!.use { it.readNBytes(4) }
        assertEquals("PK\u0003\u0004", String(header, Charsets.ISO_8859_1))
        SessionLogShare.clear(app)
    }

    @Test fun enhancedBundlesCanBeSavedIndividuallyOrAsOneOuterArchive() {
        val session = tmp.newFolder("2026-11-04-04-save")
        session.resolve("session.log").writeText("startup\n")
        session.resolve("wayland.log").writeText("frame\n")
        session.resolve("steam-42.log").writeText("game\n")
        val zips = EnhancedSessionLogShare.create(app, session)
        val sessionZip = zips.single { it.name.endsWith("-session.zip") }

        val single = ByteArrayOutputStream()
        SessionLogShare.writeEnhancedSave(app, listOf(sessionZip), single)
        assertEquals(sessionZip.name, SessionLogShare.enhancedSaveName(app, listOf(sessionZip)))
        assertArrayEquals(sessionZip.readBytes(), single.toByteArray())

        val all = tmp.newFile("saved-all.zip")
        all.outputStream().use { SessionLogShare.writeEnhancedSave(app, zips, it) }
        assertEquals("DroidDeck-${session.name}-all.zip", SessionLogShare.enhancedSaveName(app, zips))
        ZipFile(all).use { archive ->
            assertEquals(zips.map { it.name }.toSet(), archive.entries().toList().map { it.name }.toSet())
            for (source in zips) {
                val nested = archive.getInputStream(archive.getEntry(source.name)).readBytes()
                assertArrayEquals(source.readBytes(), nested)
                java.util.zip.ZipInputStream(nested.inputStream()).use { inner ->
                    assertTrue(generateSequence { inner.nextEntry }.any { it.name == "diagnostics-manifest.json" })
                }
            }
        }

        val prepared = SessionLogShare.prepareEnhancedSave(app, zips)
        assertEquals("DroidDeck-${session.name}-all.zip", prepared.outputName)
        val restoredState = SessionLogShare.saveState(prepared)
        val restored = SessionLogShare.restoreSave(app, restoredState)!!
        assertEquals(restoredState, SessionLogShare.saveState(restored))
        val destination = tmp.newFile("saved-output.zip")
        val provider = ExternalDocumentsProvider(destination)
        val authority = "diagnostic-save-test"
        provider.attachInfo(app, android.content.pm.ProviderInfo().apply {
            this.authority = authority
            exported = true
            grantUriPermissions = true
            readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
            writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
        })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val destinationUri = DocumentsContract.buildDocumentUri(authority, "saved-output")
        SessionLogShare.setSaveDestinationValidatorForTest { _, uri -> uri == destinationUri }
        SessionLogShare.saveEnhanced(app, restored, destinationUri)
        assertArrayEquals(all.readBytes(), destination.readBytes())
        assertEquals(listOf("w"), provider.openModes)
        SessionLogShare.clear(app)
    }

    @Test fun enhancedSaveRejectsFilesOutsideThePrivateShareCacheAndDuplicateBundles() {
        val outside = tmp.newFile("DroidDeck-2026-11-04-04-save-session.zip").apply { writeText("not a zip") }
        assertThrows(IllegalArgumentException::class.java) {
            SessionLogShare.writeEnhancedSave(app, listOf(outside), ByteArrayOutputStream())
        }

        val session = tmp.newFolder("2026-11-04-04-save-invalid")
        session.resolve("session.log").writeText("startup\n")
        val zips = EnhancedSessionLogShare.create(app, session)
        assertThrows(IllegalArgumentException::class.java) {
            SessionLogShare.writeEnhancedSave(app, listOf(zips[0], zips[0], zips[1]), ByteArrayOutputStream())
        }

        val oversized = SessionLogShare.shareDir(app).resolve("DroidDeck-${session.name}-session.zip")
        RandomAccessFile(oversized, "rw").use { it.setLength(513L * 1024 * 1024) }
        assertThrows(IllegalArgumentException::class.java) {
            SessionLogShare.prepareEnhancedSave(app, listOf(oversized))
        }

        SessionLogShare.clear(app)
        val root = SessionLogShare.shareDir(app).apply { mkdirs() }
        val aggregate = listOf("session", "graphics", "games").map { category ->
            root.resolve("DroidDeck-${session.name}-$category.zip").also { file ->
                RandomAccessFile(file, "rw").use { it.setLength(171L * 1024 * 1024) }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionLogShare.prepareEnhancedSave(app, aggregate)
        }
        SessionLogShare.clear(app)
    }

    @Test fun preparedEnhancedSaveRejectsDeletionReplacementGrowthAndHardLinksBeforeWritingDestination() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val session = tmp.newFolder("2026-11-04-04-prepared-save")
        session.resolve("session.log").writeText("startup\n")
        fun fresh() = EnhancedSessionLogShare.create(app, session)
        val untouched = "destination-not-opened".toByteArray()
        fun destination(): Pair<File, android.net.Uri> {
            val file = SessionLogShare.shareDir(app).apply { mkdirs() }
                .resolve("save-destination.zip").apply { writeBytes(untouched) }
            return file to FileProvider.getUriForFile(app, app.packageName + ".logs", file)
        }
        SessionLogShare.setSaveDestinationValidatorForTest { _, _ -> true }

        var zips = fresh()
        var prepared = SessionLogShare.prepareEnhancedSave(app, zips)
        Files.write(zips.first().toPath(), byteArrayOf(0), StandardOpenOption.APPEND)
        var (output, uri) = destination()
        assertThrows(IllegalArgumentException::class.java) {
            SessionLogShare.saveEnhanced(app, prepared, uri)
        }
        assertArrayEquals(untouched, output.readBytes())

        zips = fresh()
        prepared = SessionLogShare.prepareEnhancedSave(app, zips)
        val hardLink = zips.first().toPath().resolveSibling("hard-link.zip")
        Files.createLink(hardLink, zips.first().toPath())
        destination().also { (file, target) -> output = file; uri = target }
        assertThrows(IllegalArgumentException::class.java) {
            SessionLogShare.saveEnhanced(app, prepared, uri)
        }
        assertArrayEquals(untouched, output.readBytes())
        Files.delete(hardLink)

        zips = fresh()
        prepared = SessionLogShare.prepareEnhancedSave(app, zips)
        val replacement = zips.first().readBytes().also { it[0] = (it[0].toInt() xor 1).toByte() }
        zips.first().writeBytes(replacement)
        destination().also { (file, target) -> output = file; uri = target }
        assertThrows(IllegalArgumentException::class.java) {
            SessionLogShare.saveEnhanced(app, prepared, uri)
        }
        assertArrayEquals(untouched, output.readBytes())

        zips = fresh()
        prepared = SessionLogShare.prepareEnhancedSave(app, zips)
        SessionLogShare.clear(app)
        destination().also { (file, target) -> output = file; uri = target }
        assertThrows(IllegalArgumentException::class.java) {
            SessionLogShare.saveEnhanced(app, prepared, uri)
        }
        assertArrayEquals(untouched, output.readBytes())
        SessionLogShare.clear(app)
    }

    @Test fun enhancedSaveRejectsSymlinkHardlinkSwapAndGrowth() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val session = tmp.newFolder("2026-11-04-04-save-races")
        session.resolve("session.log").writeText("startup\n")
        val source = EnhancedSessionLogShare.create(app, session)
            .single { it.name.endsWith("-session.zip") }
        val root = SessionLogShare.shareDir(app).toPath()
        val outside = tmp.newFile("outside.zip").apply { writeText("outside") }.toPath()

        val symbolic = root.resolve("DroidDeck-${session.name}-symbolic-session.zip")
        Files.createSymbolicLink(symbolic, outside)
        assertThrows(IllegalArgumentException::class.java) {
            SessionLogShare.writeEnhancedSave(app, listOf(symbolic.toFile()), ByteArrayOutputStream())
        }

        val hard = root.resolve("DroidDeck-${session.name}-hard-session.zip")
        Files.createLink(hard, source.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            SessionLogShare.writeEnhancedSave(app, listOf(hard.toFile()), ByteArrayOutputStream())
        }
        Files.delete(hard)

        EnhancedSessionLogShare.setSourceOpenerForTest(object : SecureSourceOpener {
            override fun <T> withChannel(
                root: java.nio.file.Path,
                relative: java.nio.file.Path,
                block: (SeekableByteChannel) -> T,
            ): T? {
                val target = root.resolve(relative)
                Files.delete(target)
                Files.createSymbolicLink(target, outside)
                return JvmSecureSourceOpener.withChannel(root, relative, block)
            }
        })
        assertThrows(IllegalArgumentException::class.java) {
            SessionLogShare.writeEnhancedSave(app, listOf(source), ByteArrayOutputStream())
        }
        EnhancedSessionLogShare.setSourceOpenerForTest(JvmSecureSourceOpener)
        Files.delete(symbolic)
        Files.delete(source.toPath())
        val recreated = EnhancedSessionLogShare.create(app, session)
            .single { it.name.endsWith("-session.zip") }
        assertEquals(source.absolutePath, recreated.absolutePath)

        EnhancedSessionLogShare.setSourceOpenerForTest(object : SecureSourceOpener {
            override fun <T> withChannel(
                root: java.nio.file.Path,
                relative: java.nio.file.Path,
                block: (SeekableByteChannel) -> T,
            ): T? = JvmSecureSourceOpener.withChannel(root, relative) { channel ->
                var grown = false
                block(object : SeekableByteChannel by channel {
                    override fun read(dst: ByteBuffer): Int {
                        val read = channel.read(dst)
                        if (!grown && read > 0) {
                            Files.write(root.resolve(relative), byteArrayOf(0), StandardOpenOption.APPEND)
                            grown = true
                        }
                        return read
                    }
                })
            }
        })
        assertThrows(IllegalArgumentException::class.java) {
            SessionLogShare.writeEnhancedSave(app, listOf(source), ByteArrayOutputStream())
        }
        SessionLogShare.clear(app)
    }

    @Test fun sameSizeMutationDuringCopyUsesProviderTruncateFallback() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val session = tmp.newFolder("2026-11-04-04-copy-race")
        session.resolve("session.log").writeText("startup\n")
        val source = EnhancedSessionLogShare.create(app, session)
            .single { it.name.endsWith("-session.zip") }
        val prepared = SessionLogShare.prepareEnhancedSave(app, listOf(source))

        EnhancedSessionLogShare.setSourceOpenerForTest(object : SecureSourceOpener {
            override fun <T> withChannel(
                root: java.nio.file.Path,
                relative: java.nio.file.Path,
                block: (SeekableByteChannel) -> T,
            ): T? = JvmSecureSourceOpener.withChannel(root, relative) { channel ->
                var rewinds = 0
                block(object : SeekableByteChannel by channel {
                    override fun position(newPosition: Long): SeekableByteChannel {
                        channel.position(newPosition)
                        if (newPosition == 0L && ++rewinds == 2) {
                            Files.write(root.resolve(relative), byteArrayOf(0x7f), StandardOpenOption.WRITE)
                        }
                        return this
                    }
                })
            }
        })

        val output = tmp.newFile("partial-output.zip").apply { writeText("existing") }
        val provider = NoDeleteOutputProvider(output)
        val authority = "diagnostic-save-test"
        provider.attachInfo(app, android.content.pm.ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val destination = Uri.parse("content://$authority/output.zip")
        SessionLogShare.setSaveDestinationValidatorForTest { _, uri -> uri == destination }

        assertThrows(IllegalArgumentException::class.java) {
            SessionLogShare.saveEnhanced(app, prepared, destination)
        }
        assertEquals(listOf("w", "rwt"), provider.openModes)
        assertEquals(1, provider.deleteCalls)
        assertEquals(0L, output.length())
        SessionLogShare.clear(app)
    }

    @Test fun saveRejectsFileOwnAppAndNonDocumentUrisWithoutOpeningThem() {
        val session = tmp.newFolder("2026-11-04-04-destination-validation")
        session.resolve("session.log").writeText("startup\n")
        val source = EnhancedSessionLogShare.create(app, session)
            .single { it.name.endsWith("-session.zip") }
        val prepared = SessionLogShare.prepareEnhancedSave(app, listOf(source))
        val output = tmp.newFile("destination-not-opened.zip").apply { writeText("existing") }
        val provider = NoDeleteOutputProvider(output)
        val authority = "ordinary-save-provider"
        provider.attachInfo(app, android.content.pm.ProviderInfo().apply {
            this.authority = authority
            packageName = "example.untrusted.picker"
        })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val ownFile = SessionLogShare.shareDir(app).resolve("own-destination.zip").apply { createNewFile() }
        val destinations = listOf(
            Uri.fromFile(output),
            FileProvider.getUriForFile(app, app.packageName + ".logs", ownFile),
            Uri.parse("content://${app.packageName}.documents/document/private"),
            Uri.parse("content://$authority/not-a-document"),
        )

        for (destination in destinations) {
            assertFalse(SessionLogShare.validSaveDestination(app, destination))
            assertThrows(IllegalArgumentException::class.java) {
                SessionLogShare.saveEnhanced(app, prepared, destination)
            }
        }
        assertTrue(provider.openModes.isEmpty())
        assertEquals(0, provider.deleteCalls)
        assertEquals("existing", output.readText())
        SessionLogShare.clear(app)
    }

    @Test fun externalDocumentProviderPassesProductionValidationAndReceivesTheBundle() {
        val session = tmp.newFolder("2026-11-04-04-external-document")
        session.resolve("session.log").writeText("startup\n")
        val source = EnhancedSessionLogShare.create(app, session)
            .single { it.name.endsWith("-session.zip") }
        val prepared = SessionLogShare.prepareEnhancedSave(app, listOf(source))
        val output = tmp.newFile("external-document.zip")
        val provider = ExternalDocumentsProvider(output)
        val authority = "external.documents"
        val packageName = "example.external.documents"
        val providerInfo = android.content.pm.ProviderInfo().apply {
            this.authority = authority
            this.packageName = packageName
            name = ExternalDocumentsProvider::class.java.name
            applicationInfo = android.content.pm.ApplicationInfo().apply { this.packageName = packageName }
            exported = true
            grantUriPermissions = true
            readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
            writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
        }
        provider.attachInfo(app, providerInfo)
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val component = ComponentName(packageName, providerInfo.name)
        shadowOf(app.packageManager).apply {
            addOrUpdateProvider(providerInfo)
            addIntentFilterForProvider(component, IntentFilter(DocumentsContract.PROVIDER_INTERFACE))
        }
        val destination = DocumentsContract.buildDocumentUri(authority, "output")

        assertTrue(SessionLogShare.validSaveDestination(app, destination))
        SessionLogShare.saveEnhanced(app, prepared, destination)
        assertArrayEquals(source.readBytes(), output.readBytes())
        assertEquals(listOf("w"), provider.openModes)
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

    @Test fun sourceReadFailureIsRecordedWithoutFailingTheArchive() {
        val session = tmp.newFolder("2026-11-04-16-steam")
        session.resolve("session.log").writeText("must not be shared\n")
        EnhancedSessionLogShare.setSourceOpenerForTest(object : SecureSourceOpener {
            override fun <T> withChannel(
                root: java.nio.file.Path,
                relative: java.nio.file.Path,
                block: (java.nio.channels.SeekableByteChannel) -> T,
            ): T? = FileChannel.open(root.resolve(relative), StandardOpenOption.READ).use { channel ->
                block(object : SeekableByteChannel by channel {
                    override fun read(dst: ByteBuffer): Int = throw IOException("injected source read failure")
                })
            }
        })
        try {
            val sessionZip = EnhancedSessionLogShare.create(app, session).single { it.name.endsWith("-session.zip") }
            ZipFile(sessionZip).use { archive ->
                assertFalse(archive.entries().toList().any { it.name.endsWith("session.log") })
                val manifest = JSONObject(archive.getInputStream(archive.getEntry("diagnostics-manifest.json"))
                    .bufferedReader().readText()).getJSONArray("entries")
                val record = (0 until manifest.length()).map { manifest.getJSONObject(it) }
                    .single { it.optString("entry").endsWith("session.log") }
                assertEquals("error", record.getString("status"))
                assertEquals("IOException", record.getString("reason"))
            }
        } finally {
            resetSecureSourceOpener()
        }
        SessionLogShare.clear(app)
    }

    private class NoDeleteOutputProvider(private val file: File) : ContentProvider() {
        val openModes = ArrayList<String>()
        var deleteCalls = 0

        override fun onCreate() = true

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            openModes += mode
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deleteCalls++
            return 0
        }

        override fun getType(uri: Uri) = "application/zip"
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0
    }

    private class ExternalDocumentsProvider(private val file: File) : DocumentsProvider() {
        val openModes = ArrayList<String>()

        override fun onCreate() = true

        override fun queryRoots(projection: Array<out String>?): Cursor =
            MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))

        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
            MatrixCursor(projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID))

        override fun queryChildDocuments(
            parentDocumentId: String,
            projection: Array<out String>?,
            sortOrder: String?,
        ): Cursor = MatrixCursor(projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID))

        override fun openDocument(
            documentId: String,
            mode: String,
            signal: CancellationSignal?,
        ): ParcelFileDescriptor {
            openModes += mode
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        }
    }
}
