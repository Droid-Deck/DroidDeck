package com.droiddeck.launcher.gpu

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class TurnipReleasesCarryOverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val a644 = GpuInfo("Adreno 644", 644, GpuInfo.familyOf(true, 644), "", false)

    private fun asset(source: String, tag: String, name: String, pair: String, linux: Boolean = false, bundle: Boolean = false) =
        JSONObject().put("source", source).put("tag", tag).put("name", name).put("url", "https://example/$name").put("size", 1L)
            .put("linux", linux).put("label", "label").put("sha256", "0".repeat(64)).put("pair", pair).put("bundle", bundle)

    private val dd = asset("DroidDeck", "v0.4.0", "DD-Turnip-v0.4.0.zip", DriverPairs.ddTurnip("v0.4.0"), bundle = true)
    private fun banner(tag: String) = listOf(asset("Banners-Turnip", tag, "Turnip-$tag.zip", DriverPairs.BANNER),
        asset("Banners-Turnip", tag, "Turnip-$tag-Linux.zip", DriverPairs.BANNER, linux = true))

    private fun previous() = JSONObject().put("assets", JSONArray().put(dd).apply { banner("r5").forEach(::put) })
        .put("latest", JSONArray().put(JSONObject().put("source", "DroidDeck").put("tag", "v0.4.0"))
            .put(JSONObject().put("source", "Banners-Turnip").put("tag", "r5")))

    private fun pick(assets: JSONArray, latest: JSONArray, failed: List<String>): String? {
        val stored = JSONObject().put("assets", assets).put("latest", latest).put("failed", JSONArray(failed)).put("checkedAt", 1L)
        context.getSharedPreferences("turnip_releases", Context.MODE_PRIVATE).edit().putString("latest", stored.toString()).commit()
        return DriverPairs.recommendedKey(a644, DriverPairs.from(TurnipReleases.cached(context)))
    }

    @Test fun aRepoThatFailedToAnswerKeepsItsDriversFromTheLastCheck() {
        val assets = JSONArray().apply { banner("r6").forEach(::put) }
        val latest = JSONArray().put(JSONObject().put("source", "Banners-Turnip").put("tag", "r6"))
        TurnipReleases.carryOver(listOf("DroidDeck"), previous(), assets, latest)
        assertEquals(DriverPairs.ddTurnip("v0.4.0"), pick(assets, latest, listOf("DroidDeck")))
        assertEquals(listOf("r6", "r6", "v0.4.0"), (0 until assets.length()).map { assets.getJSONObject(it).getString("tag") })
        assertEquals(listOf("Banners-Turnip" to "r6", "DroidDeck" to "v0.4.0"),
            (0 until latest.length()).map { latest.getJSONObject(it).let { l -> l.getString("source") to l.getString("tag") } })
    }

    @Test fun reposThatAnsweredAreNotDuplicatedOrRevived() {
        val assets = JSONArray().apply { banner("r6").forEach(::put) }
        val latest = JSONArray()
        TurnipReleases.carryOver(emptyList(), previous(), assets, latest)
        assertEquals(2, assets.length())
        assertEquals(DriverPairs.BANNER, pick(assets, latest, emptyList()))
    }

    @Test fun withNoEarlierCheckAFailedRepoContributesNothing() {
        val assets = JSONArray().apply { banner("r6").forEach(::put) }
        val latest = JSONArray()
        TurnipReleases.carryOver(listOf("DroidDeck"), null, assets, latest)
        assertEquals(2, assets.length())
        assertEquals(0, latest.length())
    }
}
