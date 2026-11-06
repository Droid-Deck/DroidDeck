package com.droiddeck.launcher.gpu

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SystemVulkanSelectionTest {
    @Test fun defaultPowerVrUsesTheSystemBridge() {
        assertTrue(SystemVulkanDriver.usesDefault(true, ""))
        assertTrue(SystemVulkanDriver.usesDefault(true, null))
    }

    @Test fun otherGpusAndExplicitImportsKeepTheirDriver() {
        assertFalse(SystemVulkanDriver.usesDefault(false, ""))
        assertFalse(SystemVulkanDriver.usesDefault(false, null))
        assertFalse(SystemVulkanDriver.usesDefault(true, "custom-driver"))
    }
    @Test fun renderNodeRequiresARealCharacterDeviceAndBothAccessModes() {
        assertTrue(SystemVulkanDriver.renderNodeAvailable(true, true, true))
        assertFalse("a regular placeholder is not a DRM device", SystemVulkanDriver.renderNodeAvailable(false, true, true))
        assertFalse("read access is required", SystemVulkanDriver.renderNodeAvailable(true, false, true))
        assertFalse("write access is required", SystemVulkanDriver.renderNodeAvailable(true, true, false))
        assertFalse("a missing node is unavailable", SystemVulkanDriver.renderNodeAvailable(false, false, false))
    }

    @Test fun renderNodeDriverMustIdentifyPowerVrExactly() {
        for (name in listOf("pvrsrvkm", "pvr", "powervr"))
            assertTrue(name, SystemVulkanDriver.powerVrDriver(name))
        for (name in listOf(null, "", "msm", "panfrost", "not-pvr"))
            assertFalse(name, SystemVulkanDriver.powerVrDriver(name))
    }

    @Test fun samsungXclipseGpuNodeIsSelectedOverItsDisplayNode() {
        assertTrue(SystemVulkanDriver.powerVrDriver("sgpu"))
        assertFalse("the display controller is not the GPU", SystemVulkanDriver.powerVrDriver("exynos-drm"))
        // Galaxy A56 (Exynos 1580): the GPU owns renderD128, the display controller renderD129.
        val drivers = mapOf("/dev/dri/renderD128" to "sgpu", "/dev/dri/renderD129" to "exynos-drm")
        assertEquals("/dev/dri/renderD128", SystemVulkanDriver.findRenderNode(null) { path ->
            SystemVulkanDriver.powerVrDriver(drivers[path])
        })
    }

    @Test fun renderNodeIdentityUsesTheDriverLinkOrUevent() {
        val device = Files.createTempDirectory("powervr-device").toFile()
        try {
            assertFalse("missing identity is unavailable", SystemVulkanDriver.powerVrDevice(device))
            val uevent = File(device, "uevent")
            uevent.writeText("DRIVER=pvrsrvkm\nOF_NAME=gpu0\n")
            assertTrue(SystemVulkanDriver.powerVrDevice(device))
            uevent.writeText("DRIVER=msm\nOF_COMPATIBLE_0=not-pvr\n")
            assertFalse(SystemVulkanDriver.powerVrDevice(device))
            val driver = File(device, "driver").toPath()
            Files.createSymbolicLink(driver, File("../../drivers/pvrsrvkm").toPath())
            assertTrue(SystemVulkanDriver.powerVrDevice(device))
            Files.delete(driver)
            Files.createSymbolicLink(driver, File("../../drivers/msm").toPath())
            uevent.writeText("DRIVER=pvrsrvkm\n")
            assertFalse("an unrelated driver link must not fall back", SystemVulkanDriver.powerVrDevice(device))
        } finally {
            device.deleteRecursively()
        }
    }

    @Test fun renderNodeDiscoverySkipsOtherDriversAndDeniedNodes() {
        val drivers = mapOf(
            "/dev/dri/renderD128" to "msm",
            "/dev/dri/renderD129" to "pvr",
            "/dev/dri/renderD130" to "pvrsrvkm",
        )
        assertEquals("/dev/dri/renderD129", SystemVulkanDriver.findRenderNode(null) { path ->
            SystemVulkanDriver.powerVrDriver(drivers[path])
        })
        assertEquals("/dev/dri/renderD130", SystemVulkanDriver.findRenderNode(null) { path ->
            SystemVulkanDriver.powerVrDriver(drivers[path]) &&
                SystemVulkanDriver.renderNodeAvailable(true, path != "/dev/dri/renderD129", true)
        })
    }

    @Test fun renderNodesAreDiscoveredWithoutDirectoryListing() {
        for (minor in listOf(128, 129, 191)) {
            val expected = "/dev/dri/renderD$minor"
            assertEquals(expected, SystemVulkanDriver.findRenderNode(null) { it == expected })
        }
        assertNull(SystemVulkanDriver.findRenderNode(null) { false })
    }

    @Test fun listedRenderNodesCoverExtendedMinorNumbers() {
        assertEquals("/dev/dri/renderD256", SystemVulkanDriver.findRenderNode(
            arrayOf("card0", "renderD256", "renderD128/device", "../renderD129"),
        ) { path ->
            assertTrue(path, path.matches(Regex("/dev/dri/renderD[0-9]+")))
            path == "/dev/dri/renderD256"
        })
    }

    @Test fun defaultPowerVrResolvesToTheSystemIcd() {
        assertEquals("system/icd.json", LinuxVulkanDriver.resolveIcdPath("", true,
            { "system/icd.json" }, { error("default must not resolve an import") }))
    }

    @Test fun defaultAdrenoLeavesTheRuntimeIcdUnchanged() {
        assertNull(LinuxVulkanDriver.resolveIcdPath("", false,
            { error("Adreno must not prepare the system bridge") }, { error("default must not resolve an import") }))
    }

    @Test fun missingManualPowerVrImportFailsWithoutSwitchingBackends() {
        val failure = assertThrows(IllegalStateException::class.java) {
            LinuxVulkanDriver.resolveIcdPath("missing-driver", true,
                { error("manual preference must not silently switch to the system backend") }, { null })
        }
        assertTrue(failure.message!!.contains("missing-driver"))
        assertTrue(failure.message!!.contains("use Auto"))
    }

    @Test fun missingAdrenoImportKeepsTheExistingRuntimeFallback() {
        assertNull(LinuxVulkanDriver.resolveIcdPath("missing-driver", false,
            { error("Adreno must not prepare the system bridge") }, { null }))
    }

    @Test fun manualPowerVrResolvesTheChosenImport() {
        assertEquals("imports/chosen/icd.json", LinuxVulkanDriver.resolveIcdPath("chosen", true,
            { error("manual must not prepare the system bridge") }, { "imports/$it/icd.json" }))
    }
}
