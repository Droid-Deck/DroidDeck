package com.droiddeck.launcher.runtime

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeUserDataCarryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun put(root: File, rel: String, text: String = rel) = File(root, rel).apply { parentFile.mkdirs(); writeText(text) }

    private fun userTree(): File = tmp.newFolder("old").also { old ->
        put(old, "root/save.dat", "mine")
        put(old, "opt/appimages/user/app1/app.AppImage")
        put(old, "opt/droiddeck-apps/game/run.sh")
        put(old, "usr/local/share/applications/droiddeck-app-game.desktop")
        put(old, "usr/local/share/applications/droiddeck-appimage-app1.desktop")
        put(old, "usr/local/share/applications/other.desktop")
        put(old, "opt/appimages/dolphin.AppImage")
        put(old, "usr/share/applications/droiddeck-dolphin.desktop")
        put(old, ".droiddeck-pkg-dolphin", "1.0")
        put(old, "usr/bin/tool", "old system file")
    }

    @Test fun anUpdateKeepsImportedAppsCatalogAppImagesAndHome() {
        val old = userTree()
        val staging = tmp.newFolder("new").also { put(it, "root/.bashrc", "shipped"); put(it, "usr/bin/tool", "new system file") }

        val moved = RuntimeFileTree.carryUserData(old, staging, false)

        assertEquals("mine", File(staging, "root/save.dat").readText())
        assertFalse("the shipped home is replaced, not merged", File(staging, "root/.bashrc").exists())
        for (rel in listOf("opt/appimages/user/app1/app.AppImage", "opt/droiddeck-apps/game/run.sh",
                "usr/local/share/applications/droiddeck-app-game.desktop", "usr/local/share/applications/droiddeck-appimage-app1.desktop",
                "opt/appimages/dolphin.AppImage", "usr/share/applications/droiddeck-dolphin.desktop", ".droiddeck-pkg-dolphin")) {
            assertTrue(rel, File(staging, rel).exists())
            assertTrue(rel, rel in moved || moved.any { rel.startsWith("$it/") })
        }
        assertEquals("system files come from the new runtime", "new system file", File(staging, "usr/bin/tool").readText())
        assertFalse("only DroidDeck's own menu entries are user data", File(staging, "usr/local/share/applications/other.desktop").exists())
    }

    @Test fun aFailureHalfwayPutsEverythingBack() {
        val old = userTree()
        val staging = tmp.newFolder("new")
        put(staging, "usr/local", "a file where a folder is needed")

        try {
            RuntimeFileTree.carryUserData(old, staging, false)
            fail("expected the carry to fail")
        } catch (expected: IOException) {
        }

        assertEquals("mine", File(old, "root/save.dat").readText())
        assertTrue(File(old, "opt/appimages/user/app1/app.AppImage").exists())
        assertTrue(File(old, "opt/droiddeck-apps/game/run.sh").exists())
        assertFalse(File(staging, "root").exists())
    }

    @Test fun recoveryOnlyFillsWhatIsMissing() {
        val target = tmp.newFolder("target").also { put(it, "opt/droiddeck-apps/game/run.sh", "kept") }
        val staging = tmp.newFolder("staging").also {
            put(it, "opt/droiddeck-apps/game/run.sh", "stale")
            put(it, "opt/appimages/user/app1/app.AppImage")
        }

        val moved = RuntimeFileTree.carryUserData(staging, target, true)

        assertEquals(listOf("opt/appimages/user"), moved)
        assertEquals("kept", File(target, "opt/droiddeck-apps/game/run.sh").readText())
        assertTrue(File(target, "opt/appimages/user/app1/app.AppImage").exists())
    }
}
