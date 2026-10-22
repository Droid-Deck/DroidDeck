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

    private fun asset(name: String, tag: String, linux: Boolean, pair: String, pairKind: String = pair) =
        TurnipReleases.Asset("src", tag, name, "https://example/$name", 3_000_000, linux, "label", "0".repeat(64), pair,
            pairKind = pairKind)

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
    fun sourceQualifiedPairsRetainTheirHardwareClassification() {
        val key = "${DriverPairs.BANNER_A8XX}:github-owner-turnip"
        val pairs = DriverPairs.from(TurnipReleases.Check(
            listOf(
                asset("Turnip-r4-A8xx.zip", "r4", false, key, DriverPairs.BANNER_A8XX),
                asset("Turnip-r4-A8xx-Linux.zip", "r4", true, key, DriverPairs.BANNER_A8XX),
                asset("Turnip-r4-A8xx.zip", "r4", false, DriverPairs.BANNER_A8XX),
                asset("Turnip-r4-A8xx-Linux.zip", "r4", true, DriverPairs.BANNER_A8XX),
            ), emptyList(), emptyList(), 0L,
        )).associateBy { it.key }
        val custom = pairs.getValue(key)
        assertTrue(custom.complete)
        assertTrue(custom.suits(gpu(830)))
        assertFalse(custom.suits(gpu(650)))
        assertFalse(custom.suits(gpu(740)))
        assertEquals(pairs.getValue(DriverPairs.BANNER_A8XX).families, custom.families)
        assertEquals("Banners-Turnip · Adreno 8xx", custom.name)
    }

    @Test
    fun sourceQualifiedPairsNeverMixSourcesOrBannersReleases() {
        val first = "${DriverPairs.BANNER_A8XX}:github-owner-one"
        val second = "${DriverPairs.BANNER_A8XX}:github-owner-two"
        val pairs = DriverPairs.from(TurnipReleases.Check(
            listOf(
                asset("Turnip-r4-A8xx.zip", "r4", false, first, DriverPairs.BANNER_A8XX),
                asset("Turnip-r3-A8xx-Linux.zip", "r3", true, first, DriverPairs.BANNER_A8XX),
                asset("Turnip-r4-A8xx-Linux.zip", "r4", true, second, DriverPairs.BANNER_A8XX),
                asset("Turnip-r4-A8xx-Linux.zip", "r4", true, DriverPairs.BANNER_A8XX),
            ), emptyList(), emptyList(), 0L,
        )).associateBy { it.key }
        assertEquals(3, pairs.size)
        assertTrue(pairs.values.none { it.complete })
        assertNull(pairs.getValue(first).linux)
        assertNull(pairs.getValue(second).display)
        assertNull(pairs.getValue(DriverPairs.BANNER_A8XX).display)
    }

    @Test
    fun unknownAndConflictingClassificationsDoNotClaimCompatibility() {
        val pairs = DriverPairs.from(TurnipReleases.Check(
            listOf(
                asset("Turnip-r4-unknown.zip", "r4", false, "banner-unknown:github-owner-one", "banner-unknown"),
                asset("Turnip-r4-A8xx.zip", "r4", false, "conflict:github-owner-one", DriverPairs.BANNER_A8XX),
                asset("Turnip-r4-Linux.zip", "r4", true, "conflict:github-owner-one", DriverPairs.BANNER),
            ), emptyList(), emptyList(), 0L,
        ))
        for (pair in pairs) {
            assertTrue(pair.families.isEmpty())
            assertFalse(pair.suits(gpu(650)))
            assertFalse(pair.suits(gpu(830)))
            assertFalse(pair.suits(gpu(0)))
        }
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
    fun adreno840RanksSourceQualifiedDdTurnipByItsUnqualifiedVersion() {
        fun bundle(tag: String, sourceId: String): TurnipReleases.Asset {
            val kind = DriverPairs.ddTurnip(tag)
            return TurnipReleases.Asset(
                "Custom", tag, "$tag.zip", "https://example/$tag", 6_000_000, false,
                "Android + Linux", "0".repeat(64), "$kind:$sourceId", bundle = true, pairKind = kind,
            )
        }
        val pairs = DriverPairs.from(TurnipReleases.Check(
            listOf(bundle("DD-Turnip-v1.2.3", "a".repeat(64)), bundle("DD-Turnip-v2.0.0", "b".repeat(64))),
            emptyList(), emptyList(), 0L,
        ))
        assertEquals(
            "${DriverPairs.ddTurnip("DD-Turnip-v2.0.0")}:${"b".repeat(64)}",
            DriverPairs.recommendedKey(gpu(840), pairs),
        )
    }

    @Test
    fun adreno840IgnoresDdTurnipWithAnOversizedVersionComponent() {
        fun bundle(tag: String) = TurnipReleases.Asset(
            "DroidDeck", tag, "$tag.zip", "https://example/$tag", 6_000_000, false,
            "Android + Linux", "0".repeat(64), DriverPairs.ddTurnip(tag), bundle = true,
        )
        val valid = bundle("DD-Turnip-v2.0.0")
        val oversized = bundle("DD-Turnip-v9223372036854775808.0.0")
        val pairs = DriverPairs.from(TurnipReleases.Check(listOf(oversized, valid), emptyList(), emptyList(), 0L))
        assertNull(DriverPairs.ddTurnipVersion(oversized.pairKind))
        assertEquals(valid.pair, DriverPairs.recommendedKey(gpu(840), pairs))
    }

    @Test
    fun adreno840ComparesLargeDdTurnipVersionsWithoutPackingOverflow() {
        fun bundle(tag: String) = TurnipReleases.Asset(
            "DroidDeck", tag, "$tag.zip", "https://example/$tag", 6_000_000, false,
            "Android + Linux", "0".repeat(64), DriverPairs.ddTurnip(tag), bundle = true,
        )
        val newer = bundle("DD-Turnip-v9223373.0.0")
        val older = bundle("DD-Turnip-v1.999999.999999")
        val pairs = DriverPairs.from(TurnipReleases.Check(listOf(older, newer), emptyList(), emptyList(), 0L))
        assertEquals(newer.pair, DriverPairs.recommendedKey(gpu(840), pairs))
    }
}
