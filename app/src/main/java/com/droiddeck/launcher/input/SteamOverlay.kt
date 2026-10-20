package com.droiddeck.launcher.input

import org.json.JSONObject

internal object SteamOverlay {
    // Steam's keyboard and menus share Gamescope's interactive overlay protocol.
    fun hasInput(snapshot: JSONObject?): Boolean {
        val displays = snapshot?.optJSONObject("displays") ?: return false
        for (display in displays.keys()) {
            val windows = displays.optJSONArray(display) ?: continue
            for (index in 0 until windows.length()) {
                val window = windows.optJSONObject(index) ?: continue
                if (window.optString("map") == "viewable" &&
                    window.optInt("overlay") > 0 && window.optInt("inputFocus") > 0 &&
                    window.optInt("externalOverlay") == 0 && !window.optBoolean("inputOnly") &&
                    window.optLong("opacity", 0xffff_ffffL) > 0
                ) return true
            }
        }
        return false
    }
}
