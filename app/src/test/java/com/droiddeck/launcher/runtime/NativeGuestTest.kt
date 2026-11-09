package com.droiddeck.launcher.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeGuestTest {
    private fun rootWithLoader(): File = Files.createTempDirectory("native-root").toFile().also {
        File(it, "usr/lib").mkdirs()
        File(it, "usr/lib/ld-linux-aarch64.so.1").writeText("")
    }

    @Test
    fun commandRunsTheProgramThroughTheRootfsLoader() {
        val root = rootWithLoader()
        val command = NativeGuest.command(root, "/usr/bin/env", listOf("-i", "A=1"))
        assertEquals(File(root, "usr/lib/ld-linux-aarch64.so.1").path, command[0])
        assertEquals(listOf("--argv0", "env", File(root, "usr/bin/env").path, "-i", "A=1"), command.drop(1))
    }

    @Test
    fun mountsWithoutADestinationMapToThemselves() {
        val root = rootWithLoader()
        val env = NativeGuest.sessionEnvironment(root, listOf("/dev", "/a:/b"))
        assertEquals("/dev:/dev|/a:/b", env["DDNATV_BINDS"])
        assertEquals(root.path, env["DDNATV_ROOT"])
        assertTrue(env.getValue("LD_PRELOAD").replace(File.separatorChar, '/').endsWith("usr/lib/libddnatv-hook.so"))
    }

    private val linkRoot = "/data/user/0/app/files/linuxfs"
    private val sdBinds = listOf(
        "/mnt/media_rw/CARD/files/steam:/mnt/droiddeck-sd",
        "/data/user/0/app/files/steam-libraries/x/steamapps/compatdata:/mnt/droiddeck-sd/steamapps/compatdata",
        "/storage/emulated/0",
    )

    @Test
    fun aProotDriveLinkToTheSdLibraryPointsAtTheCard() {
        assertEquals("/mnt/media_rw/CARD/files/steam", NativeGuest.hostLinkTarget("/mnt/droiddeck-sd", linkRoot, sdBinds))
        assertEquals("/data/user/0/app/files/steam-libraries/x/steamapps/compatdata/1",
            NativeGuest.hostLinkTarget("/mnt/droiddeck-sd/steamapps/compatdata/1", linkRoot, sdBinds))
    }

    @Test
    fun aRootDriveLinkPointsAtTheRootfs() {
        assertEquals(linkRoot, NativeGuest.hostLinkTarget("/", linkRoot, sdBinds))
    }

    @Test
    fun linksTheKernelAlreadyFollowsStayAsTheyAre() {
        assertEquals(null, NativeGuest.hostLinkTarget(linkRoot, linkRoot, sdBinds))
        assertEquals(null, NativeGuest.hostLinkTarget("/mnt/media_rw/CARD/files/steam", linkRoot, sdBinds))
        assertEquals(null, NativeGuest.hostLinkTarget("/storage/emulated/0/Games", linkRoot, sdBinds))
        assertEquals(null, NativeGuest.hostLinkTarget("../drive_c", linkRoot, sdBinds))
    }

    @Test
    fun aLinkIntoAnIdentityMountStaysAsItIs() {
        val binds = sdBinds + listOf("/odd/place:/odd/place", "/bare/mount")
        assertEquals(null, NativeGuest.hostLinkTarget("/odd/place/games", linkRoot, binds))
        assertEquals(null, NativeGuest.hostLinkTarget("/bare/mount", linkRoot, binds))
    }

    @Test
    fun aMountInsideAnIdentityMountStillMapsItsPaths() {
        val binds = listOf("/odd/place", "/real/sd:/odd/place/sd")
        assertEquals("/real/sd/x", NativeGuest.hostLinkTarget("/odd/place/sd/x", linkRoot, binds))
    }
}
