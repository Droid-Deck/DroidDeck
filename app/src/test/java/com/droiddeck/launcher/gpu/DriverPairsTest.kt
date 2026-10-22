package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.gpu.GpuInfo.Family
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverPairsTest {
    private fun gpu(model: Int, adreno: Boolean = true, oneUi: Boolean = false) =
        GpuInfo("Adreno $model", model, GpuInfo.familyOf(adreno, model), "", oneUi)

    private fun asset(name: String, tag: String, linux: Boolean, pair: String) =
        TurnipReleases.Asset("src", tag, name, "https://example/$name", 3_000_000, linux, "label", "0".repeat(64), pair)

    @Test
    fun familiesFollowTheAdrenoModel() {
        assertEquals(Family.A8XX, GpuInfo.familyOf(true, 825))
        assertEquals(Family.A8XX, GpuInfo.familyOf(true, 810))
        assertEquals(Family.A7XX_LOW, GpuInfo.familyOf(true, 722))
        assertEquals(Family.A7XX, GpuInfo.familyOf(true, 740))
        assertEquals(Family.A6XX, GpuInfo.familyOf(true, 610))
        assertEquals(Family.ADRENO_UNKNOWN, GpuInfo.familyOf(true, 0))
        assertEquals(Family.NOT_ADRENO, GpuInfo.familyOf(false, 0))
    }

    @Test
    fun onlyTestedHardwareCountsAsSupported() {
        assertEquals(GpuInfo.Support.TESTED, gpu(650).support)
        assertEquals("Supported", gpu(650).supportText)
        assertEquals(
            GpuInfo.Support.UNTESTED,
            GpuInfo("Adreno 650", 650, Family.ADRENO_UNKNOWN, "", false).support,
        )
        assertEquals(GpuInfo.Support.TESTED, gpu(740).support)
        assertEquals(GpuInfo.Support.TESTED, gpu(825).support)
        assertEquals(GpuInfo.Support.UNTESTED, gpu(610).support)
        assertEquals(GpuInfo.Support.UNTESTED, gpu(660).support)
        assertEquals("Outside tested hardware (Adreno 650, 725 and newer): it may not run", gpu(660).supportText)
        assertEquals(GpuInfo.Support.UNTESTED, gpu(720).support)
        assertEquals(GpuInfo.Support.UNSUPPORTED, gpu(0, adreno = false).support)
    }

    @Test
    fun powerVrUsesItsOwnFamilyWithoutClaimingAllModelsAreTested() {
        assertEquals(Family.POWERVR, GpuInfo.familyOf(false, 0, powerVr = true))
        assertEquals(Family.NOT_ADRENO, GpuInfo.familyOf(false, 0, powerVr = false))
        assertEquals(Family.A7XX, GpuInfo.familyOf(true, 740, powerVr = true))
        val powerVr = GpuInfo("PowerVR", 0, Family.POWERVR, "", false)
        assertEquals(GpuInfo.Support.UNTESTED, powerVr.support)
        assertNull("PowerVR never downloads an Adreno driver pair", DriverPairs.recommendedKey(powerVr, emptyList()))
        val pairs = DriverPairs.from(TurnipReleases.Check(
            listOf(asset("Turnip-r4.zip", "r4", false, DriverPairs.BANNER),
                asset("Turnip-r4-Linux.zip", "r4", true, DriverPairs.BANNER)),
            emptyList(), emptyList(), 0L,
        ))
        assertFalse(pairs.single().suits(powerVr))
        assertNull(DriverPairs.recommendedKey(powerVr, pairs))
    }

    @Test
    fun autoPicksWinNativeBalancedOn8xxAndBannersElsewhere() {
        assertEquals(DriverPairs.WN_BALANCED, DriverPairs.recommendedKey(gpu(830), emptyList()))
        assertEquals("840 with no DD-Turnip release listed", DriverPairs.WN_BALANCED, DriverPairs.recommendedKey(gpu(840), emptyList()))
        assertEquals(DriverPairs.WN_BALANCED, DriverPairs.recommendedKey(gpu(825), emptyList()))
        assertEquals(DriverPairs.BANNER, DriverPairs.recommendedKey(gpu(740), emptyList()))
        assertEquals(DriverPairs.BANNER_ONEUI, DriverPairs.recommendedKey(gpu(740, oneUi = true), emptyList()))
        assertEquals(DriverPairs.BANNER_710, DriverPairs.recommendedKey(gpu(720), emptyList()))
        assertEquals(DriverPairs.BANNER, DriverPairs.recommendedKey(gpu(650), emptyList()))
        assertNull(DriverPairs.recommendedKey(gpu(0, adreno = false), emptyList()))
    }

    @Test
    fun pairsMatchHalvesAndNeverMixBannersReleases() {
        val check = TurnipReleases.Check(
            listOf(
                asset("Turnip-r4.zip", "r4", false, DriverPairs.BANNER),
                asset("Turnip-r4-Linux.zip", "r4", true, DriverPairs.BANNER),
                asset("Turnip-r4-A8xx.zip", "r4", false, DriverPairs.BANNER_A8XX),
                asset("Turnip-r3-A8xx-Linux.zip", "r3", true, DriverPairs.BANNER_A8XX),
                asset("WN-Turnip-1.19-b_Axxx.zip", "v1.19", false, DriverPairs.WN_BALANCED),
                asset("WN-Linux-Turnip-0.1.2-b_Axxx.zip", "linux-v0.1.2", true, DriverPairs.WN_BALANCED),
            ),
            emptyList(), emptyList(), 0L,
        )
        val pairs = DriverPairs.from(check).associateBy { it.key }
        assertTrue(pairs.getValue(DriverPairs.BANNER).complete)
        assertFalse("halves from two releases are not a pair", pairs.getValue(DriverPairs.BANNER_A8XX).complete)
        val wn = pairs.getValue(DriverPairs.WN_BALANCED)
        assertTrue("WinNative's two release lines pair up", wn.complete)
        assertEquals("v1.19 + v0.1.2", wn.version)
        assertTrue(wn.suits(gpu(830)))
        assertFalse(pairs.getValue(DriverPairs.BANNER).suits(gpu(830)))
    }

    @Test
    fun everyBundleReleaseIsACompletePairOnItsOwnNewestFirst() {
        fun bundle(tag: String) = TurnipReleases.Asset("DroidDeck", tag, "$tag.zip", "https://example/$tag", 6_000_000,
            false, "Android + Linux", "0".repeat(64), DriverPairs.ddTurnip(tag), bundle = true)
        val newer = bundle("DD-Turnip-v0.2.0")
        val older = bundle("DD-Turnip-v0.1.0")
        val pairs = DriverPairs.from(TurnipReleases.Check(
            listOf(asset("Turnip-r4.zip", "r4", false, DriverPairs.BANNER), newer, older,
                asset("Turnip-r4-Linux.zip", "r4", true, DriverPairs.BANNER)),
            emptyList(), emptyList(), 0L,
        ))
        assertEquals(listOf(DriverPairs.ddTurnip("DD-Turnip-v0.2.0"), DriverPairs.ddTurnip("DD-Turnip-v0.1.0"), DriverPairs.BANNER), pairs.map { it.key })
        val dd = pairs.first()
        assertTrue(dd.complete)
        assertEquals(listOf(newer), dd.assets)
        assertEquals("v0.2.0", dd.version)
        assertEquals("DroidDeck · DD-Turnip", dd.name)
        assertTrue(dd.suits(gpu(840)))
        assertTrue(dd.suits(gpu(740)))
    }

    @Test
    fun adreno840GetsTheNewestOfficialDdTurnip() {
        fun bundle(tag: String) = TurnipReleases.Asset("DroidDeck", tag, "$tag.zip", "https://example/$tag", 6_000_000,
            false, "Android + Linux", "0".repeat(64), DriverPairs.ddTurnip(tag), bundle = true)
        val pairs = DriverPairs.from(TurnipReleases.Check(
            listOf(bundle("DD-Turnip-v0.9.0"), bundle("DD-Turnip-v1.0.0"), bundle("DD-Turnip-v0.10.1"),
                asset("WN-Turnip-1.19-b_Axxx.zip", "v1.19", false, DriverPairs.WN_BALANCED),
                asset("WN-Linux-Turnip-0.1.2-b_Axxx.zip", "linux-v0.1.2", true, DriverPairs.WN_BALANCED)),
            emptyList(), emptyList(), 0L,
        ))
        assertEquals(DriverPairs.ddTurnip("DD-Turnip-v1.0.0"), DriverPairs.recommendedKey(gpu(840), pairs))
        assertEquals("other 8xx keep WinNative", DriverPairs.WN_BALANCED, DriverPairs.recommendedKey(gpu(830), pairs))
        assertEquals(DriverPairs.WN_BALANCED, DriverPairs.recommendedKey(gpu(825), pairs))
        assertEquals(DriverPairs.BANNER, DriverPairs.recommendedKey(gpu(740), pairs))
    }

    @Test
    fun maliFamiliesFollowTheOpenDriverBuilds() {
        assertEquals(Family.MALI_JM, GpuInfo.familyOf(false, 0, mali = "Mali-G57 MC2"))
        assertEquals(Family.MALI_JM, GpuInfo.familyOf(false, 0, mali = "Mali-G77"))
        assertEquals(Family.MALI_G610_G710, GpuInfo.familyOf(false, 0, mali = "Mali-G710"))
        assertEquals(Family.MALI_G615, GpuInfo.familyOf(false, 0, mali = "Mali-G615 MC6"))
        assertEquals(Family.MALI_G720, GpuInfo.familyOf(false, 0, mali = "Mali-G720-Immortalis MC12"))
        assertEquals(Family.MALI_LATEST, GpuInfo.familyOf(false, 0, mali = "Mali-G925-Immortalis MC12"))
        assertEquals(Family.MALI_OTHER, GpuInfo.familyOf(false, 0, mali = "Mali-T880"))
        assertEquals(Family.MALI_OTHER, GpuInfo.familyOf(false, 0, mali = ""))
        // An Adreno is an Adreno whatever else the device reports, and the system HAL keeps priority.
        assertEquals(Family.A7XX, GpuInfo.familyOf(true, 740, mali = "Mali-G720"))
        assertEquals(Family.POWERVR, GpuInfo.familyOf(false, 0, powerVr = true, mali = "Mali-G720"))
    }

    @Test
    fun autoPicksTheOpenDriverForEachMaliAndNothingForTheRest() {
        fun mali(name: String) = GpuInfo(name, 0, GpuInfo.familyOf(false, 0, mali = name), "", false)
        // Android 14: the per-generation builds; the newest Malis have none and use the bridge.
        assertEquals(DriverPairs.NAD_MALI_JM, DriverPairs.recommendedKey(mali("Mali-G57"), emptyList(), sdk = 34))
        assertEquals(DriverPairs.NAD_MALI_G710, DriverPairs.recommendedKey(mali("Mali-G710"), emptyList(), sdk = 34))
        assertEquals(DriverPairs.NAD_MALI_G615, DriverPairs.recommendedKey(mali("Mali-G615"), emptyList(), sdk = 34))
        assertEquals(DriverPairs.NAD_MALI_G720, DriverPairs.recommendedKey(mali("Mali-G720"), emptyList(), sdk = 34))
        assertNull(DriverPairs.recommendedKey(mali("Mali-G925-Immortalis MC12"), emptyList(), sdk = 34))
        // Android 15+: the one-binary kbase build for every Mali it covers, not the Bifrost G52/G76.
        assertEquals(DriverPairs.NAD_MALI_KBASE, DriverPairs.recommendedKey(mali("Mali-G57"), emptyList(), sdk = 35))
        assertEquals(DriverPairs.NAD_MALI_KBASE, DriverPairs.recommendedKey(mali("Mali-G720"), emptyList(), sdk = 36))
        assertEquals(DriverPairs.NAD_MALI_KBASE, DriverPairs.recommendedKey(mali("Mali-G925-Immortalis MC12"), emptyList(), sdk = 36))
        assertEquals(DriverPairs.NAD_MALI_JM, DriverPairs.recommendedKey(mali("Mali-G52 MC2"), emptyList(), sdk = 36))
        assertNull("a Mali no build covers uses the bridge", DriverPairs.recommendedKey(mali("Mali-T880"), emptyList(), sdk = 36))
        assertEquals(DriverPairs.BANNER, DriverPairs.recommendedKey(gpu(740), emptyList()))
    }

    @Test
    fun aMissingKbaseReleaseFallsBackToTheGenerationsPair() {
        val g720 = GpuInfo("Mali-G720", 0, Family.MALI_G720, "", false)
        fun bundle(id: String) = TurnipReleases.Asset("Non-Adreno", id.lowercase(), "$id-Pair.zip", "https://example/$id", 9_000_000,
            false, "label", "0".repeat(64), DriverPairs.nonAdreno(id), bundle = true)
        val onlyG720 = DriverPairs.from(TurnipReleases.Check(listOf(bundle("PanVK-Mali-G720")), emptyList(), emptyList(), 0L))
        assertEquals(DriverPairs.NAD_MALI_G720, DriverPairs.recommendedKey(g720, onlyG720, sdk = 36))
        val both = DriverPairs.from(TurnipReleases.Check(listOf(bundle("PanVK-Mali-G720"), bundle("PanVK-Mali-kbase")), emptyList(), emptyList(), 0L))
        assertEquals(DriverPairs.NAD_MALI_KBASE, DriverPairs.recommendedKey(g720, both, sdk = 36))
        assertTrue(both.single { it.key == DriverPairs.NAD_MALI_KBASE }.suits(g720))
        assertFalse(both.single { it.key == DriverPairs.NAD_MALI_KBASE }.suits(GpuInfo("Mali-G52", 0, Family.MALI_JM, "", false)))
    }

    @Test
    fun nonAdrenoPairsComeFromTheirBundlesAndSuitOnlyTheirGpu() {
        val pairs = DriverPairs.from(TurnipReleases.Check(
            listOf(
                TurnipReleases.Asset("Non-Adreno", "panvk-mali-g720", "PanVK-Mali-G720-Pair.zip", "https://example/a", 9_000_000,
                    false, "label", "0".repeat(64), DriverPairs.nonAdreno("PanVK-Mali-G720"), bundle = true),
                TurnipReleases.Asset("Non-Adreno", "radv-xclipse", "RADV-Xclipse-Pair.zip", "https://example/b", 9_000_000,
                    false, "label", "0".repeat(64), DriverPairs.nonAdreno("RADV-Xclipse"), bundle = true),
            ),
            emptyList(), emptyList(), 0L,
        ))
        val g720 = pairs.single { it.key == DriverPairs.NAD_MALI_G720 }
        val radv = pairs.single { it.key == DriverPairs.NAD_RADV_XCLIPSE }
        assertTrue(g720.complete)
        assertTrue(g720.suits(GpuInfo("Mali-G720", 0, Family.MALI_G720, "", false)))
        assertFalse(g720.suits(gpu(740)))
        assertTrue(radv.suits(GpuInfo("Xclipse", 0, Family.POWERVR, "", false)))
        assertFalse("RADV is not for a PowerVR Pixel", radv.suits(GpuInfo("PowerVR", 0, Family.POWERVR, "", false)))
        assertTrue(DriverPairs.isNonAdreno("PanVK-Mali-G720"))
        assertTrue(DriverPairs.isNonAdreno("RADV-Xclipse-2"))
        assertFalse(DriverPairs.isNonAdreno("DD-Turnip-v0.1.0"))
        assertFalse(DriverPairs.isNonAdreno(null))
    }

    @Test
    fun upstreamRadvIsAManualXclipseChoiceAndAutoKeepsOurs() {
        val asset = { id: String -> TurnipReleases.Asset("Non-Adreno", id.lowercase(), "$id-Pair.zip", "https://example/$id", 9_000_000,
            false, "label", "0".repeat(64), DriverPairs.nonAdreno(id), bundle = true) }
        val pairs = DriverPairs.from(TurnipReleases.Check(listOf(asset("RADV-Xclipse"), asset("RADV-Xclipse-upstream")), emptyList(), emptyList(), 0L))
        val xclipse = GpuInfo("Samsung Xclipse 940", 0, Family.POWERVR, "", false)
        val upstream = pairs.single { it.key == DriverPairs.NAD_RADV_XCLIPSE_UPSTREAM }
        assertEquals("nad:radv-xclipse-upstream", DriverPairs.nonAdreno("RADV-Xclipse-upstream"))
        assertTrue(upstream.complete)
        assertTrue(upstream.suits(xclipse))
        assertFalse(upstream.suits(GpuInfo("PowerVR", 0, Family.POWERVR, "", false)))
        assertEquals("RADV upstream · Samsung Xclipse", upstream.name)
        assertEquals(DriverPairs.NAD_RADV_XCLIPSE, DriverPairs.recommendedKey(xclipse, pairs))
        assertTrue(DriverPairs.isNonAdreno("RADV-Xclipse-upstream"))
    }

    @Test
    fun deviceBuildsAreOfferedOnlyForTheirMaliAndNeverByAuto() {
        fun bundle(id: String) = TurnipReleases.Asset("Non-Adreno", id.lowercase(), "$id-Pair.zip", "https://example/$id", 9_000_000,
            false, "label", "0".repeat(64), DriverPairs.nonAdreno(id), bundle = true)
        val pairs = DriverPairs.from(TurnipReleases.Check(
            listOf(bundle("PanVK-faux123-RP4Pro"), bundle("PanVK-faux123-Ace"), bundle("PanVK-faux123-RG556")),
            emptyList(), emptyList(), 0L))
        val g77 = GpuInfo("Mali-G77 MC9", 0, Family.MALI_JM, "", false)
        val g57 = GpuInfo("Mali-G57 MC2", 0, Family.MALI_JM, "", false)
        val g610 = GpuInfo("Mali-G610 MC6", 0, Family.MALI_G610_G710, "", false)
        assertTrue(pairs.single { it.key == DriverPairs.NAD_FAUX_RP4PRO }.suits(g77))
        assertFalse(pairs.single { it.key == DriverPairs.NAD_FAUX_RP4PRO }.suits(g57))
        assertTrue(pairs.single { it.key == DriverPairs.NAD_FAUX_RG556 }.suits(g57))
        assertTrue(pairs.single { it.key == DriverPairs.NAD_FAUX_ACE }.suits(g610))
        assertEquals(DriverPairs.NAD_MALI_JM, DriverPairs.recommendedKey(g77, pairs, sdk = 34))
        assertTrue(DriverPairs.isNonAdreno("PanVK-faux123-RP4Pro"))
    }

    @Test
    fun aMaliTheDeviceDoesNotNameIsKnownByItsChip() {
        assertEquals("Mali-G720 (by chip mt6989)", GpuInfo.maliOfSoc(listOf("", "mt6989")))
        assertEquals("Mali-G710 (by chip gs201)", GpuInfo.maliOfSoc(listOf("GS201")))
        assertEquals(null, GpuInfo.maliOfSoc(listOf("unknown-chip")))
        // An Exynos with a Mali, and a Helio G99, by board name.
        assertEquals(Family.MALI_JM, GpuInfo.familyOf(false, 0, mali = GpuInfo.maliOfSoc(listOf("s5e8835"))))
        assertEquals(Family.MALI_JM, GpuInfo.familyOf(false, 0, mali = GpuInfo.maliOfSoc(listOf("MT6789"))))
        assertEquals(Family.MALI_G720, GpuInfo.familyOf(false, 0, mali = "Mali-G720 (by chip mt6989)"))
        assertEquals(Family.MALI_LATEST, GpuInfo.familyOf(false, 0, mali = "Mali-G925 (by chip mt6991)"))
        assertTrue(GpuInfo.maliKbaseCovered("Mali-G720 (by chip mt6989)"))
    }

    @Test
    fun theMaliG1SeriesHasNoPanVkPairAndIsKnownByItsChip() {
        // Arch v14 (Dimensity 9500): no PanVK build is proven there, so Auto uses the phone's own driver.
        assertEquals("Mali-G1-Ultra (by chip mt6993)", GpuInfo.maliOfSoc(listOf("MT6993")))
        assertEquals(Family.MALI_OTHER, GpuInfo.familyOf(false, 0, mali = "Mali-G1-Ultra MC12"))
    }

    @Test
    fun autoTakesRadvOnAnXclipseAndLeavesPowerVrOnTheSystemDriver() {
        assertEquals(DriverPairs.NAD_RADV_XCLIPSE, DriverPairs.recommendedKey(GpuInfo("Samsung Xclipse 940", 0, Family.POWERVR, "", false), emptyList()))
        assertNull(DriverPairs.recommendedKey(GpuInfo("PowerVR", 0, Family.POWERVR, "", false), emptyList()))
    }
}
