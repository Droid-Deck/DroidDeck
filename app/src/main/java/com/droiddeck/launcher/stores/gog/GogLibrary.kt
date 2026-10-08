package com.droiddeck.launcher.stores.gog

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreNet
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The signed-in account's GOG library: the owned ids from embed.gog.com, each product's details
 * from api.gog.com, and a cache so the tab paints at once. A sync fetches only what the cache
 * lacks unless forced or 15 minutes stale. Ported from Bannerlator's GogLibraryRepo.
 */
object GogLibrary {
    private const val TAG = "GogLibrary"
    // v2: the card art moved from the store page's backdrop to the Galaxy library art; an older cache is re-fetched.
    private const val CACHE_KEY = "library_cache_v2"
    private const val LAST_SYNC_KEY = "library_synced_at"
    private const val THROTTLE_MS = 15L * 60L * 1000L

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
                GogGame(o.optString("gameId", ""), o.optString("title", ""), o.optString("imageUrl", ""), o.optString("description", ""),
                    o.optString("developer", ""), o.optString("category", ""), o.optInt("generation", 1), o.optString("verticalCover", "").ifEmpty { null })
            }.filter { it.gameId.isNotEmpty() }.sortedBy { it.title.lowercase() }
        }.getOrDefault(emptyList())
    }

    fun sizeOf(context: Context, gameId: String): Long = GogPrefs.get(context).getLong("size_$gameId", 0L)

    /** Owned ids diffed against the cache; fetches what is new, or everything when forced / empty / stale. Blocking. */
    fun sync(context: Context, force: Boolean = false, onStatus: (String) -> Unit = {}): SyncResult {
        if (!syncing.compareAndSet(false, true)) return SyncResult.Busy
        try {
            val app = context.applicationContext
            val p = GogPrefs.get(app)
            val token = GogAuth.validToken(app) ?: return SyncResult.NotLoggedIn
            onStatus("Fetching game list…")
            val gamesJson = StoreNet.get("https://embed.gog.com/user/data/games", bearer = token, userAgent = GogAuth.GALAXY_UA)
                ?: return SyncResult.Failed("Couldn't reach GOG")
            val ids = ArrayList<String>()
            val parsed = runCatching {
                val owned = JSONObject(gamesJson).optJSONArray("owned")
                if (owned != null) for (i in 0 until owned.length()) {
                    val id = owned.optLong(i).toString()
                    // 1801418160 is GOG's own "Galaxy" entry, in every library.
                    if (id != "1801418160" && id != "0") ids.add(id)
                }
            }.isSuccess
            if (!parsed) return SyncResult.Failed("Error parsing library")

            val ownedSet = ids.toHashSet()
            val cachedList = cached(app)
            val stale = System.currentTimeMillis() - p.getLong(LAST_SYNC_KEY, 0L) >= THROTTLE_MS
            val heavy = force || cachedList.isEmpty() || stale
            val cachedIds = cachedList.mapTo(HashSet()) { it.gameId }
            val idsToFetch = if (heavy) ids else ids.filter { it !in cachedIds }
            val fetchSet = idsToFetch.toHashSet()
            val merged = LinkedHashMap<String, GogGame>()
            for (g in cachedList) if (g.gameId in ownedSet && g.gameId !in fetchSet) merged[g.gameId] = g
            if (idsToFetch.isEmpty()) {
                val list = merged.values.toList()
                saveCache(p, list)
                return SyncResult.Ok(list.sortedBy { it.title.lowercase() }, 0)
            }
            onStatus("Syncing ${idsToFetch.size} game${if (idsToFetch.size == 1) "" else "s"}…")
            val pool = Executors.newFixedThreadPool(5)
            val futures = idsToFetch.map { id -> pool.submit(Callable<GogGame?> { fetchGame(p, id, token) }) }
            pool.shutdown()
            var fetched = 0
            for ((idx, f) in futures.withIndex()) {
                val g = runCatching { f.get() }.getOrNull()
                if (g != null) { merged[g.gameId] = g; fetched++ }
                if ((idx + 1) % 5 == 0) onStatus("Syncing… ${idx + 1}/${futures.size}")
            }
            val finalList = merged.values.toList()
            saveCache(p, finalList)
            if (heavy) p.edit().putLong(LAST_SYNC_KEY, System.currentTimeMillis()).apply()
            Log.i(TAG, "sync: owned=${ids.size} fetched=$fetched cached=${finalList.size} heavy=$heavy")
            return SyncResult.Ok(finalList.sortedBy { it.title.lowercase() }, fetched)
        } catch (e: Exception) {
            Log.w(TAG, "sync failed: ${e.message}")
            return SyncResult.Failed(e.message ?: "Sync failed")
        } finally {
            syncing.set(false)
        }
    }

    /** One product's details, or null for anything that is not a Windows game (DLC, movies, other platforms). */
    private fun fetchGame(prefs: SharedPreferences, id: String, token: String): GogGame? {
        try {
            val productJson = StoreNet.get("https://api.gog.com/products/$id?expand=downloads,description", bearer = token) ?: return null
            val prod = JSONObject(productJson)
            if (prod.optBoolean("is_secret", false)) return null
            val gameType = prod.optString("game_type", "")
            if (gameType.isNotEmpty() && gameType != "game" && gameType != "pack") return null
            var title = prod.optJSONObject("title")?.optString("*")
            if (title.isNullOrEmpty()) title = prod.optString("title")
            if (title.isNullOrEmpty()) return null
            // Card art from the v2 game record, the way the Galaxy client draws its library: the dark
            // Galaxy background for the wide card and the box art for the tall one. The product's
            // `images.background` is the store page's fade-out backdrop (it ends in white on a
            // card) and is never used; the logo or icon is the last resort.
            val images = prod.optJSONObject("images")
            var imageUrl = ""
            var boxArt = ""
            runCatching {
                val links = StoreNet.get("https://api.gog.com/v2/games/$id?locale=en-US")?.let { JSONObject(it).optJSONObject("_links") }
                if (links != null) {
                    imageUrl = links.optJSONObject("galaxyBackgroundImage")?.optString("href", "").orEmpty()
                    boxArt = links.optJSONObject("boxArtImage")?.optString("href", "").orEmpty()
                }
            }
            if (imageUrl.isEmpty()) imageUrl = images?.optString("logo2x", "") ?: ""
            if (imageUrl.isEmpty()) imageUrl = images?.optString("icon", "") ?: ""
            val desc = prod.optJSONObject("description")?.optString("lead", "") ?: ""
            val developer = prod.optJSONObject("developers")?.optString("name", "") ?: prod.optString("developer", "")
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
            val ed = prefs.edit().putInt("gen_$id", generation)
            prod.optString("release_date", "").takeIf { it.isNotEmpty() }?.let { ed.putString("release_$id", it) }
            ed.apply()
            if (prefs.getLong("size_$id", -1L) <= 0) {
                val size = GogDownloadManager.fetchInstallSizeBytes(id, token)
                if (size > 0) prefs.edit().putLong("size_$id", size).apply()
            }
            var hasWindowsInstaller = false
            prod.optJSONObject("downloads")?.optJSONArray("installers")?.let { arr ->
                for (di in 0 until arr.length()) if (arr.optJSONObject(di)?.optString("os", "") == "windows") { hasWindowsInstaller = true; break }
            }
            if (!hasWindowsBuild && !hasWindowsInstaller) return null

            var verticalCover: String? = boxArt.ifEmpty { prefs.getString("vcover_$id", null) }
            if (verticalCover.isNullOrEmpty()) {
                verticalCover = fetchVerticalCover(id).ifEmpty { StoreNet.sgdbPoster(title) }
            }
            if (!verticalCover.isNullOrEmpty()) prefs.edit().putString("vcover_$id", verticalCover).apply()
            return GogGame(id, title, GogStoreCatalog.absolutize(imageUrl), desc, developer, category, generation, verticalCover?.ifEmpty { null })
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
                    .put("developer", g.developer).put("category", g.category).put("generation", g.generation)
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
        tags = g.category, developer = g.developer, description = g.description, owned = true,
        sizeBytes = sizeOf(context, g.gameId),
        storeUrl = "https://www.gog.com/en/game/${g.gameId}",
    )
}
