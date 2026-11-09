package com.droiddeck.launcher.stores.ea

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

class EaApiException(val status: Int, message: String) : Exception(message)

object EaApi {
    private const val GRAPHQL = "https://service-aggregation-layer.juno.ea.com/graphql"
    private const val LOCALE = "DEFAULT"

    class Player(val userId: String, val personaId: String, val displayName: String, val eaId: String)

    class OwnedProduct(
        val offerId: String,
        val productId: String,
        val name: String,
        val slug: String,
        val baseSlug: String,
        val gameType: String,
        val downloadable: Boolean,
        val trial: Boolean,
        val ownership: List<String>,
    )

    class Offer(
        val offerId: String,
        val contentId: String,
        val displayName: String,
        val displayType: String,
        val downloadable: Boolean,
        val executePathOverride: String,
        val installCheckOverride: String,
        val executeParameters: String,
        val multiplayerId: String,
        val masterTitleId: String,
        val gameVersion: String,
        val cloudSaves: String,
        val locales: List<String>,
    )

    class Images(val tall: String?, val wide: String?, val logo: String?)

    class Build(val buildId: String, val gameVersion: String, val liveDate: String)

    private val queries = HashMap<String, String>()

    internal fun query(name: String): String = synchronized(queries) {
        queries.getOrPut(name) {
            val stream = EaApi::class.java.getResourceAsStream("/ea/graphql/$name.gql") ?: throw IllegalStateException("missing query $name")
            stream.use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }

    private fun persisted(name: String): String =
        JSONObject().put("persistedQuery", JSONObject().put("version", 1).put("sha256Hash", EaCrypto.hex(EaCrypto.sha256(query(name).toByteArray())))).toString()

    internal fun call(token: String, name: String, variables: JSONObject): JSONObject {
        val headers = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")
        val get = Uri.parse(GRAPHQL).buildUpon()
            .appendQueryParameter("extensions", persisted(name))
            .appendQueryParameter("operationName", name)
            .appendQueryParameter("variables", variables.toString())
            .build().toString()
        var reply = EaHttp.request(get, headers = headers)
        var json = runCatching { JSONObject(reply.text) }.getOrNull()
        if (!reply.ok || json == null || json.has("errors")) {
            val body = JSONObject()
                .put("extensions", JSONObject(persisted(name)))
                .put("variables", variables)
                .put("operationName", name)
                .put("query", query(name))
            reply = EaHttp.request(GRAPHQL, "POST", headers + ("Content-Type" to "application/json"), body.toString().toByteArray())
            json = runCatching { JSONObject(reply.text) }.getOrNull()
        }
        if (reply.status == 401) throw EaApiException(401, "EA refused the session")
        if (json == null) throw EaApiException(reply.status, "EA gave no answer to $name (${reply.status})")
        json.optJSONArray("errors")?.optJSONObject(0)?.let { throw EaApiException(reply.status, "$name: ${it.optString("message")}") }
        return json.optJSONObject("data") ?: throw EaApiException(reply.status, "$name returned no data")
    }

    fun player(token: String): Player {
        val me = call(token, "GetUserPlayer", JSONObject()).optJSONObject("me") ?: throw EaApiException(0, "no player")
        val p = me.optJSONObject("player")
        return Player(
            userId = me.optString("pd"),
            personaId = p?.optString("psd").orEmpty(),
            displayName = p?.optString("displayName").orEmpty(),
            eaId = p?.optString("uniqueName").orEmpty().ifEmpty { p?.optString("displayName").orEmpty() },
        )
    }

    internal fun parseOwned(page: JSONObject): Pair<List<OwnedProduct>, String?> {
        val owned = page.optJSONObject("me")?.optJSONObject("ownedGameProducts") ?: return emptyList<OwnedProduct>() to null
        val items = owned.optJSONArray("items") ?: JSONArray()
        val list = (0 until items.length()).mapNotNull { items.optJSONObject(it) }.mapNotNull { o ->
            val product = o.optJSONObject("product") ?: return@mapNotNull null
            val base = product.optJSONObject("baseItem")
            val user = product.optJSONObject("gameProductUser")
            val methods = user?.optJSONArray("ownershipMethods")
            OwnedProduct(
                offerId = o.optString("originOfferId").ifEmpty { return@mapNotNull null },
                productId = product.optString("id"),
                name = (base?.optString("title")?.takeIf { it.isNotBlank() } ?: product.optString("name")).replace("\n", " ").trim(),
                slug = product.optString("gameSlug"),
                baseSlug = base?.optString("baseGameSlug").orEmpty().takeIf { it != "null" }.orEmpty(),
                gameType = base?.optString("gameType").orEmpty(),
                downloadable = product.optBoolean("downloadable", true),
                trial = product.optJSONObject("trialDetails")?.optString("trialType").orEmpty().let { it.isNotEmpty() && it != "null" },
                ownership = (0 until (methods?.length() ?: 0)).map { methods!!.optString(it) },
            )
        }
        val next = owned.optString("next").takeIf { it.isNotBlank() && it != "null" }
        return list to next
    }

    fun owned(token: String): List<OwnedProduct> {
        val out = ArrayList<OwnedProduct>()
        var next: String? = "0"
        var guard = 0
        while (next != null && guard++ < 50) {
            val variables = JSONObject()
                .put("isMac", false)
                .put("limit", 100)
                .put("next", next)
                .put("type", JSONArray().put("DIGITAL_FULL_GAME").put("PACKAGED_FULL_GAME"))
                .put("storefronts", JSONArray().put("EA"))
                .put("platforms", JSONArray().put("PC"))
            val (items, after) = parseOwned(call(token, "getPreloadedOwnedGames", variables))
            out += items
            next = after?.takeIf { it != next && items.isNotEmpty() }
        }
        return out
    }

    internal fun parseOffers(data: JSONObject): List<Offer> {
        val arr = data.optJSONArray("legacyOffers") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
            val downloads = o.optJSONArray("downloads")
            val version = (0 until (downloads?.length() ?: 0)).mapNotNull { downloads!!.optJSONObject(it) }
                .firstOrNull { it.optString("downloadType").equals("LIVE", true) }?.optString("gameVersion")
                ?: downloads?.optJSONObject(0)?.optString("gameVersion")
            val locales = o.optJSONArray("softwareLocales")
            fun s(k: String) = o.optString(k).takeIf { it != "null" }.orEmpty()
            Offer(
                offerId = s("offerId").ifEmpty { return@mapNotNull null },
                contentId = s("contentId"),
                displayName = s("displayName"),
                displayType = s("displayType"),
                downloadable = o.optBoolean("isDownloadable", true),
                executePathOverride = s("executePathOverride"),
                installCheckOverride = s("installCheckOverride"),
                executeParameters = s("executeParameters"),
                multiplayerId = s("multiplayerId"),
                masterTitleId = s("primaryMasterTitleId"),
                gameVersion = version.orEmpty().takeIf { it != "null" }.orEmpty(),
                cloudSaves = s("cloudSaveConfigurationOverride"),
                locales = (0 until (locales?.length() ?: 0)).map { locales!!.optString(it) },
            )
        }
    }

    fun offers(token: String, ids: List<String>): List<Offer> = ids.distinct().chunked(50).flatMap { chunk ->
        parseOffers(call(token, "getLegacyCatalogDefs", JSONObject().put("offerIds", JSONArray(chunk))))
    }

    private fun path(o: JSONObject?, vararg keys: String): String? {
        var cur = o ?: return null
        for (k in keys.dropLast(1)) cur = cur.optJSONObject(k) ?: return null
        return cur.optString(keys.last()).takeIf { it.startsWith("http") }
    }

    internal fun parseImages(data: JSONObject): Images {
        val game = data.optJSONObject("game")
        val key = game?.optJSONObject("keyArt")
        val pack = game?.optJSONObject("packArt")
        return Images(
            tall = path(pack, "aspect9x16Image", "path") ?: path(key, "aspect9x16Image", "path"),
            wide = path(key, "aspect16x9Image", "path") ?: path(key, "largestImage", "path") ?: path(pack, "aspect2x1Image", "path"),
            logo = path(game, "primaryLogo", "largestImage", "path") ?: path(game, "primaryLogo", "path"),
        )
    }

    fun images(token: String, slug: String): Images =
        parseImages(call(token, "GameImages", JSONObject().put("gameSlug", slug).put("locale", LOCALE)))

    fun downloadUrl(token: String, offerId: String, buildId: String? = null): String {
        val data = call(token, "downloadUrl", JSONObject().put("offerId", offerId).put("buildId", buildId.orEmpty()))
        return data.optJSONObject("downloadUrl")?.optString("url").orEmpty().ifEmpty { throw EaApiException(0, "EA gave no download link") }
    }

    fun builds(token: String, offerId: String): List<Build> {
        val arr = call(token, "availableBuilds", JSONObject().put("offerId", offerId)).optJSONArray("availableBuilds") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
            Build(it.optString("buildId"), it.optString("gameVersion").takeIf { v -> v != "null" }.orEmpty(), it.optString("buildLiveDate"))
        }
    }

    fun legacyEntitlements(token: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var next: String? = "0"
        var guard = 0
        while (next != null && guard++ < 50) {
            val variables = JSONObject().put("limit", 100).put("next", next).put("storeFronts", JSONArray().put("EA"))
            val page = call(token, "getLegacyEntitlements", variables).optJSONObject("me")?.optJSONObject("legacyEntitlements") ?: break
            val items = page.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) items.optJSONObject(i)?.let(out::add)
            next = page.optString("next").takeIf { it.isNotBlank() && it != "null" && it != next && items.length() > 0 }
        }
        return out
    }

    fun entitlements(token: String, group: String): JSONArray {
        val variables = JSONObject()
            .put("productIds", JSONArray())
            .put("includeChildGroups", false)
            .put("groupNames", JSONArray().put(group))
            .put("entitlementTag", "")
            .put("pageSize", 100)
            .put("pageNumber", 1)
        return call(token, "sdkEntitlements", variables).optJSONObject("me")?.optJSONObject("sdkEntitlements")?.optJSONArray("entitlements") ?: JSONArray()
    }
}
