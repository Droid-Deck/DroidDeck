package com.droiddeck.launcher.frontend

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameFilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun game(id: String = "8400", name: String = "Geometry Wars") =
        Library.SteamGame(8400, name, null, "internal", id.toULong().toLong())

    @Test fun exportsPortableFilesAndAcceptsFileUriRawPathAndContentUri() {
        val folder = tmp.newFolder()
        val file = GameFiles.export(folder, game("18446744073709551615", "A/B: C"))
        assertEquals("A_B_ C (18446744073709551615).droiddeck", file.name)
        assertEquals("droiddeck://game/18446744073709551615\n", file.readText())
        val context = RuntimeEnvironment.getApplication()
        listOf(Uri.fromFile(file), Uri.parse(file.path)).forEach { uri ->
            assertEquals("18446744073709551615", GameFiles.readIntent(context, Intent(Intent.ACTION_VIEW, uri)))
        }
        val provider = LaunchFileProvider(file)
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "frontend" })
        ShadowContentResolver.registerProviderInternal("frontend", provider)
        // Providers often give opaque document IDs with no filename extension.
        val uri = Uri.parse("content://frontend/document/opaque%3A42")
        assertEquals("18446744073709551615", GameFiles.readIntent(context, Intent(Intent.ACTION_VIEW, uri)))
    }

    @Test fun rejectsCommandsMalformedIdsLargeFilesAndUnsupportedIntents() {
        listOf("", "0", "01", "-1", "18446744073709551616", "steam://rungameid/8400",
            "8400\n8401", "droiddeck://game/8400?command=quit", "x".repeat(129)).forEach { value ->
            assertNull(value, GameFiles.read(ByteArrayInputStream(value.toByteArray())))
        }
        assertEquals("8400", GameFiles.read(ByteArrayInputStream("8400\n".toByteArray())))
        val context = RuntimeEnvironment.getApplication()
        assertNull(GameFiles.readIntent(context, Intent(Intent.ACTION_SEND, Uri.parse("droiddeck://game/8400"))))
        assertNull(GameFiles.readIntent(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/8400.droiddeck"))))
        assertNull(GameFiles.readIntent(context, Intent(Intent.ACTION_VIEW, Uri.parse("file://remote/game.droiddeck"))))
        assertNull(GameFiles.readIntent(context, Intent(Intent.ACTION_VIEW, Uri.fromFile(File(tmp.root, "missing.droiddeck")))))
        assertEquals("8400", GameFiles.readIntent(context, Intent(Intent.ACTION_VIEW, Uri.parse("droiddeck://game/8400"))))
    }

    @Test fun exportsGameIconBeforeCapsuleAndPreservesCustomArtwork() {
        val folder = tmp.newFolder()
        val art = tmp.newFile("cover.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val icon = tmp.newFile("icon.jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val game = Library.SteamGame(8400, "Geometry Wars: Retro Evolved", art, "internal", icon = icon)
        GameFiles.export(folder, game)
        val image = File(folder, "images/geometry-wars-retro-evolved-icon.jpg")
        assertArrayEquals(icon.readBytes(), image.readBytes())
        LibraryCache.save(RuntimeEnvironment.getApplication(), listOf(game))
        assertEquals(icon, LibraryCache.load(RuntimeEnvironment.getApplication()).single().icon)
        image.writeText("custom artwork")
        GameFiles.sync(folder, listOf(game))
        assertEquals("custom artwork", image.readText())
        GameFiles.sync(folder, emptyList())
        assertEquals("custom artwork", image.readText())
        assertEquals(0, folder.listFiles { f -> f.extension == GameFiles.EXTENSION }!!.size)
        icon.delete()
        val fallback = tmp.newFolder()
        GameFiles.export(fallback, game)
        assertArrayEquals(art.readBytes(), File(fallback, "images/${image.name}").readBytes())
    }

    @Test fun syncAddsRenamesAndRemovesOwnedFilesAndPreservesUnrelatedAndEditedFiles() {
        val folder = tmp.newFolder()
        val unrelated = File(folder, "manual.droiddeck").apply { writeText("droiddeck://game/1\n") }
        val note = File(folder, "notes.txt").apply { writeText("Keep me") }
        val first = game()
        val second = game("620", "Portal 2")
        GameFiles.sync(folder, listOf(first, second))
        val firstFile = File(folder, GameFiles.filename(first))
        val secondFile = File(folder, GameFiles.filename(second))
        val modified = secondFile.apply { writeText("user edit") }
        val third = game("1", "New install")
        GameFiles.sync(folder, listOf(first, second, third))
        assertTrue(File(folder, GameFiles.filename(third)).isFile)
        assertEquals("user edit", modified.readText())
        val renamed = game(name = "Geometry Wars: Retro Evolved")
        GameFiles.sync(folder, listOf(renamed))
        assertFalse(firstFile.exists())
        assertTrue(File(folder, GameFiles.filename(renamed)).isFile)
        assertEquals("user edit", modified.readText())
        GameFiles.sync(folder, emptyList())
        assertFalse(File(folder, GameFiles.filename(renamed)).exists())
        assertTrue(unrelated.isFile)
        assertEquals("Keep me", note.readText())
        assertEquals("user edit", modified.readText())
    }

    @Test fun failedExportDoesNotPruneOldInventoryAndNeverOverwritesCollisions() {
        val folder = tmp.newFolder()
        val first = game()
        GameFiles.sync(folder, listOf(first))
        val second = game("620", "Portal 2")
        val collision = File(folder, GameFiles.filename(second)).apply { writeText("personal file") }
        assertThrows(IllegalArgumentException::class.java) { GameFiles.sync(folder, listOf(second)) }
        assertEquals("personal file", collision.readText())
        assertTrue(File(folder, GameFiles.filename(first)).isFile)
        collision.delete()
        GameFiles.sync(folder, listOf(second))
        assertFalse(File(folder, GameFiles.filename(first)).exists())
        assertTrue(collision.isFile)
    }

    @Test fun sanitizedNamesFitFilesystemLimitsAndDoNotEscapeFolder() {
        val folder = tmp.newFolder()
        val games = listOf(game(name = "../.."), game(name = "🎮".repeat(200)), game(name = "\u0000\n"))
        games.forEach { game ->
            val file = GameFiles.export(folder, game)
            assertEquals(folder.canonicalFile, file.canonicalFile.parentFile)
            assertTrue(file.name.toByteArray().size < 255)
        }
    }

    @Test fun refusesSymlinksLeavingTheExportFolder() {
        val folder = tmp.newFolder()
        val outside = tmp.newFile()
        outside.writeText("droiddeck://game/8400\n")
        java.nio.file.Files.createSymbolicLink(File(folder, GameFiles.filename(game())).toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { GameFiles.export(folder, game()) }
        assertEquals("droiddeck://game/8400\n", outside.readText())
    }

    @Test fun caseOnlyRenameKeepsTheLiveExportOnCaseInsensitiveStorage() {
        val folder = tmp.newFolder()
        val first = game(name = "Game")
        val renamed = game(name = "GAME")
        GameFiles.sync(folder, listOf(first))
        GameFiles.sync(folder, listOf(renamed))
        val file = File(folder, GameFiles.filename(renamed))
        assertTrue(file.isFile)
        assertEquals("8400", file.inputStream().use(GameFiles::read))
        assertEquals(1, folder.listFiles()!!.count { it.extension == GameFiles.EXTENSION })
    }

    @Test fun unrelatedInventoryIsPreserved() {
        val folder = tmp.newFolder()
        val inventory = File(folder, ".droiddeck-sync.json").apply { writeText("{}") }
        assertThrows(IllegalArgumentException::class.java) { GameFiles.sync(folder, listOf(game())) }
        assertEquals("{}", inventory.readText())
        assertEquals(0, folder.listFiles()!!.count { it.extension == GameFiles.EXTENSION })
    }

    private class LaunchFileProvider(private val file: File) : ContentProvider() {
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
