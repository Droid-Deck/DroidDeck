package com.droiddeck.launcher.stores.epic

import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreShelves
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EpicShelvesTest {
    private fun item(id: String, items: String, mature: Boolean? = null, tags: List<String> = emptyList()) = JSONObject()
        .put("id", id).put("title", "T$id").put("image", "w").put("tall", "t").put("tags", "Action").put("free", false)
        .put("hasPrice", true).put("final", "$5").put("orig", "$10").put("disc", 50).put("url", "u").put("dev", "D")
        .put("pub", "P").put("rel", "2024").put("desc", "<b>x</b>")
        .put("extra", JSONObject().put("namespace", "ns").put("items", items))
        .apply { if (mature != null) put("mature", mature) else put("allTags", JSONArray(tags)) }

    @Test fun nativeAnswerAndPersistedCopyReadTheSame() {
        val answer = JSONObject().put("new", JSONArray().put(item("o1", "c1", tags = listOf("Mature"))))
            .put("deals", JSONArray()).put("free", JSONArray()).put("trending", JSONArray().put(item("o2", "c2")))
        val shelves = EpicShelves.fromJson(answer.toString())!!
        assertEquals(1, shelves.whatsNew.size)
        assertTrue(shelves.whatsNew[0].mature)
        assertEquals("x", shelves.whatsNew[0].description)
        assertEquals(50, shelves.whatsNew[0].discountPercent)
        val again = EpicShelves.fromJson(EpicShelves.toJson(shelves))!!
        assertEquals(shelves.whatsNew, again.whatsNew)
        assertEquals(shelves.trending, again.trending)
    }

    @Test fun allEmptyIsAbsent() {
        val empty = JSONObject().put("new", JSONArray()).put("deals", JSONArray()).put("free", JSONArray()).put("trending", JSONArray())
        assertNull(EpicShelves.fromJson(empty.toString()))
        assertNull(EpicShelves.fromJson(null))
    }

    @Test fun ownedShelfEntriesBecomeTheLibraryItem() {
        val shelf = EpicShelves.fromJson(JSONObject().put("free", JSONArray().put(item("offer", "x,c2", mature = false))).toString())!!
        val owned = CatalogItem(Store.EPIC, "FigApp", "Fig", imageUrl = null, owned = true, developer = "",
            publisher = "Lib Pub", extra = mapOf("namespace" to "ns", "catalogItemId" to "c2"))
        val marked = EpicShelves.markOwned(shelf, listOf(owned))
        val entry = marked.free.single()
        assertEquals("FigApp", entry.id)
        assertTrue(entry.owned)
        assertEquals("w", entry.imageUrl)
        assertEquals("D", entry.developer)
        assertEquals("Lib Pub", entry.publisher)
        assertEquals(StoreShelves().isEmpty, EpicShelves.markOwned(StoreShelves(), listOf(owned)).isEmpty)
    }
}
