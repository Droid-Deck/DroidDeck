package com.droiddeck.launcher.stores.xbox

import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class XboxLibraryCacheTest {
    private val account = "test@example.test"
    private val now = 1_792_440_000_000L
    private fun game(id: String, subscription: Boolean) = CatalogItem(Store.XBOX, id, "A game", "https://example.test/hero",
        tallImageUrl = "https://example.test/poster", description = "Description", developer = "Developer", publisher = "Publisher",
        sizeBytes = 12345, owned = !subscription, subscription = subscription, storeUrl = "https://www.xbox.com/games/store/$id",
        extra = mapOf("market" to "US", "access" to if (subscription) "gamepass" else "owned", "pc" to "1", "cloud" to "0", "cloudId" to ""))

    @Test fun restartRestoresMetadataWithoutTurningSubscriptionsIntoPurchases() {
        val games = listOf(game("9AAAAAAAAAAA", false), game("9BBBBBBBBBBB", true))
        val text = XboxLibraryCache.encode(account, "US", games, now)
        val cached = XboxLibraryCache.decode(text, account, "US", now)!!
        assertEquals(games, cached.games)
        assertFalse(cached.games[1].owned)
        assertTrue(cached.games[1].subscription)
    }

    @Test fun anotherAccountMarketOrInvalidEnvelopeCannotRestoreTheLibrary() {
        val text = XboxLibraryCache.encode(account, "US", listOf(game("9AAAAAAAAAAA", false)), now)
        assertNull(XboxLibraryCache.decode(text, "other@example.test", "US", now))
        assertNull(XboxLibraryCache.decode(text, account, "GB", now))
        assertNull(XboxLibraryCache.decode(text.replace("\"schema\":2", "\"schema\":1"), account, "US", now))
        assertNull(XboxLibraryCache.decode("invalid", account, "US", now))
        assertNull(XboxLibraryCache.decode(text, account, "US", now - 120_000))
    }

    @Test fun freshCacheAvoidsNetworkAndForceOrStalenessRequestsARefresh() {
        val cached = XboxLibraryCache.Snapshot(emptyList(), now)
        assertFalse(cached.needsRefresh(false, now + 899_999))
        assertTrue(cached.needsRefresh(false, now + 900_000))
        assertTrue(cached.needsRefresh(true, now))
        val old = XboxLibraryCache.decode(XboxLibraryCache.encode(account, "US", emptyList(), now), account, "US", now + 86_400_000)!!
        assertTrue(old.needsRefresh(false, now + 86_400_000))
        assertTrue(old.games.isEmpty())
    }

    @Test fun clearingTheCacheRemovesThePersistedAccountLibrary() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        XboxLibraryCache.write(app, account, "US", listOf(game("9AAAAAAAAAAA", false)))
        assertNotNull(XboxLibraryCache.read(app, account, "US"))
        XboxLibraryCache.clear(app)
        assertNull(XboxLibraryCache.read(app, account, "US"))
    }

    @Test fun cloudFlagsAndTheStreamingEditionSurviveARestart() {
        val stream = game("9CCCCCCCCCCC", true).copy(extra = mapOf("market" to "US", "access" to "gamepass", "pc" to "0", "cloud" to "1", "cloudId" to ""))
        val folded = game("9AAAAAAAAAAA", true).copy(extra = mapOf("market" to "US", "access" to "gamepass", "pc" to "1", "cloud" to "1", "cloudId" to "9DDDDDDDDDDD"))
        val cached = XboxLibraryCache.decode(XboxLibraryCache.encode(account, "US", listOf(stream, folded), now), account, "US", now)!!
        assertEquals(listOf(stream, folded), cached.games)
        assertFalse(cached.games[0].xboxPc); assertTrue(cached.games[0].xboxCloud)
        assertEquals("9DDDDDDDDDDD", cached.games[1].xboxCloudId)
    }
}
