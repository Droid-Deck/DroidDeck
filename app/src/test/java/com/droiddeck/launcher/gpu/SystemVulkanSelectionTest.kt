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
    @Test fun halProfilesRequireAnUnambiguousKnownHal() {
        for (profile in SystemVulkanDriver.Profile.values()) {
            assertEquals(profile, SystemVulkanDriver.Profile.fromHal(profile.halPath))
            assertEquals(profile, SystemVulkanDriver.findProfile { it == profile.halPath })
        }
        assertNull(SystemVulkanDriver.Profile.fromHal(null))
        assertNull(SystemVulkanDriver.Profile.fromHal("/vendor/lib64/hw/vulkan.other.so"))
        assertNull(SystemVulkanDriver.findProfile { false })
        assertNull(SystemVulkanDriver.findProfile { true })
    }

    @Test fun drmIdentityCannotCrossVendorProfiles() {
        assertFalse(SystemVulkanDriver.renderNodeDriver(SystemVulkanDriver.Profile.POWERVR, "sgpu"))
        assertFalse(SystemVulkanDriver.renderNodeDriver(SystemVulkanDriver.Profile.XCLIPSE, "pvr"))
        assertFalse(SystemVulkanDriver.renderNodeDriver(SystemVulkanDriver.Profile.MALI, "pvr"))
        assertFalse(SystemVulkanDriver.renderNodeDriver(null, "pvr"))
        assertFalse(SystemVulkanDriver.renderNodeDriver(SystemVulkanDriver.Profile.POWERVR, null))
    }

    @Test fun profileCapabilitiesMatchSessionAndComponentPolicy() {
        for (profile in SystemVulkanDriver.Profile.values()) {
            val env = mutableListOf<String>()
            SystemVulkanDriver.addEnvironment(File("/runtime"), profile.halPath,
                if (profile.requiresRenderNode) "/dev/dri/renderD128" else null, 37, env)
            assertTrue(env.contains("DROIDDECK_SYSTEM_VULKAN_PRESENTATION=" + profile.presentation))
            val dxvk = if (profile.dxvkPayload == null) "user" else "managed"
            assertTrue(env.contains("DROIDDECK_SYSTEM_VULKAN_DXVK=$dxvk"))
            assertTrue(env.contains("DROIDDECK_HYBRIS_SESSION_LAYERS=" + profile.sessionLayers))
            if (profile.dxvkPayload == null) assertFalse(env.any { it.startsWith("DROIDDECK_HYBRIS_DXVK=") })
            else assertTrue(env.contains("DROIDDECK_HYBRIS_DXVK=" + profile.dxvkPayload))
        }
    }

    @Test fun maliUsesItsHalWithoutPowerVrDrmOrGlOverrides() {
        val env = mutableListOf<String>()
        SystemVulkanDriver.addEnvironment(File("/runtime"), "/vendor/lib64/hw/vulkan.mali.so", null, 37, env)
        assertTrue(env.contains("HYBRIS_VULKAN_HAL=/vendor/lib64/hw/vulkan.mali.so"))
        assertTrue(env.contains("DROIDDECK_SYSTEM_VULKAN_GPU=mali"))
        assertTrue(env.contains("DROIDDECK_SYSTEM_VULKAN_RUNTIME=/runtime"))
        assertFalse(env.any { it.startsWith("DROIDDECK_POWERVR_RUNTIME=") })
        assertTrue(env.contains("BL_GAMESCOPE_BACKEND=sdl"))
        assertTrue(env.contains("VK_INSTANCE_LAYERS=VK_LAYER_HYBRIS_compat"))
        assertFalse(env.any { it.startsWith("BL_POWERVR_") || it.startsWith("BL_STEAM_GL_PRESENT=") })
        assertFalse(env.any { it.startsWith("BL_STEAM_CEF_SOFTWARE=") })
        // The outer compositor uses Wayland; the guest helpers scope X11 WSI to clients.
        assertFalse(env.any { it.startsWith("WSI_X11_FORCE_SHM=") })
    }

    @Test fun powerVrUsesSoftwareSteamUiAndKeepsHardwareGamePolicy() {
        val env = mutableListOf<String>()
        SystemVulkanDriver.addEnvironment(File("/runtime"), "/vendor/lib64/hw/vulkan.powervr.so", "/dev/dri/renderD130", 37, env)
        assertTrue(env.contains("BL_POWERVR_RENDER_NODE=/dev/dri/renderD130"))
        assertTrue(env.contains("BL_POWERVR_DRM_HANDLES=1"))
        assertTrue(env.contains("BL_STEAM_GL_PRESENT=1"))
        assertTrue(env.contains("BL_STEAM_CEF_SOFTWARE=1"))
        assertTrue(env.contains("DROIDDECK_SYSTEM_VULKAN_PRESENTATION=native"))
        assertTrue(env.contains("DROIDDECK_HYBRIS_DXVK=dxvk"))
        assertTrue(env.contains("DROIDDECK_SYSTEM_VULKAN_GPU=powervr"))
        assertThrows(IllegalStateException::class.java) {
            SystemVulkanDriver.addEnvironment(File("/runtime"), "/vendor/lib64/hw/vulkan.powervr.so", null, 37, mutableListOf())
        }
    }

    @Test fun xclipseUsesSharedCompatibilityWithItsPayloadAndDrmProfile() {
        val env = mutableListOf<String>()
        SystemVulkanDriver.addEnvironment(File("/runtime"), "/vendor/lib64/hw/vulkan.samsung.so", "/dev/dri/renderD128", 37, env)
        assertTrue(env.contains("DROIDDECK_SYSTEM_VULKAN_GPU=xclipse"))
        assertTrue(env.contains("VK_INSTANCE_LAYERS=VK_LAYER_HYBRIS_compat"))
        assertTrue(env.contains("DROIDDECK_HYBRIS_SESSION_LAYERS=VK_LAYER_HYBRIS_compat"))
        assertTrue(env.contains("DROIDDECK_HYBRIS_LAYERS=VK_LAYER_HYBRIS_compat"))
        assertTrue(env.contains("VK_LAYER_PATH=/runtime/lib"))
        assertFalse(env.any { it.startsWith("DROIDDECK_HYBRIS_LAYER_PATH=") })
        assertFalse(env.any { it.contains("DROIDDECK_xclipse") || it.contains("droiddeck-xclipse") })
        assertTrue(env.contains("HYBRIS_BC_TEXTURES=0"))
        assertTrue(env.contains("DROIDDECK_HYBRIS_SESSION_BC_TEXTURES=0"))
        assertTrue(env.contains("DROIDDECK_HYBRIS_DXVK=dxvk-2.6.2"))
        assertFalse(env.any { it.startsWith("BL_STEAM_CEF_SOFTWARE=") })
        assertTrue(env.contains("BL_POWERVR_RENDER_NODE=/dev/dri/renderD128"))
        assertTrue(env.contains("BL_POWERVR_DRM_HANDLES=1"))
        assertTrue(env.contains("BL_STEAM_GL_PRESENT=1"))
        assertTrue(env.contains("DROIDDECK_POWERVR_RUNTIME=/runtime"))
        assertThrows(IllegalStateException::class.java) {
            SystemVulkanDriver.addEnvironment(File("/runtime"), "/vendor/lib64/hw/vulkan.samsung.so", null, 37, mutableListOf())
        }
    }

    @Test fun otherProfilesRetainTheirSessionBcPolicy() {
        for (profile in listOf(SystemVulkanDriver.Profile.POWERVR, SystemVulkanDriver.Profile.MALI)) {
            val env = mutableListOf<String>()
            SystemVulkanDriver.addEnvironment(File("/runtime"), profile.halPath,
                if (profile.requiresRenderNode) "/dev/dri/renderD128" else null, 37, env)
            assertFalse(env.any { it.startsWith("HYBRIS_BC_TEXTURES=") })
            assertFalse(env.any { it.startsWith("DROIDDECK_HYBRIS_SESSION_BC_TEXTURES=") })
        }
    }

    @Test fun maliAutomaticallySelectsManagedPayloadWithoutChangingPresentation() {
        val env = mutableListOf<String>()
        SystemVulkanDriver.addEnvironment(File("/runtime"), "/vendor/lib64/hw/vulkan.mali.so", null, 37, env)
        assertTrue(env.contains("DROIDDECK_HYBRIS_DXVK=dxvk-mali"))
        assertTrue(env.contains("DXVK_MALI_COMPAT=1"))
        assertTrue(env.contains("DROIDDECK_SYSTEM_VULKAN_DXVK=managed"))
        assertTrue(env.contains("DROIDDECK_SYSTEM_VULKAN_PRESENTATION=x11-shm"))
        assertFalse(env.any { it.startsWith("BL_POWERVR_DRM_HANDLES=") })
        assertFalse(env.any { it.startsWith("DROIDDECK_POWERVR_RUNTIME=") })
    }

    @Test fun xclipseAutomaticProfileDoesNotEnableMaliCompatibility() {
        val env = mutableListOf<String>()
        SystemVulkanDriver.addEnvironment(File("/runtime"), "/vendor/lib64/hw/vulkan.samsung.so", "/dev/dri/renderD128", 37, env)
        assertTrue(env.contains("DROIDDECK_HYBRIS_DXVK=dxvk-2.6.2"))
        assertFalse(env.any { it.startsWith("DXVK_MALI_COMPAT=") })
    }

    @Test fun unknownHalCannotActivateTheSystemProfile() {
        assertThrows(IllegalStateException::class.java) {
            SystemVulkanDriver.addEnvironment(File("/runtime"), "/vendor/lib64/hw/vulkan.other.so", null, 37, mutableListOf())
        }
    }

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
            assertTrue(name, SystemVulkanDriver.renderNodeDriver(SystemVulkanDriver.Profile.POWERVR, name))
        for (name in listOf(null, "", "msm", "panfrost", "not-pvr"))
            assertFalse(name, SystemVulkanDriver.renderNodeDriver(SystemVulkanDriver.Profile.POWERVR, name))
    }

    @Test fun samsungXclipseGpuNodeIsSelectedOverItsDisplayNode() {
        assertTrue(SystemVulkanDriver.renderNodeDriver(SystemVulkanDriver.Profile.XCLIPSE, "sgpu"))
        assertFalse("the display controller is not the GPU", SystemVulkanDriver.renderNodeDriver(SystemVulkanDriver.Profile.XCLIPSE, "exynos-drm"))
        // Galaxy A56 (Exynos 1580): the GPU owns renderD128, the display controller renderD129.
        val drivers = mapOf("/dev/dri/renderD128" to "sgpu", "/dev/dri/renderD129" to "exynos-drm")
        assertEquals("/dev/dri/renderD128", SystemVulkanDriver.findRenderNode(null) { path ->
            SystemVulkanDriver.renderNodeDriver(SystemVulkanDriver.Profile.XCLIPSE, drivers[path])
        })
    }

    @Test fun renderNodeIdentityUsesTheDriverLinkOrUevent() {
        val device = Files.createTempDirectory("powervr-device").toFile()
        try {
            assertFalse("missing identity is unavailable", SystemVulkanDriver.renderNodeDevice(device, SystemVulkanDriver.Profile.POWERVR))
            val uevent = File(device, "uevent")
            uevent.writeText("DRIVER=pvrsrvkm\nOF_NAME=gpu0\n")
            assertTrue(SystemVulkanDriver.renderNodeDevice(device, SystemVulkanDriver.Profile.POWERVR))
            uevent.writeText("DRIVER=msm\nOF_COMPATIBLE_0=not-pvr\n")
            assertFalse(SystemVulkanDriver.renderNodeDevice(device, SystemVulkanDriver.Profile.POWERVR))
            val driver = File(device, "driver").toPath()
            Files.createSymbolicLink(driver, File("../../drivers/pvrsrvkm").toPath())
            assertTrue(SystemVulkanDriver.renderNodeDevice(device, SystemVulkanDriver.Profile.POWERVR))
            Files.delete(driver)
            Files.createSymbolicLink(driver, File("../../drivers/msm").toPath())
            uevent.writeText("DRIVER=pvrsrvkm\n")
            assertFalse("an unrelated driver link must not fall back", SystemVulkanDriver.renderNodeDevice(device, SystemVulkanDriver.Profile.POWERVR))
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
            SystemVulkanDriver.renderNodeDriver(SystemVulkanDriver.Profile.POWERVR, drivers[path])
        })
        assertEquals("/dev/dri/renderD130", SystemVulkanDriver.findRenderNode(null) { path ->
            SystemVulkanDriver.renderNodeDriver(SystemVulkanDriver.Profile.POWERVR, drivers[path]) &&
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
