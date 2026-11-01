package com.droiddeck.launcher.stores.gamejolt

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameJoltCollectionsTest {
    @Test fun playlistsBecomeCollectionsByIdAndName() {
        val list = JSONArray()
            .put(JSONObject().put("id", 7).put("name", "test list"))
            .put(JSONObject().put("id", 0).put("name", "no id"))
            .put(JSONObject().put("id", 9).put("name", ""))
        val parsed = GameJoltCollections.parse(list)
        assertEquals(listOf("playlist:7"), parsed.map { it.id })
        assertEquals(listOf("test list"), parsed.map { it.title })
    }

    @Test fun eachCollectionHasItsOwnGameListEndpoint() {
        assertEquals("/web/library/games/followed/@some+one", GameJoltCollections.path(GameJoltCollections.FOLLOWED, "some one"))
        assertEquals("/web/library/games/playlist/7", GameJoltCollections.path("playlist:7", "x"))
        assertNull(GameJoltCollections.path("playlist:abc", "x"))
        assertNull(GameJoltCollections.path("other", "x"))
    }
}
