package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BottomScreenshotsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun fileNamesSortByTime() {
        val name = BottomScreenshots.fileName(0L)
        assertTrue(name, name.startsWith("DroidDeck_") && name.endsWith(".jpg"))
        assertEquals(29, name.length)
    }

    @Test fun steamScreenshotsAreFoundAndThumbnailsSkipped() {
        val root = tmp.root
        val shots = File(root, "root/.local/share/Steam/userdata/2932373/760/remote/220/screenshots")
        File(shots, "thumbnails").mkdirs()
        File(shots, "20261031010101_1.jpg").writeText("x")
        File(shots, "thumbnails/20261031010101_1.jpg").writeText("x")
        File(shots, "screenshots.vdf").writeText("x")
        val x64 = File(root, "root/.droiddeck-x64/.local/share/Steam/userdata/2932373/760/remote/440/screenshots")
        x64.mkdirs()
        File(x64, "20261031020202_1.jpg").writeText("x")
        val found = BottomScreenshots.steam(root).map { it.file!!.name }.sorted()
        assertEquals(listOf("20261031010101_1.jpg", "20261031020202_1.jpg"), found)
    }
}
