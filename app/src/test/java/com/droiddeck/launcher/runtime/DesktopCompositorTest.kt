package com.droiddeck.launcher.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopCompositorTest {
    private fun root(): File {
        val dir = File.createTempFile("desktop", "").let { it.delete(); it.mkdirs(); it }
        File(dir, "usr/share/droiddeck").mkdirs()
        dir.deleteOnExit()
        return dir
    }

    private fun marker(dir: File) = File(dir, "usr/share/droiddeck/kwin-compositor")
    private fun backup(dir: File) = File(dir, "usr/share/droiddeck/kwin-compositor.orig")
    private fun lastWritten(dir: File) = File(dir, "usr/share/droiddeck/kwin-compositor.mine")
    private fun text(file: File) = if (file.isFile) file.readText().trim() else null

    @Test fun turningItOnKeepsTheMarkerItFoundAndWritesSoftware() {
        val dir = root()
        marker(dir).writeText("opengl\n")
        assertTrue(DesktopCompositor.setForceSoftwareAt(dir, true))
        assertEquals("software", text(marker(dir)))
        assertEquals("opengl", text(backup(dir)))
        assertEquals("software", text(lastWritten(dir)))
        assertTrue(DesktopCompositor.forceSoftwareAt(dir))
    }

    @Test fun turningItOffRestoresTheBackupAndRemovesBothSideFiles() {
        val dir = root()
        marker(dir).writeText("opengl\n")
        DesktopCompositor.setForceSoftwareAt(dir, true)
        assertTrue(DesktopCompositor.setForceSoftwareAt(dir, false))
        assertEquals("opengl", text(marker(dir)))
        assertFalse(backup(dir).exists())
        assertFalse(lastWritten(dir).exists())
        assertFalse(DesktopCompositor.forceSoftwareAt(dir))
    }

    @Test fun aHandEditedSoftwareMarkerReadsAsOnAndGetsNoInventedBackup() {
        val dir = root()
        marker(dir).writeText("software\n")
        assertTrue(DesktopCompositor.forceSoftwareAt(dir))
        assertTrue(DesktopCompositor.conflictedAt(dir))
        assertTrue(DesktopCompositor.setForceSoftwareAt(dir, true))
        assertEquals("software", text(marker(dir)))
        assertFalse(backup(dir).exists())
    }

    @Test fun turningItOffLeavesAMarkerItDidNotWrite() {
        val dir = root()
        marker(dir).writeText("software\n")
        assertTrue(DesktopCompositor.conflictedAt(dir))
        assertTrue(DesktopCompositor.setForceSoftwareAt(dir, false))
        assertEquals("software", text(marker(dir)))
        assertTrue(DesktopCompositor.conflictedAt(dir))
    }

    @Test fun aMarkerTheSwitchWroteIsNotAConflict() {
        val dir = root()
        marker(dir).writeText("opengl\n")
        DesktopCompositor.setForceSoftwareAt(dir, true)
        assertFalse(DesktopCompositor.conflictedAt(dir))
        DesktopCompositor.setForceSoftwareAt(dir, false)
        assertFalse(DesktopCompositor.conflictedAt(dir))
    }

    @Test fun turningItOffNeverPutsBackAStaleBackup() {
        val dir = root()
        marker(dir).writeText("opengl\n")
        DesktopCompositor.setForceSoftwareAt(dir, true)
        marker(dir).writeText("opengl\n")
        assertTrue(DesktopCompositor.setForceSoftwareAt(dir, false))
        assertEquals("opengl", text(marker(dir)))
        assertFalse(lastWritten(dir).exists())
        assertFalse(backup(dir).exists())
    }

    @Test fun aMarkerThatCannotBeWrittenKeepsTheBackup() {
        val dir = root()
        marker(dir).writeText("opengl\n")
        // A directory where the note goes makes its write fail, after the backup was made.
        assertTrue(lastWritten(dir).mkdirs())
        try {
            assertFalse(DesktopCompositor.setForceSoftwareAt(dir, true))
            assertEquals("opengl", text(marker(dir)))
            assertEquals("opengl", text(backup(dir)))
        } finally {
            lastWritten(dir).delete()
        }
        // The marker is unchanged text, so the switch still recognises it and a reset can put it back.
        assertTrue(DesktopCompositor.setForceSoftwareAt(dir, false))
        assertEquals("opengl", text(marker(dir)))
        assertFalse(backup(dir).exists())
    }

    @Test fun aDesktopThatIsNotInstalledHasNoSwitch() {
        val dir = root()
        assertFalse(DesktopCompositor.installedAt(dir))
        marker(dir).writeText("opengl\n")
        assertTrue(DesktopCompositor.installedAt(dir))
    }
}
