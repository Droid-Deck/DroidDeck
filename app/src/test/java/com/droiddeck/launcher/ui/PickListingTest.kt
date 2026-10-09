package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** What the in-app picker lists in a folder: every folder A-Z, then only the files asked for. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PickListingTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun storage(): File {
        val root = tmp.newFolder("emulated0")
        for (d in listOf("Download", "Android", "DCIM", "Games", "Documents", "Winlator", ".thumbnails", "alarms")) File(root, d).mkdirs()
        for (f in listOf("Setup.exe", "game.EXE", "cover.jpg", "notes.txt", "logo.PNG", "icon.ico", "hero.webp", ".hidden.exe")) File(root, f).writeText("x")
        return root
    }

    @Test fun everyFolderIsListedAToZAndDotFoldersAreNot() {
        val listing = pickListing(storage(), PickKind.EXE)
        assertEquals(listOf("alarms", "Android", "DCIM", "Documents", "Download", "Games", "Winlator"), listing.folders.map { it.dir.name })
    }

    @Test fun onlyExesForAGameOnlyImagesForArt() {
        val root = storage()
        assertEquals(listOf("game.EXE", "Setup.exe"), pickListing(root, PickKind.EXE).files.map { it.name })
        assertEquals(listOf("cover.jpg", "hero.webp", "icon.ico", "logo.PNG"), pickListing(root, PickKind.IMAGE).files.map { it.name })
    }

    @Test fun aFolderTheAppCannotReadIsListedButMarked() {
        val root = storage()
        val locked = File(root, "Android")
        locked.setReadable(false, false)
        try {
            assumeFalse("running as root reads everything", locked.list() != null)
            val folders = pickListing(root, PickKind.EXE).folders.associate { it.dir.name to it.readable }
            assertFalse(folders.getValue("Android"))
            assertTrue(folders.getValue("Download"))
        } finally {
            locked.setReadable(true, false)
        }
    }
}
