package com.droiddeck.launcher.stores.xbox

import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreNet
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal object XboxCatalog {
    private val productId = Regex("[A-Z0-9]{12}")
    private val plans = setOf("CFQ7TTC0KGQ8", "CFQ7TTC0KHS0", "CFQ7TTC0P85B", "CFQ7TTC0K5DJ", "CFQ7TTC0K6L8", "CFQ7TTC10QFD")
    /** Plans whose store offers advertise PC games. Library entitlements under any active plan count, as the backend resolves a PC package for them. */
    private val pcPlans = mapOf(
        "CFQ7TTC0KGQ8" to "PC Game Pass",
        "CFQ7TTC0KHS0" to "Game Pass Ultimate",
        "CFQ7TTC0P85B" to "Xbox Game Pass Premium",
        "CFQ7TTC0K5DJ" to "Xbox Game Pass Essential",
        "CFQ7TTC10QFD" to "Xbox Game Pass (Starter Edition)",
    )

    fun access(json: JSONObject, account: String, market: String, now: Instant = Instant.now(), report: (String) -> Unit = {}): Map<String, String> {
        require(json.getInt("schema") == 1 && json.getString("account") == account && json.getString("market") == market)
        val age = java.time.Duration.between(timestamp(json.getString("checkedAt")), now).seconds
        require(age in -60..299)
        val memberships: Set<String>? = if (json.has("subscriptions")) {
            val subscriptions = json.getJSONArray("subscriptions")
            require(subscriptions.length() <= plans.size)
            val seen = HashSet<String>()
            buildSet {
                for (i in 0 until subscriptions.length()) {
                    val row = subscriptions.getJSONObject(i)
                    val id = row.getString("productId")
                    require(id in plans && seen.add(id))
                    val status = row.getString("status")
                    val expiry = timestamp(row.getString("endDate"))
                    if (status == "Active" && expiry.isAfter(now)) add(id)
                }
            }
        } else null
        val array = json.getJSONArray("entitlements")
        require(array.length() <= 10_000)
        val rows = LinkedHashMap<String, JSONObject>()
        val blocked = HashSet<String>()
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val id = row.optString("productId")
            if (!productId.matches(id)) continue
            val valid = row.opt("status") is String && row.opt("isTrial") is Boolean && row.opt("special") is Boolean &&
                row.opt("sharingSource") is String && runCatching { timestamp(row.getString("endDate")) }.isSuccess &&
                (row.isNull("satisfiedByProductId") || productId.matches(row.optString("satisfiedByProductId")))
            if (!valid || id in rows || id in blocked) { blocked.add(id); rows.remove(id) } else rows[id] = row
        }
        fun active(row: JSONObject) = row.getString("status") == "Active" && !row.getBoolean("isTrial") && !row.getBoolean("special") &&
            row.getString("sharingSource") == "None" && timestamp(row.getString("endDate")).isAfter(now)
        fun activePlan(id: String) = rows[id]?.let(::active) ?: (id in memberships.orEmpty())
        fun kind(id: String, visited: Set<String> = emptySet()): String? {
            if (id in blocked || id in visited || visited.size >= 16) return null
            val row = rows[id] ?: return null
            if (!active(row)) return null
            val parent = row.optString("satisfiedByProductId").takeUnless { it.isBlank() || it == "null" }
            if (parent == null) return if (id in plans) null else "owned"
            if (parent in blocked) return null
            if (parent in plans) return if (activePlan(parent)) "gamepass" else null
            return kind(parent, visited + id)
        }
        val result = rows.keys.mapNotNull { id -> kind(id)?.let { id to it } }.toMap()
        val missingPlan = rows.values.count { row -> row.optString("satisfiedByProductId").let { it in plans && !activePlan(it) } }
        report("library entitlements=${array.length()} invalid=${array.length() - rows.size} restricted=${rows.values.count { !active(it) }} " +
            "unverified-plan=$missingPlan owned=${result.values.count { it == "owned" }} gamepass=${result.values.count { it == "gamepass" }}")
        return result
    }

    private fun timestamp(value: String): Instant = java.time.OffsetDateTime.parse(value).toInstant()

    fun products(ids: List<String>, access: Map<String, String> = emptyMap(), market: String = Xodus.market()): List<CatalogItem> {
        return rawProducts(ids, market).mapNotNull { product(it, access) }
    }

    fun rawProducts(ids: List<String>, market: String = Xodus.market()): List<JSONObject> {
        return ids.map { it.uppercase() }.filter { productId.matches(it) }.distinct().chunked(25).flatMap { batch ->
            val body = get("https://displaycatalog.mp.microsoft.com/v7.0/products?bigIds=${batch.joinToString(",")}&market=$market&languages=en-us,neutral")
            objects(body.optJSONArray("Products")).filter { it.optString("ProductId").uppercase() in batch }
        }
    }

    fun search(query: String, access: Map<String, String>): List<CatalogItem> {
        if (productId.matches(query.uppercase())) return products(listOf(query.uppercase()), access)
        if (query.length !in 2..100) return emptyList()
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val body = get("https://apps.microsoft.com/api/products/search?query=$q&mediaType=games&age=all&price=all&category=all&subscription=all&gl=${Xodus.market()}&hl=en-us")
        val ids = objects(body.optJSONArray("productsList")).map { it.optString("productId") }.take(60)
        return products(ids, access)
    }

    fun featured(access: Map<String, String>): List<CatalogItem> {
        val body = get("https://apps.microsoft.com/api/Reco/GetComputedProductsList?listName=TopTrending&pgNo=1&noItems=12&filteredCategories=AllProducts&mediaType=Games&gl=${Xodus.market()}&hl=en-us&exp=0")
        return products(objects(body.optJSONArray("productsList")).map { it.optString("productId") }.take(24), access)
    }

    internal fun product(json: JSONObject, access: Map<String, String>, cloud: XboxCloudCatalog? = null): CatalogItem? {
        val id = json.optString("ProductId").uppercase()
        if (!productId.matches(id) || json.optString("ProductType") != "Game") return null
        val localized = objects(json.optJSONArray("LocalizedProperties")).firstOrNull() ?: return null
        val title = localized.optString("ProductTitle").takeIf { it.isNotBlank() } ?: return null
        val packages = objects(json.optJSONArray("DisplaySkuAvailabilities")).flatMap {
            objects(it.optJSONObject("Sku")?.optJSONObject("Properties")?.optJSONArray("Packages"))
        }
        val pc = packages.filter { pkg -> objects(pkg.optJSONArray("PlatformDependencies")).any { it.optString("PlatformName") == "Windows.Desktop" } }
        val installable = pc.any { it.optString("PackageFormat").equals("MSIXVC", true) }
        val streams = cloud?.streams(id, access[id].orEmpty()) == true
        if (!installable && !streams) return null
        val images = objects(localized.optJSONArray("Images"))
        fun image(vararg kinds: String): String? = kinds.firstNotNullOfOrNull { purpose ->
            images.firstOrNull { it.optString("ImagePurpose") == purpose }?.optString("Uri")?.let { raw ->
                val url = if (raw.startsWith("//")) "https:$raw" else raw
                runCatching { java.net.URI(url) }.getOrNull()?.takeIf {
                    it.scheme == "https" && it.host in setOf("store-images.s-microsoft.com", "store-images.microsoft.com") && it.userInfo == null && it.port == -1
                }?.toString()
            }
        }
        val kind = access[id]
        val includedWith = objects(localized.optJSONObject("EligibilityProperties")?.optJSONArray("Affirmations"))
            .mapNotNull { pcPlans[it.optString("AffirmationProductId")] }
            .firstOrNull().orEmpty()
        return CatalogItem(Store.XBOX, id, title, image("SuperHeroArt", "Hero", "BoxArt"),
            tallImageUrl = image("TitledPoster", "Poster", "BoxArt"),
            owned = kind == "owned", subscription = kind == "gamepass", includedWith = includedWith,
            storeUrl = "https://www.xbox.com/games/store/$id",
            publisher = localized.optString("PublisherName"), developer = localized.optString("DeveloperName"),
            description = localized.optString("ProductDescription"),
            sizeBytes = if (installable) pc.maxOfOrNull { it.optLong("MaxDownloadSizeInBytes") } ?: 0L else 0L,
            extra = mapOf("market" to Xodus.market(), "access" to kind.orEmpty(), "pc" to flag(installable), "cloud" to flag(streams)),
        )
    }

    private const val CLOUD_GAME_PASS = "29a81209-df6f-41fd-a528-2ae6b91f719c"
    private const val CLOUD_ALL = "ce573635-7c18-4d0c-9d68-90b932393470"

    /** Microsoft's public cloud lists (catalog.gamepass.com "sigls"; ids as Better xCloud by redphx, MIT, uses them). */
    fun cloudCatalog(market: String = Xodus.market()): XboxCloudCatalog =
        XboxCloudCatalog(cloudList(CLOUD_GAME_PASS, market), cloudList(CLOUD_ALL, market))

    private fun cloudList(id: String, market: String): Set<String> {
        val body = JSONArray(StoreNet.get("https://catalog.gamepass.com/sigls/v2?id=$id&language=en-us&market=$market") ?: error("Xbox cloud list unavailable"))
        return (0 until body.length()).mapNotNull { body.optJSONObject(it)?.optString("id")?.uppercase()?.takeIf(productId::matches) }.toSet()
    }

    fun cloudUrl(id: String, title: String, cloudId: String = id): String =
        "https://www.xbox.com/play/launch/${title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "game" }}/$cloudId"

    internal fun flag(value: Boolean) = if (value) "1" else "0"

    fun objects(array: JSONArray?): List<JSONObject> = if (array == null) emptyList() else
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }

    private fun get(url: String): JSONObject = JSONObject(StoreNet.get(url) ?: error("Microsoft Store could not be reached. Try again."))
}

internal data class XboxCloudCatalog(val gamePass: Set<String>, val all: Set<String>) {
    fun streams(id: String, access: String): Boolean = when (access) {
        "gamepass" -> id in gamePass
        "owned" -> id in all || id in gamePass
        else -> false
    }
}

val CatalogItem.xboxPc: Boolean get() = extra["pc"] != "0"
val CatalogItem.xboxCloud: Boolean get() = extra["cloud"] == "1"
val CatalogItem.xboxCloudId: String get() = extra["cloudId"]?.takeIf { it.isNotEmpty() } ?: id

/** Microsoft lists console and PC editions as separate products; a cloud-only one with a PC game's title folds into that card. */
internal fun mergeEditions(games: List<CatalogItem>): List<CatalogItem> {
    fun key(title: String) = title.lowercase().replace(Regex("[^a-z0-9]+"), "")
    val cloudOnly = games.filter { !it.xboxPc && it.xboxCloud }.groupBy { key(it.title) }
    val folded = HashSet<String>()
    val merged = games.map { game ->
        if (!game.xboxPc || game.xboxCloud) game
        else cloudOnly[key(game.title)]?.firstOrNull()?.let { console ->
            folded.add(console.id)
            game.copy(extra = game.extra + mapOf("cloud" to "1", "cloudId" to console.id))
        } ?: game
    }
    return merged.filter { it.id !in folded }
}
