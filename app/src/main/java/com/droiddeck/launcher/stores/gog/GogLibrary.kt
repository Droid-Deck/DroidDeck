package com.droiddeck.launcher.stores.gog

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreNet
import com.droiddeck.launcher.stores.cleanStoreText
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The signed-in account's GOG library: the owned games from embed.gog.com's account listing, each
 * product's details from api.gog.com, and a cache so the tab paints at once. A sync fetches only what
 * the cache lacks unless forced or 15 minutes stale. Ported from Bannerlator's GogLibraryRepo.
 */
object GogLibrary {
    private const val TAG = "GogLibrary"
    // v2: the card art moved from the store page's backdrop to the Galaxy library art; an older cache is re-fetched.
    private const val CACHE_KEY = "library_cache_v2"
    private const val LAST_SYNC_KEY = "library_synced_at"
    // Owned products that are not Windows games (DLC, films, soundtracks, other platforms): never in
    // the cache, so without this every sync would fetch each of them again.
    private const val SKIPPED_KEY = "library_skipped"
    private const val THROTTLE_MS = 6L * 60L * 60L * 1000L
    // mediaType=1 leaves the movies and soundtracks out, hiddenFlag=0 the hidden ones.
    private const val LIST_URL = "https://embed.gog.com/account/getFilteredProducts?mediaType=1&hiddenFlag=0&sortBy=title"
    private const val LIST_MAX_PAGES = 100
    // The listing's image is a bare hash, and the CDN serves it only through Galaxy's own formatters: the
    // vertical cover is the one card art it carries. The wide background only the per-game fetch knows.
    private const val COVER_SUFFIX = "_glx_vertical_cover.webp"
    // The older account endpoint: owned ids only. The fallback for a listing that cannot be read.
    private const val OWNED_URL = "https://embed.gog.com/user/data/games"

    private val syncing = AtomicBoolean(false)

    sealed interface SyncResult {
        class Ok(val games: List<GogGame>, val fetched: Int) : SyncResult
        class Failed(val message: String) : SyncResult
        object NotLoggedIn : SyncResult
        object Busy : SyncResult
    }

    /** The cached library, alphabetical; empty until the first sync. */
    fun cached(context: Context): List<GogGame> {
        val json = GogPrefs.get(context).getString(CACHE_KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                GogGame(o.optString("gameId", ""), o.optString("title", ""), o.optString("imageUrl", ""), cleanStoreText(o.optString("description", "")),
                    o.optString("developer", ""), o.optString("category", ""), o.optInt("generation", 1), o.optString("verticalCover", "").ifEmpty { null },
                    o.optString("releaseDate", ""), o.optString("publisher", ""))
            }.filter { it.gameId.isNotEmpty() }.sortedBy { it.title.lowercase() }
        }.getOrDefault(emptyList())
    }

    fun sizeOf(context: Context, gameId: String): Long = GogPrefs.get(context).getLong("size_$gameId", 0L)

    /** Owned games diffed against the cache; fetches what is new, or everything when forced / empty / stale. [onList] gets the whole library as soon as the account listing has it, and again as products come back. Blocking. */
    fun sync(context: Context, force: Boolean = false, onStatus: (String) -> Unit = {}, onList: (List<GogGame>) -> Unit = {}): SyncResult {
        if (!syncing.compareAndSet(false, true)) return SyncResult.Busy
        try {
            val app = context.applicationContext
            val p = GogPrefs.get(app)
            val token = GogAuth.validToken(app) ?: return SyncResult.NotLoggedIn
            onStatus("Fetching game list…")
            val listing = ownedListing(token)
            // The listing is the only source of titles: without it the owned ids still sync, just without the early paint.
            val listed: List<GogGame> = listing
                ?: ownedIds(token)?.map { GogGame(it, "", "", "", "", "", 1, null, "", "") }
                ?: return SyncResult.Failed("Couldn't reach GOG")
            val named = listing != null
            val ownedSet = listed.mapTo(HashSet()) { it.gameId }
            val cachedList = cached(app)
            val stale = System.currentTimeMillis() - p.getLong(LAST_SYNC_KEY, 0L) >= THROTTLE_MS
            val heavy = force || cachedList.isEmpty() || stale
            val cachedIds = cachedList.mapTo(HashSet()) { it.gameId }
            // A product the last sync found uninstallable stays out of the list while it is re-checked.
            val skipped = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
            skipped.addAll(p.getStringSet(SKIPPED_KEY, emptySet()).orEmpty())
            val idsToFetch = if (heavy) listed.map { it.gameId } else listed.map { it.gameId }.filter { it !in cachedIds && it !in skipped }
            val merged = LinkedHashMap<String, GogGame>()
            // The cache's own entry stays even when the game is re-fetched: its details beat the listing's.
            for (g in cachedList) if (g.gameId in ownedSet && g.gameId !in skipped) merged[g.gameId] = g
            // A game the sync has not reached keeps the listing's entry, so its card is there from the start.
            for (g in listed) if (g.gameId !in merged && g.gameId !in skipped) merged[g.gameId] = g
            // Only products that were asked about are cached: a bare listing entry is asked about again.
            val detailed = HashSet(cachedIds)
            if (named) onList(sorted(merged))
            if (idsToFetch.isEmpty()) {
                val list = merged.values.toList()
                skipped.retainAll(ownedSet)
                p.edit().putStringSet(SKIPPED_KEY, HashSet(skipped)).apply()
                saveCache(p, list.filter { it.gameId in detailed })
                return SyncResult.Ok(sorted(merged), 0)
            }
            onStatus("Syncing ${idsToFetch.size} game${if (idsToFetch.size == 1) "" else "s"}…")
            val pool = Executors.newFixedThreadPool(5)
            val futures = idsToFetch.map { id -> pool.submit(Callable<GogGame?> { fetchGame(p, id, token, skipped) }) }
            pool.shutdown()
            // What the grid is showing right now: a fetched game's own art and title only land when the round ends.
            val shown = HashMap(merged)
            var fetched = 0
            for ((idx, f) in futures.withIndex()) {
                val g = runCatching { f.get() }.getOrNull()
                if (g != null) { merged[g.gameId] = g; detailed.add(g.gameId); fetched++; skipped.remove(g.gameId) }
                if ((idx + 1) % 5 == 0) onStatus("Syncing… ${idx + 1}/${futures.size}")
                if (named && (idx + 1) % 20 == 0) onList(sorted(merged.mapValues { (key, v) -> shown[key]?.let { v.detailsOver(it) } ?: v }))
            }
            // A product this round found uninstallable leaves when the round is over, not in the middle of it.
            for (id in skipped) merged.remove(id)
            val finalList = merged.values.toList()
            saveCache(p, finalList.filter { it.gameId in detailed })
            skipped.retainAll(ownedSet)
            p.edit().putStringSet(SKIPPED_KEY, HashSet(skipped)).apply()
            if (heavy) p.edit().putLong(LAST_SYNC_KEY, System.currentTimeMillis()).apply()
            Log.i(TAG, "sync: owned=${listed.size} fetched=$fetched cached=${finalList.size} heavy=$heavy")
            return SyncResult.Ok(sorted(merged), fetched)
        } catch (e: Exception) {
            Log.w(TAG, "sync failed: ${e.message}")
            return SyncResult.Failed(e.message ?: "Sync failed")
        } finally {
            syncing.set(false)
        }
    }

    /** The account's owned games as the store lists them: titles and art, no description or build. Null when the account cannot be read. */
    private fun ownedListing(token: String): List<GogGame>? {
        val all = ArrayList<GogGame>()
        var page = 1
        var pages = 1
        while (page <= pages && page <= LIST_MAX_PAGES) {
            val json = StoreNet.get("$LIST_URL&page=$page", bearer = token, userAgent = GogAuth.GALAXY_UA) ?: return null
            val listed = parseListing(json) ?: return null
            all.addAll(listed.games)
            pages = listed.totalPages
            page++
        }
        return all
    }

    /** The owned product ids from the older account endpoint, which carries no titles: the fallback when the listing cannot be read. */
    private fun ownedIds(token: String): List<String>? {
        val json = StoreNet.get(OWNED_URL, bearer = token, userAgent = GogAuth.GALAXY_UA) ?: return null
        val owned = runCatching { JSONObject(json).optJSONArray("owned") }.getOrNull() ?: return null
        val ids = ArrayList<String>(owned.length())
        for (i in 0 until owned.length()) {
            val id = owned.optLong(i).toString()
            if (id != "1801418160" && id != "0") ids.add(id)
        }
        return ids
    }

    /** One page of the account listing, or null when the response is not one. */
    fun parseListing(json: String): ListingPage? = runCatching {
        val root = JSONObject(json)
        val arr = root.optJSONArray("products") ?: return@runCatching null
        val games = ArrayList<GogGame>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // 1801418160 is GOG's own "Galaxy" entry, in every library.
            val id = o.optLong("id", 0L).toString()
            if (id == "0" || id == "1801418160") continue
            if (o.optBoolean("isMovie", false) || !o.optBoolean("isGame", true)) continue
            val title = o.optString("title", "").trim()
            if (title.isEmpty()) continue
            val worksOn = o.optJSONObject("worksOn")
            if (worksOn != null && !worksOn.optBoolean("Windows", true)) continue
            val released = o.optJSONObject("releaseDate")?.optString("date", "").orEmpty().substringBefore(' ')
            val image = o.optString("image", "").ifBlank { null }
            games.add(GogGame(id, title, "", "", "", o.optString("category", ""), 1,
                image?.let { GogStoreCatalog.absolutize(it + COVER_SUFFIX) }, released, ""))
        }
        ListingPage(games, maxOf(1, root.optInt("totalPages", 1)))
    }.getOrNull()

    /** A page of the account listing: the games on it, and how many pages there are. */
    class ListingPage(val games: List<GogGame>, val totalPages: Int)

    private fun sorted(merged: Map<String, GogGame>): List<GogGame> = merged.values.sortedBy { it.title.lowercase() }

    /** The round's details on the card that is already on screen: its art and title wait for the end of the round. */
    private fun GogGame.detailsOver(shown: GogGame): GogGame =
        GogGame(gameId, shown.title, shown.imageUrl, description, developer, category, generation, shown.verticalCover, releaseDate, publisher)

    /** One product's details, or null for anything that is not a Windows game (DLC, movies, other platforms). */
    private fun fetchGame(prefs: SharedPreferences, id: String, token: String, skipped: MutableSet<String>): GogGame? {
        // Not a game this app installs: remembered, so the next sync does not ask again.
        fun skip(): GogGame? { skipped.add(id); return null }
        try {
            val productJson = StoreNet.get("https://api.gog.com/products/$id?expand=downloads,description", bearer = token) ?: return null
            val prod = JSONObject(productJson)
            if (prod.optBoolean("is_secret", false)) return skip()
            val gameType = prod.optString("game_type", "")
            if (gameType.isNotEmpty() && gameType != "game" && gameType != "pack") return skip()
            var title = prod.optJSONObject("title")?.optString("*")
            if (title.isNullOrEmpty()) title = prod.optString("title")
            if (title.isNullOrEmpty()) return skip()
            // Card art from the v2 game record, the way the Galaxy client draws its library: the dark
            // Galaxy background for the wide card and the box art for the tall one. The product's
            // `images.background` is the store page's fade-out backdrop (it ends in white on a
            // card) and is never used; the logo or icon is the last resort.
            val images = prod.optJSONObject("images")
            var imageUrl = ""
            var boxArt = ""
            val v2 = runCatching { StoreNet.get("https://api.gog.com/v2/games/$id?locale=en-US")?.let { JSONObject(it) } }.getOrNull()
            v2?.optJSONObject("_links")?.let { links ->
                imageUrl = links.optJSONObject("galaxyBackgroundImage")?.optString("href", "").orEmpty()
                boxArt = links.optJSONObject("boxArtImage")?.optString("href", "").orEmpty()
            }
            if (imageUrl.isEmpty()) imageUrl = images?.optString("logo2x", "") ?: ""
            if (imageUrl.isEmpty()) imageUrl = images?.optString("icon", "") ?: ""
            // The account endpoint hands back template keys for some products; the v2 record's text
            // is tried next, and when that is a template too the game simply has no description.
            val desc = cleanStoreText(prod.optJSONObject("description")?.optString("lead", ""))
                .ifEmpty { cleanStoreText(v2?.optString("overview", "")) }
                .ifEmpty { cleanStoreText(v2?.optString("description", "")) }
            // The products endpoint names no developer or publisher; the v2 record does, with the worldwide release date.
            val embedded = v2?.optJSONObject("_embedded")
            fun names(key: String) = embedded?.optJSONArray(key)
                ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) } }
                ?.joinToString(", ").orEmpty()
            val developer = names("developers")
            val publisher = embedded?.optJSONObject("publisher")?.optString("name")?.takeIf(String::isNotBlank) ?: names("publishers")
            val releaseDate = listOf(embedded?.optJSONObject("product")?.optString("globalReleaseDate"), prod.optString("release_date"))
                .firstOrNull { !it.isNullOrBlank() && it != "null" }.orEmpty()
            val genres = prod.optJSONArray("genres")
            val category = if (genres != null && genres.length() > 0) genres.optJSONObject(0)?.optString("name", "") ?: "" else ""

            var generation = 1
            var hasWindowsBuild = false
            runCatching {
                val buildsJson = StoreNet.get("https://content-system.gog.com/products/$id/os/windows/builds?generation=2", bearer = token, userAgent = GogAuth.GALAXY_UA)
                val items = buildsJson?.let { JSONObject(it).optJSONArray("items") }
                if (items != null && items.length() > 0) {
                    hasWindowsBuild = true
                    var maxGen = 0
                    for (bi in 0 until items.length()) maxGen = maxOf(maxGen, items.optJSONObject(bi)?.optInt("generation", 0) ?: 0)
                    if (maxGen > 0) generation = maxGen
                }
            }
            prefs.edit().putInt("gen_$id", generation).apply()
            if (prefs.getLong("size_$id", -1L) <= 0) {
                val size = GogDownloadManager.fetchInstallSizeBytes(id, token)
                if (size > 0) prefs.edit().putLong("size_$id", size).apply()
            }
            var hasWindowsInstaller = false
            prod.optJSONObject("downloads")?.optJSONArray("installers")?.let { arr ->
                for (di in 0 until arr.length()) if (arr.optJSONObject(di)?.optString("os", "") == "windows") { hasWindowsInstaller = true; break }
            }
            if (!hasWindowsBuild && !hasWindowsInstaller) return skip()

            var verticalCover: String? = boxArt.ifEmpty { prefs.getString("vcover_$id", null) }
            if (verticalCover.isNullOrEmpty()) {
                verticalCover = fetchVerticalCover(id).ifEmpty { StoreNet.sgdbPoster(title) }
            }
            if (!verticalCover.isNullOrEmpty()) prefs.edit().putString("vcover_$id", verticalCover).apply()
            return GogGame(id, title, GogStoreCatalog.absolutize(imageUrl), desc, developer, category, generation, verticalCover?.ifEmpty { null }, releaseDate, publisher)
        } catch (_: Exception) {
            return null
        }
    }

    private fun fetchVerticalCover(productId: String): String = try {
        val extJson = StoreNet.get("https://gamesdb.gog.com/platforms/gog/external_releases/$productId")
        val gameId = extJson?.let { JSONObject(it).optString("game_id", "") }.orEmpty()
        if (gameId.isEmpty()) "" else {
            val gameJson = StoreNet.get("https://gamesdb.gog.com/games/$gameId")
            val fmt = gameJson?.let { JSONObject(it).optJSONObject("vertical_cover") }?.optString("url_format", "").orEmpty()
            if (fmt.isEmpty()) "" else fmt.replace("{formatter}", "").replace("{ext}", "webp")
        }
    } catch (_: Exception) { "" }

    private fun saveCache(prefs: SharedPreferences, games: List<GogGame>) {
        runCatching {
            val arr = JSONArray()
            for (g in games) {
                arr.put(JSONObject().put("gameId", g.gameId).put("title", g.title).put("imageUrl", g.imageUrl).put("description", g.description)
                    .put("developer", g.developer).put("category", g.category).put("generation", g.generation).put("releaseDate", g.releaseDate).put("publisher", g.publisher)
                    .apply { if (!g.verticalCover.isNullOrEmpty()) put("verticalCover", g.verticalCover) })
            }
            prefs.edit().putString(CACHE_KEY, arr.toString()).apply()
        }
    }

    /** A cached game as the page's card. */
    fun toCatalogItem(context: Context, g: GogGame): CatalogItem = CatalogItem(
        store = Store.GOG, id = g.gameId, title = g.title,
        imageUrl = GogStoreCatalog.absolutize(g.imageUrl).ifBlank { null },
        tallImageUrl = GogStoreCatalog.absolutize(g.verticalCover ?: g.imageUrl).ifBlank { null },
        tags = g.category, developer = g.developer, publisher = g.publisher, description = g.description, owned = true,
        // A cache from before the release date was kept still has it under the older per-game key.
        releaseDate = g.releaseDate.ifEmpty { GogPrefs.get(context).getString("release_${g.gameId}", "").orEmpty() },
        sizeBytes = sizeOf(context, g.gameId),
        storeUrl = "https://www.gog.com/en/game/${g.gameId}",
    )
}
