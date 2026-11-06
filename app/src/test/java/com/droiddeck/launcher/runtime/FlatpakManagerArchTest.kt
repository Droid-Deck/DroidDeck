package com.droiddeck.launcher.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FlatpakManagerArchTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("flatpak-arch").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun install(arch: String, branch: String) {
        File(dir, "$arch/$branch/active").mkdirs()
    }

    @Test fun armDevicesAlsoListX86Builds() {
        assertEquals(listOf("aarch64", "x86_64"), FlatpakManager.arches("aarch64"))
        assertEquals(listOf("x86_64"), FlatpakManager.arches("x86_64"))
    }

    @Test fun anX86OnlyInstallIsFound() {
        install("x86_64", "master")
        val (arch, branch) = FlatpakManager.installedBranch(dir, "aarch64")!!
        assertEquals("x86_64", arch)
        assertEquals("master", branch.name)
    }

    @Test fun theNativeBuildWinsWhenBothAreInstalled() {
        install("x86_64", "master")
        install("aarch64", "stable")
        assertEquals("aarch64", FlatpakManager.installedBranch(dir, "aarch64")!!.first)
    }

    @Test fun aBranchWithoutAnActiveDeployIsNotInstalled() {
        File(dir, "x86_64/master").mkdirs()
        assertNull(FlatpakManager.installedBranch(dir, "aarch64"))
    }

    @Test fun x86BuildsOnArmLaunchWithTheirArch() {
        val x86 = FlatpakManager.App("io.github.ebkr.r2modman", "r2modman", null, "master", null, "x86_64")
        val arm = FlatpakManager.App("org.example.App", "App", null, "stable", null, "aarch64")
        assertEquals(listOf("--arch=x86_64", "io.github.ebkr.r2modman"), FlatpakManager.launchArgs(x86, "aarch64"))
        assertEquals(listOf("org.example.App"), FlatpakManager.launchArgs(arm, "aarch64"))
    }
}
