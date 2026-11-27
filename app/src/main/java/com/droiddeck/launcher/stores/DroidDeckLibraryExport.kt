package com.droiddeck.launcher.stores

import com.droiddeck.launcher.frontend.Library
import org.json.JSONArray
import org.json.JSONObject

object DroidDeckLibraryExport {
    fun json(steamGames: List<Library.SteamGame>, confirmedOwnedSteamIds: Set<Int>): String {
        val games = JSONArray()
        val seen = HashSet<String>()
        for (game in steamGames) {
            if (game.source != Library.SOURCE_STEAM || game.appId <= 0) continue
            val key = "steam:${game.appId}"
            if (!seen.add(key)) continue
            games.put(JSONObject()
                .put("store", "steam")
                .put("storeName", "Steam")
                .put("storeId", game.appId.toString())
                .put("title", game.name)
                .put("owned", game.appId in confirmedOwnedSteamIds)
                .put("subscription", false)
                .put("storeUrl", "https://store.steampowered.com/app/${game.appId}/")
                .put("coverUrl", game.remoteArt.orEmpty())
                .put("heroUrl", game.remoteHero.orEmpty()))
        }
        for (store in Store.entries.filter { it != Store.STEAM }) for (item in StoresState.library[store].orEmpty()) {
            if (!item.accessible) continue
            val exportStoreId = if (store == Store.STEAM) Library.SOURCE_STEAM else store.id
            val key = "$exportStoreId:${item.id}"
            if (!seen.add(key)) continue
            games.put(JSONObject()
                .put("store", exportStoreId)
                .put("storeName", store.label)
                .put("storeId", item.id)
                .put("title", item.title)
                .put("owned", item.owned)
                .put("subscription", item.subscription)
                .put("storeUrl", item.storeUrl)
                .put("coverUrl", item.tallImageUrl ?: item.imageUrl.orEmpty())
                .put("heroUrl", item.imageUrl.orEmpty()))
        }
        return JSONObject()
            .put("schemaVersion", 1)
            .put("source", "DroidDeck")
            .put("generatedAt", java.time.Instant.now().toString())
            .put("games", games)
            .toString(2)
    }
}
