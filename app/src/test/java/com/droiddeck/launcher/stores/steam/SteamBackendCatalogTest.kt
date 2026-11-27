package com.droiddeck.launcher.stores.steam

import android.app.Application
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SteamBackendCatalogTest {
    @Test fun steamHasOneStoreIdentityAndResolvesThePreviousBetaId() {
        assertEquals(1, Store.entries.count { it.id == Store.STEAM.id })
        assertSame(Store.STEAM, Store.byId("steam-account"))
    }

    @Test fun ownedAndSteamClientLibrariesAreCombinedWithoutDuplicateApps() {
        val owned = CatalogItem(Store.STEAM, "620", "Portal 2", "owned-hero")
        val ownedOnly = CatalogItem(Store.STEAM, "550", "A game owned on Steam", "owned-only-hero")
        val installed = CatalogItem(Store.STEAM, "620", "Portal 2 from client", "client-header")
        val anotherInstalled = CatalogItem(Store.STEAM, "440", "Team Fortress 2", "client-header")

        val combined = combineSteamCatalog(listOf(owned, ownedOnly), listOf(installed, anotherInstalled))

        assertEquals(listOf("550", "620", "440"), combined.map { it.id })
        assertSame(installed, combined.first { it.id == "620" })
        assertEquals(1, combined.count { it.id == "620" })
    }

    @Test fun steamCatalogRowsAreResetWhenTheSelectedAccountChanges() {
        assertEquals(false, steamCatalogAccountMatches("account-a", "account-a", "account-b"))
        assertEquals(false, steamCatalogAccountMatches(null, "account-a", "account-b"))
        assertEquals(false, steamCatalogAccountMatches("account-a", null, null))
        assertEquals(true, steamCatalogAccountMatches("account-a", "account-a", "account-a"))
        assertEquals(true, steamCatalogAccountMatches(null, null, "account-b"))
    }

    @Test fun steamClientResultsMustMatchTheAccountRequestedByTheSync() {
        assertEquals(true, steamLibraryBelongsToAccount("76561198000000001", "76561198000000001"))
        assertEquals(false, steamLibraryBelongsToAccount("76561198000000001", "76561198000000002"))
        assertEquals(false, steamLibraryBelongsToAccount(null, "76561198000000001"))
        assertEquals(false, steamLibraryBelongsToAccount("76561198000000001", null))
    }

    @Test fun nativeLibraryRequestsRequireTheStillStoredSteamAccount() {
        assertEquals(true, steamClientLibraryRequestAuthorized("account-a", "account-a"))
        assertEquals(false, steamClientLibraryRequestAuthorized("account-a", "account-b"))
        assertEquals(false, steamClientLibraryRequestAuthorized("account-a", null))
        assertEquals(false, steamClientLibraryRequestAuthorized(null, "account-a"))
    }

    @Test fun updatingTheClientDisplayNameKeepsTheTransferredWebCookie() {
        val saved = JSONObject().apply {
            put("steam_id", "76561198000000001")
            put("display_name", "Steam · 0001")
            put("steam_login_secure", "76561198000000001%7C%7Csecret")
            put("session_id", "session-token")
        }

        val updated = updateSteamClientAccount(saved, "GuideAhon", "76561198000000001")

        assertEquals("GuideAhon", updated.getString("display_name"))
        assertEquals("76561198000000001%7C%7Csecret", updated.getString("steam_login_secure"))
        assertEquals("session-token", updated.getString("session_id"))
    }
}
