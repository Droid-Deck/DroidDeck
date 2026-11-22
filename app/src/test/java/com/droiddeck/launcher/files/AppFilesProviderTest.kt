package com.droiddeck.launcher.files

import android.Manifest
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.provider.DocumentsContract.Document
import com.droiddeck.launcher.session.SessionPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppFilesProviderTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: AppFilesProvider
    private lateinit var base: File

    @Before fun setUp() {
        base = app.dataDir
        provider = AppFilesProvider()
        provider.attachInfo(app, ProviderInfo().apply {
            authority = "${app.packageName}.documents"
            exported = true
            grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS
            writePermission = Manifest.permission.MANAGE_DOCUMENTS
        })
    }

    private fun file(path: String, text: String = "x") = File(base, path).apply { parentFile!!.mkdirs(); writeText(text) }

    private fun children(dir: File): List<String> {
        val c: Cursor = provider.queryChildDocuments(dir.absolutePath, null, null as String?)
        try {
            val names = mutableListOf<String>()
            while (c.moveToNext()) names += c.getString(c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME))
            return names
        } finally {
            c.close()
        }
    }

    @Test fun listingHidesPrivateFolders() {
        file("no_backup/adbkey")
        file("shared_prefs/settings.xml")
        file("databases/app.db")
        file("files/stores/epic/auth.json")
        file("files/saves/slot1.sav")
        val steam = SessionPrefs.steamRoot(app, "arm64")
        File(steam, "config/loginusers.vdf").apply { parentFile!!.mkdirs(); writeText("x") }
        File(steam, "steamapps/game.txt").apply { parentFile!!.mkdirs(); writeText("x") }

        val root = children(base)
        assertFalse(root.contains("no_backup"))
        assertFalse(root.contains("shared_prefs"))
        assertFalse(root.contains("databases"))
        assertTrue(root.contains("files"))
        val files = children(File(base, "files"))
        assertFalse(files.contains("stores"))
        assertTrue(files.contains("saves"))
        val steamChildren = children(steam)
        assertFalse(steamChildren.contains("config"))
        assertTrue(steamChildren.contains("steamapps"))
    }

    @Test fun privateFilesDoNotOpen() {
        val key = file("no_backup/adbkey")
        val prefs = file("shared_prefs/settings.xml")
        val login = File(SessionPrefs.steamRoot(app, "x86_64"), "config/loginusers.vdf").apply { parentFile!!.mkdirs(); writeText("x") }
        listOf(key, prefs, login).forEach { f ->
            assertThrows(FileNotFoundException::class.java) { provider.openDocument(f.absolutePath, "r", null) }
            assertThrows(FileNotFoundException::class.java) { provider.queryDocument(f.absolutePath, null) }
            assertThrows(FileNotFoundException::class.java) { provider.deleteDocument(f.absolutePath) }
        }
        assertTrue(key.exists())
    }

    @Test fun ordinaryFileStillOpens() {
        val save = file("files/saves/slot1.sav", "progress")
        provider.openDocument(save.absolutePath, "r", null).use { pfd ->
            assertEquals("progress", android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText())
        }
    }

    @Test fun privateFoldersCannotBeCreatedOrCarriedOut() {
        File(base, "databases").deleteRecursively()
        assertThrows(FileNotFoundException::class.java) { provider.createDocument(base.absolutePath, Document.MIME_TYPE_DIR, "databases") }
        assertFalse(File(base, "databases").exists())
        file("files/stores/epic/auth.json")
        val files = File(base, "files")
        assertThrows(FileNotFoundException::class.java) { provider.renameDocument(files.absolutePath, "elsewhere") }
        assertThrows(FileNotFoundException::class.java) { provider.deleteDocument(files.absolutePath) }
        assertTrue(File(files, "stores/epic/auth.json").exists())
    }
}
