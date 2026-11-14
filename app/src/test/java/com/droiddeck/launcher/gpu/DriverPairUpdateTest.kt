package com.droiddeck.launcher.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DriverPairUpdateTest {
    private fun pair(sha: String, id: Long, updated: String, key: String = DriverPairs.nonAdreno("PanVK-Mali-G615")) =
        TurnipReleases.Asset("Non-Adreno", "panvk-mali-g615", "PanVK-Mali-G615-Pair.zip", "https://example/p", 9_000_000, false,
            "label", sha, key, bundle = true, assetId = id, updatedAt = updated)

    @Test fun aRebuildUnderTheSameTagIsAnUpdate() {
        val context = RuntimeEnvironment.getApplication()
        val installed = pair("a".repeat(64), 1, "2026-10-13T10:00:00Z")
        assertFalse("nothing installed yet", TurnipReleases.updateAvailable(context, installed))
        TurnipReleases.recordDownload(context, installed, "PanVK-Mali-G615")
        assertFalse(TurnipReleases.updateAvailable(context, installed))
        assertEquals("2026-10-13", TurnipReleases.installedBuiltOn(context, installed))
        assertTrue(TurnipReleases.updateAvailable(context, pair("b".repeat(64), 2, "2026-10-15T10:00:00Z")))
        assertTrue("a new asset id alone", TurnipReleases.updateAvailable(context, pair("a".repeat(64), 2, "2026-10-13T10:00:00Z")))
        assertEquals("2026-10-15", pair("b".repeat(64), 2, "2026-10-15T10:00:00Z").builtOn)
    }

    @Test fun adrenoPairsAreNeverFlagged() {
        val context = RuntimeEnvironment.getApplication()
        val turnip = pair("a".repeat(64), 1, "2026-10-13T10:00:00Z", key = DriverPairs.BANNER)
        TurnipReleases.recordDownload(context, turnip, "Turnip-x")
        assertFalse(TurnipReleases.updateAvailable(context, pair("b".repeat(64), 2, "2026-10-15T10:00:00Z", key = DriverPairs.BANNER)))
    }
}
