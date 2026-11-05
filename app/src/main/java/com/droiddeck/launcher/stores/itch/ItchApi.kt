package com.droiddeck.launcher.stores.itch

import com.droiddeck.launcher.stores.StoreNet
import org.json.JSONArray
import org.json.JSONObject

internal object ItchApi {
    const val KEYS_PAGE = "https://itch.io/user/settings/api-keys"

    private const val BASE = "https://api.itch.io"
    private const val WEB = "https://itch.io"

    fun get(path: String, key: String): JSONObject? {
        val body = StoreNet.get(BASE + path, bearer = key, timeoutMs = 25_000) ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    fun profile(key: String): JSONObject? = get("/profile", key)?.optJSONObject("user")

    fun ownedKeys(key: String, page: Int): JSONObject? = get("/profile/owned-keys?page=$page&per_page=500", key)

    fun collections(key: String): JSONArray? = get("/profile/collections", key)?.optJSONArray("collections")

    fun collectionGames(key: String, collectionId: String, page: Int): JSONObject? =
        get("/collections/$collectionId/collection-games?page=$page", key)

    fun uploads(key: String, gameId: String, downloadKeyId: String): JSONArray? =
        get("/games/$gameId/uploads" + keyQuery(downloadKeyId), key)?.optJSONArray("uploads")

    fun search(key: String, query: String, page: Int): JSONArray? {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return get("/search/games?query=$q&page=$page", key)?.optJSONArray("games")
    }

    fun downloadUrl(uploadId: String, downloadKeyId: String): String =
        "$BASE/uploads/$uploadId/download" + keyQuery(downloadKeyId)

    private fun keyQuery(downloadKeyId: String): String = if (downloadKeyId.isBlank()) "" else "?download_key_id=$downloadKeyId"

    fun pageUrl(slug: String): String = "$WEB/$slug"
}
