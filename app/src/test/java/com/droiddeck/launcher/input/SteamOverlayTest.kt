package com.droiddeck.launcher.input

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [33])
class SteamOverlayTest {
    @Test fun mappedInteractiveSteamOverlayOwnsInput() {
        assertTrue(SteamOverlay.hasInput(snapshot()))
    }

    @Test fun notificationsAndHiddenWindowsDoNotSuppressControls() {
        for (change in listOf(
            "inputFocus" to 0, "overlay" to 0, "opacity" to 0,
            "map" to "unmapped", "map" to "unviewable", "inputOnly" to true,
            "externalOverlay" to 1,
        )) {
            assertFalse(change.toString(), SteamOverlay.hasInput(snapshot(change)))
        }
    }

    @Test fun pointerOnlyInteractiveOverlaysAlsoNeedUnobstructedTouch() {
        assertTrue(SteamOverlay.hasInput(snapshot("inputFocus" to 2)))
    }

    @Test fun absentOpacityMeansOpaqueAsInGamescope() {
        val snapshot = snapshot()
        snapshot.getJSONObject("displays").getJSONArray(":0").getJSONObject(0).remove("opacity")
        assertTrue(SteamOverlay.hasInput(snapshot))
    }

    @Test fun missingOrEmptySnapshotsLeaveControlsAvailable() {
        for (snapshot in listOf(null, JSONObject(), JSONObject("""{"displays":{":0":[]}}"""))) {
            assertFalse(SteamOverlay.hasInput(snapshot))
        }
    }

    private fun snapshot(change: Pair<String, Any>? = null): JSONObject {
        val window = JSONObject("""{"map":"viewable","overlay":1,"inputFocus":1,"opacity":4294967295}""")
        change?.let { window.put(it.first, it.second) }
        return JSONObject().put("displays", JSONObject().put(":0", org.json.JSONArray().put(window)))
    }
}
