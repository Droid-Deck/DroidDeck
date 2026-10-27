package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftwareGpuEnvironmentTest {
    private fun env(icd: String?) = SessionService.softwareGpuEnvironment(icd).associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test fun maliGuestsDrawWithLlvmpipeAndNeverForceZink() {
        val env = env("/data/rootfs/usr/share/vulkan/icd.d/lvp_icd.aarch64.json")
        assertEquals("1", env["LIBGL_ALWAYS_SOFTWARE"])
        assertEquals("llvmpipe", env["GALLIUM_DRIVER"])
        assertEquals("1", env["LIBGL_DRI3_DISABLE"])
        assertEquals("1", env["BL_X64_SOFTWARE"])
        assertEquals("1", env["BL_SOFTWARE_GPU"])
        assertFalse(env.containsKey("MESA_LOADER_DRIVER_OVERRIDE"))
        assertFalse(env.containsKey("LIBGL_KOPPER_DRI2"))
        assertTrue(env.values.none { "zink" in it || "freedreno" in it })
    }

    @Test fun vulkanUsesTheRuntimesLavapipeWhenItShipsOne() {
        val icd = "/data/rootfs/usr/share/vulkan/icd.d/lvp_icd.aarch64.json"
        assertEquals(icd, env(icd)["VK_ICD_FILENAMES"])
        assertEquals(icd, env(icd)["VK_DRIVER_FILES"])
        assertFalse(env(null).containsKey("VK_ICD_FILENAMES"))
        assertFalse(env(null).containsKey("VK_DRIVER_FILES"))
    }
}
