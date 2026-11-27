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
        assertTrue(SteamOwnedLibrary.clientSessionImportBlocked(app))

        SteamOwnedLibrary.allowClientSessionImport(app)
        assertFalse(SteamOwnedLibrary.isClientSessionBlocked(app, "steamLoginSecure=unknown"))
        assertFalse(SteamOwnedLibrary.clientSessionImportBlocked(app))
    }

    @Test fun ownedLibraryAcceptsOnlyArrayOrObjectPayloads() {
        assertEquals(setOf(12, 34), parseSteamOwnedApps(JSONObject().put("rgOwnedApps", JSONArray("[12,34,0]"))))
        assertEquals(emptySet<Int>(), parseSteamOwnedApps(JSONObject().put("rgOwnedApps", JSONArray())))
        assertEquals(emptySet<Int>(), parseSteamOwnedApps(JSONObject().put("rgOwnedApps", JSONObject())))
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            parseSteamOwnedApps(JSONObject().put("rgOwnedApps", "unavailable"))
        }
    }

    @Test fun ownedCatalogIsAvailableOnlyAfterAFetchOrAnAccountMatchingCacheRead() {
        assertTrue(ownedLibraryAvailable("account-a", fetched = true, cachedSteamId = null))
        assertTrue(ownedLibraryAvailable("account-a", fetched = false, cachedSteamId = "account-a"))
        assertFalse(ownedLibraryAvailable("account-a", fetched = false, cachedSteamId = "account-b"))
        assertFalse(ownedLibraryAvailable(null, fetched = true, cachedSteamId = null))
    }

    @Test fun lateAccountObservationCannotUndoANewerAccountTransition() {
        val stale = observeSteamAccount(
            expectedGeneration = 4,
            currentGeneration = 5,
            currentAccountId = "account-c",
            expectedSteamId = "account-a",
            latestSteamId = "account-b",
        )
        assertEquals(5, stale.generation)
        assertEquals("account-c", stale.accountId)
        assertFalse(stale.isExpectedAccount)

        val current = observeSteamAccount(
            expectedGeneration = 4,
            currentGeneration = 4,
            currentAccountId = "account-a",
            expectedSteamId = "account-a",
            latestSteamId = "account-b",
        )
        assertEquals(5, current.generation)
        assertEquals("account-b", current.accountId)
        assertFalse(current.isExpectedAccount)
    }

    @Test fun exportRequiresTheAccountAndGenerationThatProducedTheOwnedGames() {
        val result = SteamOwnedLibrary.SyncResult(3, "account-a", emptyList(), available = true)

        assertTrue(ownedGamesBelongToAccount(result, 3, "account-a"))
        assertFalse(ownedGamesBelongToAccount(result, 4, "account-a"))
        assertFalse(ownedGamesBelongToAccount(result, 3, "account-b"))
        assertFalse(ownedGamesBelongToAccount(result.copy(available = false), 3, "account-a"))
    }

    @Test fun exportRejectsSuccessfulOwnershipFetchesWithMissingGameMetadata() {
        val completeEmpty = SteamOwnedLibrary.SyncResult(3, "account-a", emptyList(), available = true)
        val incomplete = completeEmpty.copy(confirmedOwnedIds = setOf(620), metadataComplete = false)

        assertTrue(ownedLibraryCompleteForExport(completeEmpty))
        assertFalse(ownedLibraryCompleteForExport(incomplete))
        assertEquals(setOf(620), incomplete.confirmedOwnedIds)
    }

    @Test fun initialAccountBindingKeepsItsCallbackCurrentWithoutTreatingAccountChangesAsCurrent() {
        assertTrue(steamSyncCallbackMatches(4, null, 5, "account-a", 5, "account-a"))
        assertFalse(steamSyncCallbackMatches(4, "account-a", 5, "account-b", 5, "account-b"))
        assertFalse(steamSyncCallbackMatches(4, null, 6, "account-b", 6, "account-b"))
        assertFalse(steamSyncCallbackMatches(4, null, 5, "account-a", 5, "account-b"))
    }
}
