package com.droiddeck.launcher.session

import android.content.pm.PackageManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SecureLogFileProviderTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: SecureLogFileProvider
    private lateinit var root: File

    @Before fun setUp() {
        resetFileProviderRoot()
        root = File(app.cacheDir, "share").apply { mkdirs() }
        val info = app.packageManager.resolveContentProvider(
            app.packageName + ".logs",
            PackageManager.GET_META_DATA,
        )!!
        provider = SecureLogFileProvider().also { it.attachInfo(app, info) }
        SecureLogFileProvider.setOpenerForTest(JvmSecureLogOpener(root))
    }

    @After fun tearDown() {
        SecureLogFileProvider.setOpenerForTest(null)
        resetFileProviderRoot()
    }

    private fun resetFileProviderRoot() {
        val field = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = field.get(null) as MutableMap<String, Any>
        synchronized(cache) { cache.remove(app.packageName + ".logs") }
    }

    @Test fun generatedShareUriIsReadOnlyAndReturnsExactBytes() {
        val source = File(root, "DroidDeck-session.zip").apply { writeBytes("PK\u0003\u0004payload".toByteArray()) }
        val uri = FileProvider.getUriForFile(app, app.packageName + ".logs", source)

        val bytes = ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri, "r")).use { it.readBytes() }
        assertArrayEquals(source.readBytes(), bytes)
        assertEquals("application/zip", provider.getType(uri))
        provider.query(uri, null, null, null, null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(source.name, cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(source.length(), cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        assertThrows(FileNotFoundException::class.java) { provider.openFile(uri, "rw") }
        assertThrows(UnsupportedOperationException::class.java) { provider.delete(uri, null, null) }
    }

    @Test fun syncthingTextLogRemainsShareable() {
        val source = File(root, "syncthing-log.txt").apply { writeText("syncthing ready\n") }
        val uri = FileProvider.getUriForFile(app, app.packageName + ".logs", source)

        assertEquals("text/plain", provider.getType(uri))
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri, "r")).use { it.readBytes() }
        assertArrayEquals(source.readBytes(), bytes)
    }

    @Test fun invalidAndEncodedTraversalUrisAreRejectedBeforeOpen() {
        val authority = app.packageName + ".logs"
        val invalid = listOf(
            Uri.parse("file:///shared_logs/private"),
            Uri.parse("content://$authority/other/private"),
            Uri.parse("content://$authority/shared_logs/.."),
            Uri.parse("content://$authority/shared_logs/%2Fdata%2Fprivate"),
            Uri.parse("content://other.app/shared_logs/DroidDeck-session.zip"),
        )
        invalid.forEach { uri ->
            assertThrows(FileNotFoundException::class.java) { provider.openFile(uri, "r") }
        }
    }

    @Test fun symlinkAndHardlinkLeavesAreRejected() {
        val outside = File(app.cacheDir, "private.txt").apply { writeText("private") }
        val symbolic = root.toPath().resolve("symbolic.zip")
        Files.createSymbolicLink(symbolic, outside.toPath())
        assertThrows(FileNotFoundException::class.java) { provider.openFile(uri("symbolic.zip"), "r") }

        val original = File(root, "original.zip").apply { writeText("content") }
        val hard = root.toPath().resolve("hard.zip")
        Files.createLink(hard, original.toPath())
        assertThrows(FileNotFoundException::class.java) { provider.openFile(uri("hard.zip"), "r") }
    }

    @Test fun symlinkedShareRootIsRejected() {
        val outside = File(app.cacheDir, "outside-share").apply { mkdirs() }
        File(outside, "DroidDeck-session.zip").writeText("private")
        root.deleteRecursively()
        Files.createSymbolicLink(root.toPath(), outside.toPath())

        assertThrows(FileNotFoundException::class.java) {
            provider.openFile(uri("DroidDeck-session.zip"), "r")
        }
    }

    private fun uri(name: String): Uri = Uri.Builder()
        .scheme("content")
        .authority(app.packageName + ".logs")
        .appendPath("shared_logs")
        .appendPath(name)
        .build()

    private class JvmSecureLogOpener(private val root: File) : SecureLogOpener {
        override fun open(context: android.content.Context, name: String): ParcelFileDescriptor? {
            if (Files.isSymbolicLink(root.toPath())) return null
            val target = root.toPath().resolve(name).normalize()
            if (target.parent != root.toPath().toAbsolutePath().normalize() || Files.isSymbolicLink(target)) return null
            val attrs = runCatching { Files.readAttributes(target, java.nio.file.attribute.BasicFileAttributes::class.java) }
                .getOrNull() ?: return null
            val links = runCatching { Files.getAttribute(target, "unix:nlink") as Number }.getOrNull()?.toLong() ?: 1L
            if (!attrs.isRegularFile || links != 1L) return null
            return ParcelFileDescriptor.open(target.toFile(), ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }
}
