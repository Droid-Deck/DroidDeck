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
    fun theSeccompSwitchNamesAHelperAndItsArgumentsInTheRootfs() {
        val root = Files.createTempDirectory("rootfs").toFile()
        assertEquals(null, NativeGuest.simulator(root))
        File(root, "usr/local/bin").mkdirs()
        File(root, "usr/local/bin/seccomp-sim").writeText("")
        File(root, "etc/droiddeck").mkdirs()
        File(root, "etc/droiddeck/seccomp-sim").writeText("/usr/local/bin/seccomp-sim -f /etc/droiddeck/app.bpf\n")
        assertEquals(listOf(File(root, "usr/local/bin/seccomp-sim").path, "-f", File(root, "etc/droiddeck/app.bpf").path),
            NativeGuest.simulator(root))
        File(root, "etc/droiddeck/seccomp-sim").writeText("/usr/local/bin/missing")
        assertEquals(null, NativeGuest.simulator(root))
    }

    @Test
    fun aWorkingDirectoryInAMountIsItsSource() {
        val root = Files.createTempDirectory("rootfs").toFile()
        val storage = Files.createTempDirectory("storage").toFile()
        File(root, "root").mkdirs()
        // a mount spec is "src:dst"; Windows' drive letters cannot be written in one
        org.junit.Assume.assumeFalse(storage.path.contains(':'))
        val binds = listOf("${storage.path}:/root/Storage", "/proc")
        assertEquals(storage, NativeGuest.workingDirectory(root, binds, "/root/Storage"))
        assertEquals(File(root, "root"), NativeGuest.workingDirectory(root, binds, "/root"))
        assertEquals(root, NativeGuest.workingDirectory(root, binds, "/nowhere"))
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
    fun aDriveLinkToTheCardGetsItsGuestPathBack() {
        assertEquals("/mnt/droiddeck-sd", NativeGuest.guestLinkTarget("/mnt/media_rw/CARD/files/steam", linkRoot, sdBinds))
        assertEquals("/mnt/droiddeck-sd/steamapps/compatdata/1",
            NativeGuest.guestLinkTarget("/data/user/0/app/files/steam-libraries/x/steamapps/compatdata/1", linkRoot, sdBinds))
    }

    @Test
    fun aDriveLinkIntoTheRootfsGetsItsGuestPathBack() {
        assertEquals("/", NativeGuest.guestLinkTarget(linkRoot, linkRoot, sdBinds))
        assertEquals("/root/Games", NativeGuest.guestLinkTarget("$linkRoot/root/Games", linkRoot, sdBinds))
    }

    @Test
    fun guestPathsStayAsTheyAre() {
        assertEquals(null, NativeGuest.guestLinkTarget("/mnt/droiddeck-sd", linkRoot, sdBinds))
        assertEquals(null, NativeGuest.guestLinkTarget("/", linkRoot, sdBinds))
        assertEquals(null, NativeGuest.guestLinkTarget("../drive_c", linkRoot, sdBinds))
        assertEquals(null, NativeGuest.guestLinkTarget("/storage/emulated/0/Games", linkRoot, sdBinds))
    }

    @Test
    fun aSiblingOfAMountSourceIsNotPartOfIt() {
        assertEquals(null, NativeGuest.guestLinkTarget("/mnt/media_rw/CARD/files/steamx", linkRoot, sdBinds))
        assertEquals(null, NativeGuest.guestLinkTarget("${linkRoot}x/root", linkRoot, sdBinds))
    }

    @Test
    fun theFirstOfMountsSharingASourceIsTheGuestPath() {
        val binds = sdBinds + "/data/user/0/app/files/steam-libraries/x/steamapps/compatdata:/mnt/bannerlator-sd/steamapps/compatdata"
        assertEquals("/mnt/droiddeck-sd/steamapps/compatdata/2",
            NativeGuest.guestLinkTarget("/data/user/0/app/files/steam-libraries/x/steamapps/compatdata/2", linkRoot, binds))
    }
}
