package com.droiddeck.launcher.stores.itch

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class ItchCollectionsTest {
    @Test fun collectionsKeepTheirIdTitleAndCount() {
        val body = JSONArray()
            .put(JSONObject().put("id", 8356509).put("title", "library").put("games_count", 12))
            .put(JSONObject().put("id", 7).put("title", "").put("games_count", 3))
            .put(JSONObject().put("title", "no id"))
        val list = ItchCollections.parseCollections(body)
        assertEquals(listOf("8356509"), list.map { it.id })
        assertEquals("library", list.single().title)
        assertEquals(12, list.single().count)
    }

    @Test fun collectionGamesReadBothKeyStylesAndSkipRowsWithoutAGame() {
        val game = JSONObject().put("id", 99).put("title", "Celeste 64").put("url", "https://noel.itch.io/celeste64")
        val snake = JSONObject().put("collection_games", JSONArray().put(JSONObject().put("game", game)).put(JSONObject().put("position", 1)))
        val camel = JSONObject().put("collectionGames", JSONArray().put(JSONObject().put("game", game)))
        assertEquals(listOf("noel.itch.io/celeste64"), ItchCollections.parseGames(snake).map { it.id })
        assertEquals(1, ItchCollections.parseGames(camel).size)
        assertEquals(false, ItchCollections.parseGames(snake).single().owned)
        assertEquals(0, ItchCollections.parseGames(JSONObject()).size)
    }
}
