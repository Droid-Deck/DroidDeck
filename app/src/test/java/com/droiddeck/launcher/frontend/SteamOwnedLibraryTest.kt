package com.droiddeck.launcher.frontend

import android.app.Application
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONArray
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SteamOwnedLibraryTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun clearImportBlock() {
        app.getSharedPreferences("steam-owned-library", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun signOutBlocksTheSameClientCookieUntilItChangesOrTheUserConnectsAgain() {
        val oldCookie = "steamLoginSecure=76561198000000001%7C%7Cold; sessionid=old"
        val newCookie = "steamLoginSecure=76561198000000001%7C%7Cnew; sessionid=new"
        SteamOwnedLibrary.blockClientSessionImport(app, oldCookie)

        assertTrue(SteamOwnedLibrary.isClientSessionBlocked(app, oldCookie))
        assertFalse(SteamOwnedLibrary.isClientSessionBlocked(app, newCookie))
        assertFalse(SteamOwnedLibrary.isClientSessionBlocked(app, oldCookie))

        SteamOwnedLibrary.blockClientSessionImport(app, oldCookie)
        SteamOwnedLibrary.allowClientSessionImport(app)
        assertFalse(SteamOwnedLibrary.isClientSessionBlocked(app, oldCookie))
    }

    @Test fun signOutWithoutAReadableCookieBlocksAnyClientImportUntilExplicitConnect() {
        SteamOwnedLibrary.blockClientSessionImport(app, null)

        assertTrue(SteamOwnedLibrary.isClientSessionBlocked(app, "steamLoginSecure=unknown"))

        SteamOwnedLibrary.allowClientSessionImport(app)
        assertFalse(SteamOwnedLibrary.isClientSessionBlocked(app, "steamLoginSecure=unknown"))
    }

    @Test fun ownedLibraryAcceptsOnlyArrayOrObjectPayloads() {
        assertEquals(setOf(12, 34), parseSteamOwnedApps(JSONObject().put("rgOwnedApps", JSONArray("[12,34,0]"))))
        assertEquals(emptySet<Int>(), parseSteamOwnedApps(JSONObject().put("rgOwnedApps", JSONArray())))
        assertEquals(emptySet<Int>(), parseSteamOwnedApps(JSONObject().put("rgOwnedApps", JSONObject())))
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            parseSteamOwnedApps(JSONObject().put("rgOwnedApps", "unavailable"))
        }
    }
}
