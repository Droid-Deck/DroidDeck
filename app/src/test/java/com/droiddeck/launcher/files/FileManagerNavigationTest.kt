package com.droiddeck.launcher.files

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileManagerNavigationTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun backCannotNavigateAboveTheCurrentDriveRoot() {
        val drive = tmp.newFolder("drive")
        val child = File(drive, "folder").apply { mkdir() }

        assertTrue(canNavigateUp(child, drive))
        assertFalse(canNavigateUp(drive, drive))
    }
}
