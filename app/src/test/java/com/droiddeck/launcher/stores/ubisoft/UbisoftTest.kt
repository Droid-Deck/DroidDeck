package com.droiddeck.launcher.stores.ubisoft

import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreLaunch
import org.json.JSONArray
import java.io.File
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

    private fun listed(space: String, name: String) = CatalogItem(Store.UBISOFT, space, name, null, owned = true, extra = mapOf("spaceId" to space))

    private val owned = JSONObject().put("games", JSONArray()
        .put(JSONObject().put("productId", 1957).put("spaceId", "space-s").put("productType", 0).put("owned", true).put("configuration", steamConfig))
        .put(JSONObject().put("productId", 109).put("spaceId", "space-sc").put("productType", 0).put("owned", true).put("manifest", "AB").put("configuration", "root: {}"))
        .put(JSONObject().put("productId", 1961).put("spaceId", "space-addon").put("productType", 1).put("owned", true).put("manifest", "CD").put("configuration", steamConfig))
        .put(JSONObject().put("productId", 720).put("spaceId", "space-plus").put("productType", 0).put("owned", false).put("manifest", "EF").put("configuration", "root: {}"))
        .put(JSONObject().put("productId", 3539).put("spaceId", "space-o").put("productType", 0).put("owned", true).put("configuration", "root: {}")))

    @Test fun onlyGamesConnectDeliversAreInTheLibrary() {
        val profile = listOf(listed("space-sc", "Splinter Cell"), listed("space-s", "Syndicate"), listed("space-plus", "Ubisoft+ Game"), listed("space-gone", "Free Weekend"), listed("space-o", "Origins"))
        val games = UbisoftLibrary.assemble(profile, owned)
        assertEquals(listOf("space-o", "space-sc"), games.map { it.id })
        assertEquals(listOf("3539", "109"), games.map { it.extra["productId"] })
        assertEquals(null, games.first().extra["manifest"])
        assertEquals("Steam", UbisoftLibrary.thirdParty(steamConfig))
    }

    @Test fun gamesTheProfileLeavesOutTakeTheirArtFromTheConfiguration() {
        val config = "version: 2.0\r\nroot:\r\n  name: l1\r\n  thumb_image: THUMBIMAGE\r\n  background_image: 'b4c1.jpg'\r\n" +
            "  installer:\r\n    thumb_image: nested.jpg\r\nlocalizations:\r\n  default:\r\n    l1: \"Assassin's Creed\u2122\"\r\n    THUMBIMAGE: a3a6.jpg\r\n"
        val owned = JSONObject().put("games", JSONArray()
            .put(JSONObject().put("productId", 109).put("spaceId", "space-sc").put("productType", 0).put("owned", true).put("manifest", "AB").put("configuration", config))
            .put(JSONObject().put("productId", 82).put("spaceId", "space-ac").put("productType", 0).put("owned", true).put("manifest", "CD").put("configuration", config)))
        val art = CatalogItem(Store.UBISOFT, "space-sc", "Splinter Cell", "https://x/wide.jpg", tallImageUrl = "https://x/tall.jpg", owned = true, extra = mapOf("spaceId" to "space-sc"))
        val games = UbisoftLibrary.assemble(listOf(art), owned)
        val assets = "https://static3.cdn.ubi.com/orbit/uplay_launcher_3_0/assets/"
        val unlisted = games.first { it.id == "space-ac" }
        assertEquals("Assassin's Creed\u2122", unlisted.title)
        assertEquals(assets + "b4c1.jpg", unlisted.imageUrl)
        assertEquals(assets + "a3a6.jpg", unlisted.tallImageUrl)
        assertEquals("https://x/wide.jpg", games.first { it.id == "space-sc" }.imageUrl)
        assertNull(UbisoftLibrary.image("root:\n  thumb_image: THUMBIMAGE\n", "thumb_image"))
    }

    @Test fun withoutAnOwnershipAnswerTheProfileListStays() {
        val profile = listOf(listed("space-sc", "Splinter Cell"), listed("space-gone", "Free Weekend"))
        assertEquals(listOf("space-gone", "space-sc"), UbisoftLibrary.assemble(profile, JSONObject().put("error", "demux closed")).map { it.id })
        assertEquals(listOf("space-gone", "space-sc"), UbisoftLibrary.assemble(profile, null).map { it.id })
    }

    @Test fun theStoreSearchNamesEachGameWithItsPrice() {
        fun hit(id: String, title: String, price: Double, base: Double, off: String, date: String, free: String = "") = JSONObject()
            .put("objectID", id).put("title", title).put("price", JSONObject().put("USD", price)).put("default_price", JSONObject().put("USD", base))
            .put("promotion_percentage", off).put("release_date", date).put("Free_offer", free).put("link", "https://store.ubisoft.com/us/$id.html")
            .put("image_link", "https://img/$id.jpg?sw=300").put("productLauncherIDString", id.takeLast(3)).put("Rating", "esrb-m")
        val answer = JSONObject().put("hits", JSONArray()
            .put(hit("p-109", "Far Cry® 6", 15.0, 60.0, "75", "2021-10-07T00:00:00.000Z"))
            .put(hit("p-110", "Far Cry® 6 - Gold Edition", 30.0, 100.0, "70", "2021-10-07T00:00:00.000Z"))
            .put(hit("p-111", "Some Demo", 0.0, 0.0, "", "2021-10-07T00:00:00.000Z", free = "demo"))
            .put(hit("p-112", "Next Year", 70.0, 70.0, "", "2030-01-01T00:00:00.000Z")))
        val items = UbisoftStore.parse(answer, "USD", java.time.Instant.parse("2026-11-12T00:00:00Z"))
        assertEquals(listOf("Far Cry® 6"), items.map { it.title })
        val game = items.single()
        assertEquals("$15.00", game.finalPrice)
        assertEquals("$60.00", game.originalPrice)
        assertEquals(75, game.discountPercent)
        assertEquals("https://img/p-109.jpg", game.imageUrl)
        assertTrue(game.mature)
        val owned = CatalogItem(Store.UBISOFT, "space-fc6", "Far Cry 6", null, owned = true, extra = mapOf("productId" to "5266"))
        val ezio = CatalogItem(Store.UBISOFT, "ubisoft-store:x", "Assassin's Creed® The Ezio Collection", null)
        assertTrue(UbisoftStore.sameGame(game, owned))
        assertTrue(!UbisoftStore.sameGame(ezio, CatalogItem(Store.UBISOFT, "space-ac", "Assassin's Creed™", null, owned = true)))
    }

    @Test fun theBaseEditionStandsForItsGameWhicheverComesFirst() {
        fun hit(id: String, title: String) = JSONObject().put("objectID", id).put("title", title).put("price", JSONObject().put("USD", 10.0))
        val answer = JSONObject().put("hits", JSONArray().put(hit("gold", "Far Cry® 6 - Gold Edition")).put(hit("base", "Far Cry® 6")))
        assertEquals(listOf("ubisoft-store:base"), UbisoftStore.parse(answer, "USD").map { it.id })
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

    @Test fun aSaveNamesItsUbisoftIdentifierInItsHeader() {
        val mark = "4ef5bf634b40daa577aaeaa52c2283a9"
        val save = byteArrayOf(0x24, 2, 0, 0, 0, 0, 0, 0) + mark.toByteArray() + ByteArray(64) { it.toByte() }
        assertEquals(mark, UbisoftCloud.mark(save))
        assertNull(UbisoftCloud.mark(ByteArray(8) + ByteArray(32) + ByteArray(16)))
        assertNull(UbisoftCloud.mark(ByteArray(39)))
    }

    @Test fun cloudSaveBodiesSurviveTheZlibHop() {
        val save = ByteArray(200_000) { (it % 251).toByte() }
        assertArrayEquals(save, UbisoftCloud.inflate(UbisoftCloud.deflate(save)))
        assertEquals(0x78, UbisoftCloud.deflate(save)[0].toInt())
        val packed = UbisoftCloud.deflate(save)
        val cut = runCatching { UbisoftCloud.inflate(packed.copyOf(packed.size / 2)) }
        assertTrue(cut.isFailure)
    }

    @Test fun aSlotVersionChangesWithItsSizeTimeOrIdentifier() {
        val a = UbisoftCloud.Slot(1, "1", "4ef5bf634b40daa577aaeaa52c2283a9", 2414, 1_689_224_156_000)
        assertEquals("2414:1689224156000:4ef5bf634b40daa577aaeaa52c2283a9", UbisoftCloud.version(a))
        assertTrue(UbisoftCloud.version(a) != UbisoftCloud.version(UbisoftCloud.Slot(1, "1", a.hash, 2414, a.modifiedMs + 1)))
    }

    @Test fun aSlotCommitCountsOnlyWhenNoOtherSlotMovedAndOursLanded() {
        fun entity(revision: Int, vararg slots: Triple<String, String, Long>) = UbisoftCloud.Entity(JSONObject().put("revision", revision)
            .put("obj", JSONObject().put("slots", JSONArray().apply {
                for ((name, hash, size) in slots) put(JSONObject().put("filename", name).put("hash", hash).put("size", size).put("lastModified", "2026-01-01T00:00:00Z"))
            })))
        val a = "4ef5bf634b40daa577aaeaa52c2283a9"; val b = "0123456789abcdef0123456789abcdef"; val c = "fedcba9876543210fedcba9876543210"
        val before = entity(4, Triple("1", a, 10), Triple("2", b, 20))
        assertEquals(c, UbisoftCloudTransport.committedSlot(before, entity(5, Triple("1", c, 11), Triple("2", b, 20)), "1", c, 11)?.hash)
        assertNull(UbisoftCloudTransport.committedSlot(before, entity(6, Triple("1", c, 11), Triple("2", a, 21)), "1", c, 11))
        assertNull(UbisoftCloudTransport.committedSlot(before, entity(6, Triple("1", b, 30), Triple("2", b, 20)), "1", c, 11))
        assertNull(UbisoftCloudTransport.committedSlot(before, entity(6, Triple("1", c, 11)), "1", c, 11))
    }

    @Test fun aSlotTheCloudRewritesBeforeItIsVerifiedStaysUnsent() {
        val slots = MemorySlots().apply { store("1", MemorySlots.save("a1")) }
        val transport = UbisoftCloudTransport(slots, "u1")
        transport.list()
        slots.afterUpdate = { list -> list.getJSONObject(0).put("hash", UbisoftCloud.mark(MemorySlots.save("other device"))) }
        val changed = runCatching { transport.put(mapOf("1.save" to MemorySlots.save("a2"))) }.exceptionOrNull() as com.droiddeck.launcher.stores.CloudChanged
        assertTrue(changed.sent.isEmpty())
        assertEquals(listOf("1.save"), changed.unsent)
        assertNull(transport.committed("1.save"))
    }

    @Test fun ubisoftCloudSavesAreSupported() {
        assertTrue(com.droiddeck.launcher.stores.CloudSaves.supports(Store.UBISOFT))
        assertTrue(!com.droiddeck.launcher.stores.CloudSaves.supports(Store.AMAZON))
    }

    @Test fun keptGameTokensBelongToOneAccountAndProductUntilDropped() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        val dir = File(root, "cache")
        val cache = UbisoftActivation.TokenCache(dir)
        assertNull(cache.get("u-1", 109))
        assertTrue(cache.put("u-1", 109, byteArrayOf(1, 2, 3)))
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.get("u-1", 109))
        assertNull(cache.get("u-1", 82))
        assertNull(cache.get("u-2", 109))
        assertTrue(cache.put("../u-1", 82, byteArrayOf(4)))
        assertEquals(listOf("cache"), root.list()!!.toList())
        assertTrue(dir.listFiles()!!.all { it.parentFile == dir })
        assertTrue(cache.drop("u-1", 109))
        assertNull(cache.get("u-1", 109))
        assertTrue(cache.drop("u-1", 109))
        root.deleteRecursively()
    }
}
