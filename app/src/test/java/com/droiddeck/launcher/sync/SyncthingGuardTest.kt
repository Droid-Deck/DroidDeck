package com.droiddeck.launcher.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class SyncthingGuardTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun setup(): Triple<SyncthingGuard, File, File> {
        val data = tmp.newFolder("data")
        val external = tmp.newFolder("external")
        val home = File(external, "Sync").apply { mkdirs() }
        val config = File(data, "files/syncthing").apply { mkdirs() }
        return Triple(SyncthingGuard(home, listOf(data, external), listOf(config)), data, external)
    }

    @Test
    fun allowsFoldersInsideAppStorage() {
        val (guard, data, external) = setup()
        assertTrue(guard.allows(File(external, "Sync/Game saves").path))
        assertTrue(guard.allows(File(data, "files/linux/home").path))
        assertTrue(guard.allows("~/Shared"))
        assertTrue(guard.allows(external.path))
    }

    @Test
    fun blocksFoldersOutsideAppStorage() {
        val (guard, _, _) = setup()
        assertFalse(guard.allows(tmp.newFolder("DCIM").path))
        assertFalse(guard.allows("/storage/emulated/0/Download"))
        assertFalse(guard.allows(""))
        assertFalse(guard.allows("relative/path"))
    }

    @Test
    fun blocksSyncthingsOwnConfig() {
        val (guard, data, _) = setup()
        assertFalse(guard.allows(File(data, "files/syncthing").path))
        assertFalse(guard.allows(File(data, "files/syncthing/keys").path))
    }

    @Test
    fun blocksFoldersHoldingSyncthingsConfig() {
        val (guard, data, _) = setup()
        assertFalse(guard.allows(data.path))
        assertFalse(guard.allows(File(data, "files").path))
    }

    @Test
    fun blocksSiblingWithSharedPrefix() {
        val (guard, _, external) = setup()
        assertFalse(guard.allows(external.path + "-other"))
    }

    @Test
    fun blocksSymlinkPointingOutside() {
        val (guard, _, external) = setup()
        val outside = tmp.newFolder("outside")
        val link = File(external, "Sync/link")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertFalse(guard.allows(link.path))
        assertFalse(guard.allows(File(link, "child").path))
    }
}
