package com.droiddeck.launcher.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AutoPicksTest {
    private fun gpu(model: Int) = GpuInfo("Adreno $model", model, GpuInfo.familyOf(true, model), "", false)

    private fun bundle(tag: String) = TurnipReleases.Asset("DroidDeck", tag, "$tag.zip", "https://example/$tag", 6_000_000,
        false, "Android + Linux", "0".repeat(64), DriverPairs.ddTurnip(tag), bundle = true)

    private fun half(name: String, tag: String, linux: Boolean, pair: String) =
        TurnipReleases.Asset("src", tag, name, "https://example/$name", 3_000_000, linux, "label", "0".repeat(64), pair)

    private val pairs = DriverPairs.from(TurnipReleases.Check(
        listOf(
            bundle("DD-Turnip-v0.3.0"),
            half("Turnip-r6.zip", "r6", false, DriverPairs.BANNER),
            half("Turnip-r6-Linux.zip", "r6", true, DriverPairs.BANNER),
        ),
        emptyList(), emptyList(), 0L,
    ))

    private val manifest = """
        {"schemaVersion": 1, "rules": [
          {"families": ["A7XX"], "models": [740], "blobMin": 700, "prefer": ["banner"]},
          {"families": ["A7XX", "A6XX"], "prefer": ["dd-turnip", "banner"]},
          {"families": ["A9XX"], "prefer": ["banner"]}
        ]}
    """.trimIndent()

    @Test fun theManifestOrdersPreferencesAndTheNewestDdTurnipResolves() {
        val rules = AutoPicks.parse(manifest)!!
        assertEquals(2, rules.size)
        assertEquals(DriverPairs.ddTurnip("DD-Turnip-v0.3.0"), DriverPairs.recommendedKey(gpu(650), pairs, rules))
    }

    @Test fun aReleaseMarkedBadFallsThroughToTheNextPreference() {
        val rules = AutoPicks.parse(manifest)!!
        val dd = pairs.first { it.key.startsWith(DriverPairs.DD_TURNIP) }
        assertEquals(DriverPairs.BANNER, DriverPairs.recommendedKey(gpu(650), pairs, rules, bad = setOf(AutoDriverTrial.release(dd))))
    }

    @Test fun systemDriverRulesOnlyMatchAKnownBlob() {
        val rules = AutoPicks.parse(manifest)!!
        assertEquals(DriverPairs.BANNER, DriverPairs.recommendedKey(gpu(740), pairs, rules, blob = 744))
        assertEquals(DriverPairs.ddTurnip("DD-Turnip-v0.3.0"), DriverPairs.recommendedKey(gpu(740), pairs, rules, blob = 676))
        assertEquals(DriverPairs.ddTurnip("DD-Turnip-v0.3.0"), DriverPairs.recommendedKey(gpu(740), pairs, rules, blob = null))
        assertEquals(744, AutoPicks.blobVersion("512.744.0"))
    }

    @Test fun aGpuTheManifestDoesNotCoverUsesTheBuiltInTable() {
        assertEquals(DriverPairs.WN_BALANCED, DriverPairs.recommendedKey(gpu(830), pairs, AutoPicks.parse(manifest)!!))
    }

    @Test fun anotherSchemaOrNoUsableRulesIsIgnored() {
        assertNull(AutoPicks.parse("""{"schemaVersion": 2, "rules": [{"families": ["A7XX"], "prefer": ["banner"]}]}"""))
        assertNull(AutoPicks.parse("""{"schemaVersion": 1, "rules": [{"families": ["A9XX"], "prefer": ["banner"]}]}"""))
        assertNull(AutoPicks.parse("not json"))
    }
}
