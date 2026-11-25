package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.gpu.GpuInfo.Family
import com.droiddeck.launcher.gpu.GpuInfo.Support
import org.junit.Assert.assertEquals
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
        assertEquals(740, GpuInfo.threeDigits("Adreno A32"))
        assertEquals(740, GpuInfo.threeDigits("Adreno (TM) A32"))
        assertEquals(740, GpuInfo.threeDigits("AdrenoA32v2"))
        assertEquals(722, GpuInfo.threeDigits("Adreno A22"))
        assertEquals(710, GpuInfo.threeDigits("Adreno (TM) A12"))
        assertEquals(619, GpuInfo.threeDigits("Adreno A21"))
        assertEquals(610, GpuInfo.threeDigits("Adreno A11"))
        assertEquals(0, GpuInfo.threeDigits("Adreno A99"))
    }

    @Test fun nonAdrenoRenderersAreNeverAdrenoWhateverNumberTheyCarry() {
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

    @Test fun adreno722IsSupportedOnlyOnTheRetroidPocketDuo() {
        assertEquals(true, GpuInfo.verifiedOn(722, "Retroid Pocket Duo"))
        assertEquals(false, GpuInfo.verifiedOn(722, "Retroid Pocket 5"))
        assertEquals(false, GpuInfo.verifiedOn(720, "Retroid Pocket Duo"))
        assertEquals(false, GpuInfo.verifiedOn(740, "Retroid Pocket Duo"))
        val duo = info(true, 722).copy(verifiedDevice = true)
        assertEquals(Support.TESTED, duo.support)
        assertEquals("Supported", duo.supportText)
        assertEquals(Family.A7XX_LOW, duo.family)
        assertEquals(Support.UNTESTED, info(true, 722).support)
    }

    @Test fun userFacingTextPerTier() {
        assertEquals("Supported", info(true, 830).supportText)
        assertEquals("Experimental Adreno 850 support: requires compatible experimental display and game drivers; not verified on this device", info(true, 850).supportText)
        assertEquals("Experimental: its drivers are test builds", info(true, 720).supportText)
        assertEquals("Outside tested hardware (Adreno 650, 725 and newer): it may not run", info(true, 618).supportText)
        assertEquals("Not supported: no compatible graphics backend is available for this GPU", info(false, 0).supportText)
        assertEquals("Not an Adreno GPU", Family.NOT_ADRENO.label)
        assertEquals("Adreno 8xx", Family.A8XX.label)
    }

    @Test fun platformCodeNamesIncludingCaseAndUnknowns() {
        assertEquals(640, GpuInfo.platformModel("msmnile"))
        assertEquals(650, GpuInfo.platformModel("kona"))
        assertEquals(660, GpuInfo.platformModel("LAHAINA"))
        assertEquals(730, GpuInfo.platformModel("taro"))
        assertEquals(730, GpuInfo.platformModel("cape"))
        assertEquals(735, GpuInfo.platformModel("cliff"))
        assertEquals(830, GpuInfo.platformModel("Sun"))
        assertEquals(840, GpuInfo.platformModel("canoe"))
        assertEquals(840, GpuInfo.platformModel("Canoe"))
        assertEquals(825, GpuInfo.platformModel("tuna"))
        assertEquals(732, GpuInfo.platformModel("cliffs"))
        assertEquals(810, GpuInfo.platformModel("volcano"))
        assertEquals(720, GpuInfo.platformModel("crow"))
        assertEquals(725, GpuInfo.platformModel("ukee"))
        assertEquals(642, GpuInfo.platformModel("yupik"))
        assertEquals(620, GpuInfo.platformModel("lito"))
        assertEquals(618, GpuInfo.platformModel("atoll"))
        assertEquals(710, GpuInfo.platformModel("parrot"))
        assertEquals(619, GpuInfo.platformModel("holi"))
        assertEquals(610, GpuInfo.platformModel("khaje"))
        assertEquals(610, GpuInfo.platformModel("bengal"))
        assertEquals(610, GpuInfo.platformModel("trinket"))
        assertEquals(0, GpuInfo.platformModel(""))
        assertEquals(0, GpuInfo.platformModel("exynos2400"))
        assertEquals(0, GpuInfo.platformModel(" kona "))
    }

    @Test fun maliDetectionCoversImmortalisAndChipFallbacksWithoutNode() {
        assertEquals(
            "Immortalis-G720 MC12",
            GpuInfo.detectMali(adreno = false, powerVr = false, maliNode = false, vulkanDevice = "Immortalis-G720 MC12", kbaseName = null, socIds = emptyList()),
        )
        assertEquals(
            "Mali-G720 (by chip mt6989)",
            GpuInfo.detectMali(adreno = false, powerVr = false, maliNode = false, vulkanDevice = null, kbaseName = null, socIds = listOf("MT6989")),
        )
        assertEquals(
            "",
            GpuInfo.detectMali(adreno = false, powerVr = false, maliNode = true, vulkanDevice = null, kbaseName = null, socIds = emptyList()),
        )
        assertEquals(
            null,
            GpuInfo.detectMali(adreno = false, powerVr = false, maliNode = false, vulkanDevice = null, kbaseName = null, socIds = listOf("unknown")),
        )
        assertEquals(
            null,
            GpuInfo.detectMali(adreno = true, powerVr = false, maliNode = true, vulkanDevice = "Mali-G720", kbaseName = "Mali-G720", socIds = listOf("mt6989")),
        )
        assertEquals(
            null,
            GpuInfo.detectMali(adreno = false, powerVr = true, maliNode = true, vulkanDevice = "Mali-G720", kbaseName = "Mali-G720", socIds = listOf("mt6989")),
        )
    }
}
