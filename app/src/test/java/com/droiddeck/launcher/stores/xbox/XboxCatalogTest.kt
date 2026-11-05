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
    private fun membership(id: String = plan) = JSONObject().put("productId", id).put("status", "Active").put("endDate", "2099-01-01T00:00:00Z")
    private fun withMembership(json: JSONObject, vararg plans: JSONObject) = json.put("subscriptions", JSONArray(plans.toList()))

    @Test fun separateMembershipRestoresSubscriptionChildrenWithoutInventingOwnership() {
        val json = withMembership(envelope(row(game), row(parent, plan)), membership())
        assertEquals(mapOf(game to "owned", parent to "gamepass"), XboxCatalog.access(json, "test@example.test", "US", now))
        assertTrue(XboxCatalog.access(withMembership(envelope(), membership()), "test@example.test", "US", now).isEmpty())
        assertEquals(mapOf(game to "gamepass", parent to "gamepass"), XboxCatalog.access(
            withMembership(envelope(row(game, parent), row(parent, plan)), membership()), "test@example.test", "US", now))
    }

    @Test fun separateMembershipPreservesProductRestrictionsAndExistingPlanEvidence() {
        for (member in listOf(membership().put("status", "Expired"), membership().put("endDate", now.toString()))) {
            assertTrue(XboxCatalog.access(withMembership(envelope(row(game, plan)), member), "test@example.test", "US", now).isEmpty())
        }
        assertEquals(mapOf(game to "gamepass"), XboxCatalog.access(
            withMembership(envelope(row(game, plan), row(plan))), "test@example.test", "US", now))
        for ((key, value) in listOf("status" to "Expired", "isTrial" to true, "special" to true,
            "sharingSource" to "Family", "endDate" to now.toString())) {
            for (rows in listOf(arrayOf(row(game, plan).put(key, value)), arrayOf(row(game, plan), row(plan).put(key, value)))) {
                assertTrue(XboxCatalog.access(withMembership(envelope(*rows), membership()), "test@example.test", "US", now).isEmpty())
            }
        }
    }

    @Test fun malformedMembershipFailsTheRefreshInsteadOfBecomingAnEmptySuccessfulLibrary() {
        for (json in listOf(
            withMembership(envelope(row(game, plan)), membership().put("endDate", "invalid")),
            withMembership(envelope(row(game, plan)), membership(), membership()),
            withMembership(envelope(row(game, plan)), membership().put("productId", game)),
            envelope(row(game, plan)).put("subscriptions", JSONObject.NULL),
        )) {
            assertThrows(Exception::class.java) { XboxCatalog.access(json, "test@example.test", "US", now) }
        }
    }

    @Test fun membershipCannotCrossAccountMarketOrFreshnessBoundaries() {
        for ((key, value) in listOf("account" to "other@example.test", "market" to "GB", "checkedAt" to "2020-01-01T00:00:00Z")) {
            val json = withMembership(envelope(row(game, plan)).put(key, value), membership())
            assertThrows(IllegalArgumentException::class.java) { XboxCatalog.access(json, "test@example.test", "US", now) }
        }
    }

    @Test fun libraryDiagnosticsContainCountsWithoutAccountOrProductIdentifiers() {
        val lines = mutableListOf<String>()
        XboxCatalog.access(envelope(row(game), row(parent, plan)), "test@example.test", "US", now, lines::add)
        assertEquals(listOf("library entitlements=2 invalid=0 restricted=0 unverified-plan=1 owned=1 gamepass=0"), lines)
        assertFalse(lines.single().contains(game) || lines.single().contains(parent) || lines.single().contains("@"))
    }

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

    @Test fun everyActivePlanGrantsSubscriptionAccess() {
        for (plan in listOf("CFQ7TTC0KGQ8", "CFQ7TTC0KHS0", "CFQ7TTC0P85B", "CFQ7TTC0K5DJ", "CFQ7TTC10QFD", "CFQ7TTC0K6L8")) {
            assertEquals(mapOf(game to "gamepass"), access(row(game, plan), row(plan)))
        }
        val console = "CFQ7TTC0K6L8"
        assertEquals(mapOf(game to "gamepass"), XboxCatalog.access(withMembership(envelope(row(game, console)), membership(console)),
            "test@example.test", "US", now))
        assertTrue(XboxCatalog.access(withMembership(envelope(row(game, console)), membership(console).put("status", "Expired")),
            "test@example.test", "US", now).isEmpty())
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

    @Test fun pcSubscriptionAffirmationsOfferAnInstallWithoutClaimingOwnership() {
        fun offer(vararg affirmations: String) = JSONObject().put("ProductId", game).put("ProductType", "Game")
            .put("LocalizedProperties", JSONArray().put(JSONObject().put("ProductTitle", "A Pass game")
                .put("EligibilityProperties", JSONObject().put("Affirmations", JSONArray(affirmations.map {
                    JSONObject().put("AffirmationProductId", it)
                })))))
            .put("DisplaySkuAvailabilities", JSONArray().put(JSONObject().put("Sku", JSONObject().put("Properties", JSONObject()
                .put("Packages", JSONArray().put(JSONObject().put("PackageFormat", "MSIXVC").put("PlatformDependencies",
                    JSONArray().put(JSONObject().put("PlatformName", "Windows.Desktop")))))))))

        val pc = XboxCatalog.product(offer("CFQ7TTC0KGQ8"), emptyMap())!!
        assertEquals("PC Game Pass", pc.includedWith)
        assertFalse(pc.accessible); assertTrue(pc.installable)

        assertEquals("Game Pass Ultimate", XboxCatalog.product(offer("CFQ7TTC0KHS0"), emptyMap())!!.includedWith)
        assertEquals("Xbox Game Pass Premium", XboxCatalog.product(offer("CFQ7TTC0P85B"), emptyMap())!!.includedWith)
        assertEquals("Xbox Game Pass Essential", XboxCatalog.product(offer("CFQ7TTC0K5DJ"), emptyMap())!!.includedWith)
        assertEquals("Xbox Game Pass (Starter Edition)", XboxCatalog.product(offer("CFQ7TTC10QFD"), emptyMap())!!.includedWith)
        val console = XboxCatalog.product(offer("CFQ7TTC0K6L8"), emptyMap())!!
        assertEquals("", console.includedWith); assertFalse(console.installable)
        assertFalse(XboxCatalog.product(offer(), emptyMap())!!.installable)
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

    private fun product(id: String, platform: String?) = JSONObject().put("ProductId", id).put("ProductType", "Game")
        .put("LocalizedProperties", JSONArray().put(JSONObject().put("ProductTitle", "A Game")))
        .put("DisplaySkuAvailabilities", JSONArray().put(JSONObject().put("Sku", JSONObject().put("Properties", JSONObject()
            .put("Packages", if (platform == null) JSONArray() else JSONArray().put(JSONObject().put("PackageFormat", "MSIXVC")
                .put("PlatformDependencies", JSONArray().put(JSONObject().put("PlatformName", platform)))))))))

    @Test fun cloudListsKeepStreamOnlyGamesAndMarkStreamablePcGames() {
        val cloud = XboxCloudCatalog(gamePass = setOf(game), all = setOf(game, parent))
        val pc = XboxCatalog.product(product(game, "Windows.Desktop"), mapOf(game to "gamepass"), cloud)!!
        assertTrue(pc.xboxPc); assertTrue(pc.xboxCloud)
        val consoleOnly = XboxCatalog.product(product(parent, "Xbox.One"), mapOf(parent to "owned"), cloud)!!
        assertFalse(consoleOnly.xboxPc); assertTrue(consoleOnly.xboxCloud); assertEquals(0L, consoleOnly.sizeBytes)
        assertNull(XboxCatalog.product(product(parent, "Xbox.One"), mapOf(parent to "gamepass"), cloud))
        assertNull(XboxCatalog.product(product(parent, "Xbox.One"), mapOf(parent to "owned"), null))
        assertFalse(XboxCatalog.product(product(game, "Windows.Desktop"), mapOf(game to "gamepass"), null)!!.xboxCloud)
    }

    @Test fun aCloudOnlyEditionFoldsIntoThePcCardWithTheSameTitle() {
        val cloud = XboxCloudCatalog(gamePass = setOf(parent), all = emptySet())
        val pc = XboxCatalog.product(product(game, "Windows.Desktop"), mapOf(game to "gamepass"), cloud)!!
        val console = XboxCatalog.product(product(parent, "Xbox.One"), mapOf(parent to "gamepass"), cloud)!!
        val merged = mergeEditions(listOf(pc, console)).single()
        assertEquals(game, merged.id); assertTrue(merged.xboxPc); assertTrue(merged.xboxCloud); assertEquals(parent, merged.xboxCloudId)
    }
}
