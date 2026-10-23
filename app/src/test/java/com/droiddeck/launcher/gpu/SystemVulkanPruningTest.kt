package com.droiddeck.launcher.gpu

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SystemVulkanPruningTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun completedReplacementCollectsOnlyManagedOldDirectories() {
        val base = temporary.newFolder("system_vulkan")
        val current = completeRuntime(base, "a".repeat(64))
        val old = completeRuntime(base, "b".repeat(64))
        val stage = abandonedStage(base)
        val unrelated = File(base, "notes").apply { mkdir(); File(this, "keep").writeText("keep") }

        SystemVulkanDriver.pruneUnused(current, true)

        assertEquals("current ICD", File(current, "icd.json").readText())
        assertTrue(File(current, "run-proton.py").canExecute())
        assertFalse(old.exists())
        assertFalse(stage.exists())
        assertEquals("keep", File(unrelated, "keep").readText())
    }

    @Test fun aSurvivingGuestKeepsOldVersionsButNotAbandonedStages() {
        val base = temporary.newFolder("system_vulkan")
        val current = completeRuntime(base, "a".repeat(64))
        val old = completeRuntime(base, "b".repeat(64))
        val stage = abandonedStage(base)

        SystemVulkanDriver.pruneUnused(current, false)

        assertTrue(current.isDirectory)
        assertTrue(old.isDirectory)
        assertFalse(stage.exists())
        SystemVulkanDriver.pruneUnused(current, true)
        assertFalse("collect the old version once guests stop", old.exists())
    }

    @Test fun failedReplacementPreservesThePreviousGoodRuntime() {
        val base = temporary.newFolder("system_vulkan")
        val current = File(base, "a".repeat(64))
        val old = completeRuntime(base, "b".repeat(64))
        val stage = abandonedStage(base)

        SystemVulkanDriver.pruneUnused(current, true)
        assertTrue("missing replacement", old.isDirectory)
        assertFalse(stage.exists())

        current.mkdir()
        File(current, "icd.json").writeText("incomplete")
        SystemVulkanDriver.pruneUnused(current, true)
        assertEquals("current ICD", File(old, "icd.json").readText())
    }

    @Test fun missingBundledMaliFilePreservesPreviousRuntime() {
        val base = temporary.newFolder("system_vulkan")
        val current = completeRuntime(base, "a".repeat(64))
        val old = completeRuntime(base, "b".repeat(64))
        File(current, "dxvk-mali/aarch64-windows/dxgi.dll").delete()

        SystemVulkanDriver.pruneUnused(current, true)

        assertTrue("incomplete replacement must preserve the old runtime", old.isDirectory)
    }

    @Test fun deletingOldRuntimeDoesNotFollowItsSymlinks() {
        val base = temporary.newFolder("system_vulkan")
        val current = completeRuntime(base, "a".repeat(64))
        val old = File(base, "b".repeat(64)).apply { mkdir() }
        val outside = temporary.newFolder("outside")
        val sentinel = File(outside, "keep").apply { writeText("keep") }
        Files.createSymbolicLink(File(old, "outside").toPath(), outside.toPath())

        SystemVulkanDriver.pruneUnused(current, true)

        assertFalse(old.exists())
        assertEquals("keep", sentinel.readText())
    }

    @Test fun linkedBaseOrCurrentRuntimePreventsCleanup() {
        val base = temporary.newFolder("system_vulkan")
        val old = completeRuntime(base, "b".repeat(64))
        val current = File(base, "a".repeat(64))
        Files.createSymbolicLink(current.toPath(), old.toPath())
        SystemVulkanDriver.pruneUnused(current, true)
        assertTrue(old.isDirectory)

        Files.delete(current.toPath())
        completeRuntime(base, current.name)
        val alias = File(temporary.root, "alias")
        Files.createSymbolicLink(alias.toPath(), base.toPath())
        SystemVulkanDriver.pruneUnused(File(alias, current.name), true)
        assertTrue(old.isDirectory)
    }

    @Test fun interruptedCurrentRuntimeIsQuarantinedWithoutTouchingOtherEntries() {
        val base = temporary.newFolder("system_vulkan")
        val current = File(base, "a".repeat(64)).apply {
            mkdir()
            File(this, "partial").writeText("partial")
        }
        val old = completeRuntime(base, "b".repeat(64))
        val unrelated = File(base, "notes").apply { mkdir(); File(this, "keep").writeText("keep") }

        val quarantined = SystemVulkanDriver.quarantineIncompleteTarget(current, true)

        assertFalse(current.exists())
        assertTrue(quarantined!!.name.startsWith(".stage-"))
        assertEquals("partial", File(quarantined, "partial").readText())
        assertEquals("current ICD", File(old, "icd.json").readText())
        assertEquals("keep", File(unrelated, "keep").readText())
    }

    @Test fun survivingGuestKeepsInterruptedCurrentRuntimeInPlace() {
        val base = temporary.newFolder("system_vulkan")
        val current = File(base, "a".repeat(64)).apply { mkdir(); File(this, "partial").writeText("partial") }

        try {
            SystemVulkanDriver.quarantineIncompleteTarget(current, false)
            fail("active guests must prevent replacement")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message!!.contains("still in use"))
        }
        assertEquals("partial", File(current, "partial").readText())
        assertEquals(listOf(current.name), base.list()!!.toList())
    }

    @Test fun recoveryRejectsSymlinksAndUnmanagedLocations() {
        val base = temporary.newFolder("target-link-parent", "system_vulkan")
        val incomplete = temporary.newFolder("incomplete")
        val linkedCurrent = File(base, "a".repeat(64))
        Files.createSymbolicLink(linkedCurrent.toPath(), incomplete.toPath())
        assertRecoveryRejected(linkedCurrent)
        assertTrue(incomplete.isDirectory)

        val realBase = temporary.newFolder("real-system-vulkan")
        val linkedBase = File(temporary.newFolder("base-link-parent"), "system_vulkan")
        Files.createSymbolicLink(linkedBase.toPath(), realBase.toPath())
        val throughLinkedBase = File(linkedBase, "b".repeat(64)).apply { mkdir() }
        assertRecoveryRejected(throughLinkedBase)
        assertTrue(throughLinkedBase.isDirectory)

        val unrelatedBase = temporary.newFolder("unrelated")
        val unrelated = File(unrelatedBase, "c".repeat(64)).apply { mkdir(); File(this, "keep").writeText("keep") }
        assertRecoveryRejected(unrelated)
        assertEquals("keep", File(unrelated, "keep").readText())
    }

    private fun assertRecoveryRejected(target: File) {
        try {
            SystemVulkanDriver.quarantineIncompleteTarget(target, true)
            fail("unsafe path must be rejected: $target")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message!!.contains("Unsafe"))
        }
    }

    private fun abandonedStage(base: File) =
        File(base, ".stage-12345678-1234-1234-1234-123456789abc").apply { mkdir(); File(this, "partial").writeText("partial") }

    private fun completeRuntime(base: File, version: String): File {
        val directory = File(base, version).apply { mkdir() }
        val components = listOf(
            "lib/libhybris-vulkan-icd.so.0", "lib/libhybris-common.so.1", "lib/libhybris/linker/q.so",
            "lib/libVkLayer_hybris_compat.so", "run-proton.py",
            "lib/libVkLayer_DROIDDECK_placed_memory.so", "lib/VkLayer_DROIDDECK_placed_memory.json",
            "wsi/libVkLayer_window_system_integration.so", "wsi/VkLayer_window_system_integration.json",
        ) + listOf("x32", "x64").flatMap { arch ->
            listOf("d3d8", "d3d9", "d3d10core", "d3d11", "dxgi").map { "dxvk/$arch/$it.dll" }
        }
        val mali = listOf("i386-windows", "x86_64-windows", "aarch64-windows").flatMap { arch ->
            listOf("d3d8", "d3d9", "d3d10core", "d3d11", "dxgi").map { "dxvk-mali/$arch/$it.dll" }
        }
        (components + mali).forEach { path -> File(directory, path).apply { parentFile!!.mkdirs(); writeText("component") } }
        File(directory, "run-proton.py").setExecutable(true)
        File(directory, "icd.json").writeText("current ICD")
        return directory
    }
}
