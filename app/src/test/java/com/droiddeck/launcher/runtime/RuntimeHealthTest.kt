package com.droiddeck.launcher.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeHealthTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun link(root: File, name: String, target: String) =
        Files.createSymbolicLink(File(root, "usr/lib/$name").toPath(), Paths.get(target))

    @Test fun reportsOnlyLibraryLinksWhoseTargetIsMissingInsideTheRuntime() {
        val root = temporary.root
        val lib = File(root, "usr/lib").apply { mkdirs() }
        File(lib, "libvulkan.so.1.4.357").writeText("lib")
        File(root, "etc").mkdirs()
        File(root, "etc/ld.conf").writeText("conf")
        link(root, "libvulkan.so.1", "libvulkan.so.1.4.357")
        link(root, "libvulkan.so", "libvulkan.so.1")
        link(root, "libabsolute.so", "/etc/ld.conf")
        link(root, "libharfbuzz.so.0", "libharfbuzz.so.0.61460.0")
        link(root, "libchain.so", "libharfbuzz.so.0")
        link(root, "libhost.so", File(lib, "libvulkan.so.1.4.357").absolutePath)

        assertEquals(listOf("libchain.so", "libharfbuzz.so.0", "libhost.so"), RuntimeHealth.brokenLibraryLinks(root))
    }

    @Test fun aMissingLibraryFolderIsNotReportedAsDamage() {
        assertEquals(emptyList<String>(), RuntimeHealth.brokenLibraryLinks(temporary.root))
    }
}
