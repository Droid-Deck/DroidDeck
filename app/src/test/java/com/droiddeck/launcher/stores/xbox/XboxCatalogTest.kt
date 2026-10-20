package com.droiddeck.launcher.stores.xbox

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class XboxCatalogTest {
    private val now = Instant.parse("2026-10-19T12:00:00Z")
    private val game = "9AAAAAAAAAAA"
    private val parent = "9BBBBBBBBBBB"
    private val plan = "CFQ7TTC0KGQ8"
    private fun row(id: String, parent: String? = null) = JSONObject().put("productId", id).put("status", "Active")
        .put("isTrial", false).put("special", false).put("sharingSource", "None")
        .put("endDate", "2099-01-01T00:00:00Z").put("satisfiedByProductId", parent ?: JSONObject.NULL)
    private fun envelope(vararg rows: JSONObject) = JSONObject().put("schema", 1).put("account", "test@example.test")
        .put("market", "US").put("checkedAt", now.toString()).put("entitlements", JSONArray(rows.toList()))
    private fun access(vararg rows: JSONObject) = XboxCatalog.access(envelope(*rows), "test@example.test", "US", now)

    @Test fun purchasedAndSubscriptionAccessRemainDistinct() {
        assertEquals(mapOf(game to "owned", parent to "gamepass"), access(row(game), row(parent, plan), row(plan)))
        assertFalse(access(row(plan)).containsKey(plan))
    }

    @Test fun subscriptionRequiresAnActivePlanRow() {
        assertTrue(access(row(game, plan)).isEmpty())
        for ((key, value) in listOf("status" to "Expired", "isTrial" to true, "special" to true,
            "sharingSource" to "Family", "endDate" to now.toString())) {
            assertTrue(access(row(game, plan), row(plan).put(key, value)).isEmpty())
        }
        assertEquals(mapOf(game to "gamepass", parent to "gamepass"), access(row(game, parent), row(parent, plan), row(plan)))
    }

    @Test fun backendRfc3339OffsetsAndNanosecondsAreAccepted() {
        val checked = "2026-10-19T19:02:38.173609086+00:00"
        val response = envelope(row(game).put("endDate", "2099-01-01T01:00:00.000000001+01:00"))
            .put("checkedAt", checked)
        assertEquals(mapOf(game to "owned"), XboxCatalog.access(response, "test@example.test", "US",
            Instant.parse("2026-10-19T19:02:39Z")))
    }

    @Test fun expiredTrialSharedAndUnknownFlagsDoNotGrantAccess() {
        for ((key, value) in listOf("status" to "Expired", "isTrial" to true, "special" to true,
            "sharingSource" to "Family", "endDate" to "2020-01-01T00:00:00Z")) {
            assertTrue(access(row(game).put(key, value)).isEmpty())
        }
    }

    @Test fun malformedAndConflictingParentsCannotGrantDependentAccess() {
        assertTrue(access(row(game, parent), row(parent).put("isTrial", JSONObject.NULL)).isEmpty())
        assertTrue(access(row(game, plan), row(plan).put("endDate", "invalid")).isEmpty())
        assertTrue(access(row(game), row(game, plan)).isEmpty())
        assertTrue(access(row(game, parent), row(parent, game)).isEmpty())
    }

    @Test fun mismatchedAccountAndStaleResultsAreRejected() {
        for (json in listOf(envelope(row(game)).put("account", "other@example.test"),
            envelope(row(game)).put("checkedAt", "2020-01-01T00:00:00Z"))) {
            assertThrows(IllegalArgumentException::class.java) { XboxCatalog.access(json, "test@example.test", "US", now) }
        }
    }

    @Test fun installedSubscriptionDoesNotBecomeOwnershipWithoutACachedLibrary() {
        val sidecar = com.droiddeck.launcher.stores.StoreGameSidecar(com.droiddeck.launcher.stores.Store.XBOX,
            game, "A game", "Game.exe", extra = mapOf("access" to "gamepass"))
        val card = com.droiddeck.launcher.stores.installedCards(com.droiddeck.launcher.stores.Store.XBOX,
            listOf(com.droiddeck.launcher.stores.InstalledStoreGame(sidecar, java.io.File("/synthetic-game"))), emptyList()).single()
        assertFalse(card.owned); assertTrue(card.subscription)
    }

    @Test fun consoleAndUwpOffersAreFilteredOut() {
        fun product(platform: String, format: String) = JSONObject().put("ProductId", game).put("ProductType", "Game")
            .put("LocalizedProperties", JSONArray().put(JSONObject().put("ProductTitle", "A PC game")))
            .put("DisplaySkuAvailabilities", JSONArray().put(JSONObject().put("Sku", JSONObject().put("Properties", JSONObject()
                .put("Packages", JSONArray().put(JSONObject().put("PackageFormat", format).put("PlatformDependencies",
                    JSONArray().put(JSONObject().put("PlatformName", platform)))))))))
        assertNull(XboxCatalog.product(product("Xbox.One", "MSIXVC"), mapOf(game to "owned")))
        assertNull(XboxCatalog.product(product("Windows.Desktop", "APPX"), mapOf(game to "owned")))
        val pc = XboxCatalog.product(product("Windows.Desktop", "MSIXVC"), mapOf(game to "gamepass"))!!
        assertEquals(game, XboxCatalog.product(product("Windows.Desktop", "MSIXVC").put("ProductId", game.lowercase()), mapOf(game to "owned"))!!.id)
        assertTrue(pc.accessible); assertTrue(pc.subscription); assertFalse(pc.owned)
    }
}
