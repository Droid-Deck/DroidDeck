package com.droiddeck.launcher.stores.ubisoft

import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreLaunch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class UbisoftTest {
    private val steamConfig = "version: 2.0\r\nroot:\r\n  name: Assassin's Creed Syndicate\r\n  start_game:\r\n    steam:\r\n      steam_app_id: 368500\r\n" +
        "  third_party_platform:\r\n    name: Steam\r\n    override_uplayid: 1875\r\n"

    @Test fun aProductUbisoftDeliversThroughSteamNamesItsSteamApp() {
        assertEquals("368500", UbisoftLibrary.steamApp(steamConfig))
        assertNull(UbisoftLibrary.steamApp("root:\n  start_game:\n    online:\n      executables:\n        - path:\n            relative: bin/game.exe\n"))
    }

    @Test fun steamOwnedGamesAreKeyedByTheirSpace() {
        val owned = JSONObject().put("games", JSONArray()
            .put(JSONObject().put("productId", 1957).put("spaceId", "space-s").put("productType", 0).put("owned", true).put("configuration", steamConfig))
            .put(JSONObject().put("productId", 109).put("spaceId", "space-sc").put("productType", 0).put("owned", true).put("manifest", "AB").put("configuration", "root: {}"))
            .put(JSONObject().put("productId", 1961).put("spaceId", "space-addon").put("productType", 1).put("owned", true).put("configuration", steamConfig)))
        assertEquals(mapOf("space-s" to "368500"), UbisoftLibrary.steamBySpace(owned))
        assertEquals(setOf("space-sc"), UbisoftLibrary.ownedBySpace(owned).keys)
    }

    @Test fun theEntitlementNamesTheGamesApplication() {
        val body = JSONObject().put("items", JSONArray()
            .put(JSONObject().put("productId", 82).put("applicationId", "app-82"))
            .put(JSONObject().put("productId", 109).put("applicationId", "app-109"))).toString()
        assertEquals("app-109", UbisoftLaunch.applicationId(body, 109))
        assertNull(UbisoftLaunch.applicationId(body, 1651))
    }

    @Test fun theLauncherStartsTheServiceOnlyWithAFreshLaunchFile() {
        val text = StoreLaunch.launcherText(StoreGameSidecar(Store.UBISOFT, "space-sc", "Splinter Cell", "system/SplinterCell.exe"))
        val lines = text.trimEnd().split("\r\n")
        assertTrue(lines.contains("if not exist \"%~dp0.droiddeck-ubisoft.json\" goto direct"))
        assertTrue(lines.contains("\"Z:\\usr\\local\\lib\\droiddeck-ubisoft\\droiddeck-upc.exe\" \"%~dp0.droiddeck-ubisoft.json\""))
        assertEquals("\"%~dp0system\\SplinterCell.exe\"", lines.last())
        assertTrue(lines.indexOf(":direct") < lines.lastIndex)
    }

    @Test fun activationPayloadsSurviveTheHexHop() {
        val bytes = byteArrayOf(0, 1, 0x7f, -1, -128, 42)
        assertEquals("00017fff802a", UbisoftActivation.hex(bytes))
        assertArrayEquals(bytes, UbisoftActivation.unhex("00017fff802a"))
        assertNull(UbisoftActivation.unhex("abc"))
    }
}
