package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.gpu.GpuInfo.Family
import com.droiddeck.launcher.gpu.GpuInfo.Support
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuDetectionTest {
    private fun info(adreno: Boolean, model: Int) =
        GpuInfo(name = "x", model = model, family = GpuInfo.familyOf(adreno, model), soc = "", oneUi8Gen2 = false)

    @Test fun modelIsParsedOnlyFromAnAdrenoName() {
        assertEquals(650, GpuInfo.threeDigits("Adreno650"))
        assertEquals(830, GpuInfo.threeDigits("Adreno (TM) 830"))
        assertEquals(740, GpuInfo.threeDigits("Adreno740v2"))
        assertEquals(618, GpuInfo.threeDigits("  adreno 618  "))
        assertEquals(750, GpuInfo.threeDigits("Adreno (TM) 750 (Qualcomm)"))
        assertEquals(0, GpuInfo.threeDigits(""))
        assertEquals(0, GpuInfo.threeDigits("   "))
        assertEquals(0, GpuInfo.threeDigits("Adreno"))
        assertEquals(0, GpuInfo.threeDigits("Adreno 65"))
        assertEquals(850, GpuInfo.threeDigits("Adreno850v2"))
        assertEquals(850, GpuInfo.threeDigits("Adreno (TM) 850"))
        assertEquals(0, GpuInfo.threeDigits("44051431"))
        assertEquals(0, GpuInfo.threeDigits("0x2a02be7"))
        assertEquals(0, GpuInfo.threeDigits("Adreno 8500"))
        assertEquals(0, GpuInfo.threeDigits("Mali-G850"))
    }

    @Test fun nonAdrenoRenderersAreNeverAdrenoWhateverNumberTheyCarry() {
        // Xclipse (Exynos), Mali and Immortalis names; familyOf ignores the number off Adreno.
        assertEquals(Family.NOT_ADRENO, GpuInfo.familyOf(false, 0))
        assertEquals(Family.NOT_ADRENO, GpuInfo.familyOf(false, GpuInfo.threeDigits("Samsung Xclipse 920")))
        assertEquals(Family.NOT_ADRENO, GpuInfo.familyOf(false, GpuInfo.threeDigits("Xclipse 940")))
        assertEquals(Family.NOT_ADRENO, GpuInfo.familyOf(false, GpuInfo.threeDigits("Mali-G720-Immortalis MC12")))
        assertEquals(Family.NOT_ADRENO, GpuInfo.familyOf(false, GpuInfo.threeDigits("Mali-G715")))
        assertEquals(Family.NOT_ADRENO, GpuInfo.familyOf(false, 830))
    }

    @Test fun familyBoundaries() {
        assertEquals(Family.ADRENO_UNKNOWN, GpuInfo.familyOf(true, 0))
        assertEquals(Family.ADRENO_UNKNOWN, GpuInfo.familyOf(true, 540))
        assertEquals(Family.ADRENO_UNKNOWN, GpuInfo.familyOf(true, 599))
        for (m in listOf(600, 618, 619, 640, 650, 660, 690)) assertEquals("$m", Family.A6XX, GpuInfo.familyOf(true, m))
        for (m in listOf(710, 720, 722)) assertEquals("$m", Family.A7XX_LOW, GpuInfo.familyOf(true, m))
        for (m in listOf(700, 702, 725, 730, 740, 750, 780)) assertEquals("$m", Family.A7XX, GpuInfo.familyOf(true, m))
        for (m in listOf(800, 825, 829, 830, 840, 999)) assertEquals("$m", Family.A8XX, GpuInfo.familyOf(true, m))
    }

    @Test fun supportTiers() {
        assertEquals(Support.TESTED, info(true, 650).support)
        assertEquals(Support.UNTESTED, info(true, 640).support)
        assertEquals(Support.UNTESTED, info(true, 618).support)
        assertEquals(Support.UNTESTED, info(true, 660).support)
        assertEquals(Support.UNTESTED, info(true, 720).support)
        assertEquals(Support.UNTESTED, info(true, 722).support)
        assertEquals(Support.UNTESTED, info(true, 0).support)
        assertEquals(Support.TESTED, info(true, 725).support)
        assertEquals(Support.TESTED, info(true, 740).support)
        assertEquals(Support.TESTED, info(true, 750).support)
        assertEquals(Support.TESTED, info(true, 830).support)
        assertEquals(Support.UNTESTED, info(true, 850).support)
        assertEquals(Support.UNSUPPORTED, info(false, 0).support)
        assertEquals(Support.UNSUPPORTED, info(false, 830).support)
    }

    @Test fun userFacingTextPerTier() {
        assertEquals("Supported", info(true, 830).supportText)
        assertEquals("Experimental Adreno 850 support: requires compatible experimental display and game drivers; not verified on this device", info(true, 850).supportText)
        assertEquals("Experimental: its drivers are test builds", info(true, 720).supportText)
        assertEquals("Outside tested hardware (Adreno 650, 725 and newer): it may not run", info(true, 618).supportText)
        assertEquals("Not supported: DroidDeck needs an Adreno (Snapdragon) GPU", info(false, 0).supportText)
        assertEquals("Not an Adreno GPU", Family.NOT_ADRENO.label)
        assertEquals("Adreno 8xx", Family.A8XX.label)
    }

    private fun mali(paths: Set<String> = emptySet(), props: Map<String, String> = emptyMap(), vulkan: String? = null) =
        GpuInfo.maliEvidence({ it in paths }, { props[it] ?: "" }, vulkan)

    @Test fun maliNeedsPositiveEvidence() {
        assertFalse(mali())
        assertFalse(mali(props = mapOf("ro.hardware.egl" to "adreno", "ro.hardware.vulkan" to "adreno")))
        assertFalse(mali(vulkan = "Samsung Xclipse 940"))
        assertFalse(mali(vulkan = "Adreno (TM) 740"))
        assertFalse(mali(paths = setOf("/sys/class/kgsl/kgsl-3d0", "/vendor/lib64/hw/vulkan.adreno.so")))
        for (path in listOf("/dev/mali0", "/sys/class/misc/mali0", "/vendor/lib64/egl/libGLES_mali.so", "/vendor/lib64/hw/vulkan.mali.so"))
            assertTrue(path, mali(paths = setOf(path)))
        assertTrue(mali(props = mapOf("ro.hardware.egl" to "mali")))
        assertTrue(mali(props = mapOf("ro.hardware.vulkan" to " Mali ")))
        assertFalse(mali(props = mapOf("ro.hardware.egl" to "meow")))
        assertTrue(mali(vulkan = "Mali-G715"))
        assertTrue(mali(vulkan = "Mali-G720-Immortalis MC12"))
        assertTrue(mali(vulkan = "Immortalis-G925"))
    }

    @Test fun adrenoAlwaysWinsOverMaliEvidence() {
        assertEquals(Family.MALI, GpuInfo.familyOf(false, 0, mali = true))
        assertEquals(Family.NOT_ADRENO, GpuInfo.familyOf(false, 0, mali = false))
        assertEquals(Family.A7XX, GpuInfo.familyOf(true, 740, mali = true))
        assertEquals(Family.ADRENO_UNKNOWN, GpuInfo.familyOf(true, 0, mali = true))
        assertFalse(Family.MALI.isAdreno)
        assertFalse(Family.NOT_ADRENO.isAdreno)
        for (f in listOf(Family.A6XX, Family.A7XX, Family.A7XX_LOW, Family.A8XX, Family.ADRENO_UNKNOWN)) assertTrue("$f", f.isAdreno)
    }

    @Test fun maliIsExperimentalAndNotAdrenoStaysUnsupported() {
        val mali = GpuInfo("Mali-G715", 0, Family.MALI, "", false)
        assertEquals(Support.UNTESTED, mali.support)
        assertEquals("Experimental Mali support: the Linux side renders in software, so games are slow and many will not run", mali.supportText)
        assertEquals("Arm Mali (experimental)", Family.MALI.label)
        assertEquals(Support.UNSUPPORTED, info(false, 0).support)
        assertEquals("Not supported: DroidDeck needs an Adreno (Snapdragon) GPU", info(false, 0).supportText)
    }

    @Test fun platformCodeNamesIncludingCaseAndUnknowns() {
        assertEquals(640, GpuInfo.platformModel("msmnile"))
        assertEquals(650, GpuInfo.platformModel("kona"))
        assertEquals(660, GpuInfo.platformModel("LAHAINA"))
        assertEquals(730, GpuInfo.platformModel("taro"))
        assertEquals(730, GpuInfo.platformModel("cape"))
        assertEquals(830, GpuInfo.platformModel("Sun"))
        assertEquals(0, GpuInfo.platformModel(""))
        assertEquals(0, GpuInfo.platformModel("exynos2400"))
        assertEquals(0, GpuInfo.platformModel(" kona "))
    }
}
