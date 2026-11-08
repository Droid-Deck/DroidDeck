package com.droiddeck.launcher.stores.ea

import android.content.Context
import android.content.SharedPreferences
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreNet
import com.droiddeck.launcher.stores.StoreShelves
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.mergeItems
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object EaPrefs {
    const val FILE = "stores.ea"
    fun get(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

data class EaGame(
    val offerId: String,
    val contentId: String,
    val title: String,
    val slug: String,
    val tall: String = "",
    val wide: String = "",
    val logo: String = "",
    val version: String = "",
    val executePath: String = "",
    val installCheck: String = "",
    val parameters: String = "",
    val multiplayerId: String = "",
    val masterTitleId: String = "",
    val cloudSaves: String = "",
    val locales: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("offerId", offerId).put("contentId", contentId).put("title", title).put("slug", slug)
        .put("tall", tall).put("wide", wide).put("logo", logo).put("version", version)
        .put("executePath", executePath).put("installCheck", installCheck).put("parameters", parameters)
        .put("multiplayerId", multiplayerId).put("masterTitleId", masterTitleId).put("cloudSaves", cloudSaves)
        .put("locales", JSONArray(locales))

    companion object {
        fun fromJson(o: JSONObject): EaGame? {
            val locales = o.optJSONArray("locales")
            return EaGame(
                offerId = o.optString("offerId").ifEmpty { return null },
                contentId = o.optString("contentId"),
                title = o.optString("title"),
                slug = o.optString("slug"),
                tall = o.optString("tall"),
                wide = o.optString("wide"),
                logo = o.optString("logo"),
                version = o.optString("version"),
                executePath = o.optString("executePath"),
                installCheck = o.optString("installCheck"),
                parameters = o.optString("parameters"),
                multiplayerId = o.optString("multiplayerId"),
                masterTitleId = o.optString("masterTitleId"),
                cloudSaves = o.optString("cloudSaves"),
                locales = (0 until (locales?.length() ?: 0)).map { locales!!.optString(it) },
            )
        }
    }
}

object EaLibrary {
    private const val KEY_CACHE = "library_cache"
    private const val KEY_SYNCED_AT = "library_synced_at"
    private const val THROTTLE_MS = 6 * 60 * 60 * 1000L
    private const val ART_TTL_MS = 7 * 24 * 60 * 60 * 1000L
    private const val ART_THREADS = 4
    const val STORE_URL = "https://www.ea.com/ea-app/library"
    private val gate = SyncGate()
    private val generation = java.util.concurrent.atomic.AtomicInteger()

    /** Drops the cache and makes any sync already running drop its results, in one step. */
    fun reset(context: Context) {
        synchronized(cacheLock) {
            generation.incrementAndGet()
            EaPrefs.get(context).edit().remove(KEY_CACHE).remove(KEY_SYNCED_AT).commit()
        }
    }

    internal fun snapshot(app: Context): Pair<Int, List<EaGame>> = synchronized(cacheLock) { generation.get() to cached(app) }

    internal fun storeCache(app: Context, games: List<EaGame>, gen: Int): Boolean = synchronized(cacheLock) {
        gen == generation.get() &&
            EaPrefs.get(app).edit().putString(KEY_CACHE, JSONArray(games.map { it.toJson() }).toString()).putLong(KEY_SYNCED_AT, System.currentTimeMillis()).commit()
    }

    private val cacheLock = Any()
    private val store = Store.EA

    fun cached(context: Context): List<EaGame> {
        val text = EaPrefs.get(context).getString(KEY_CACHE, null) ?: return emptyList()
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(EaGame::fromJson) }
    }

    fun game(context: Context, offerId: String): EaGame? = cached(context).firstOrNull { it.offerId == offerId }


    fun toCatalogItem(context: Context, g: EaGame): CatalogItem {
        val prefs = EaPrefs.get(context)
        val tall = g.tall.ifEmpty { prefs.getString("poster_${g.offerId}", null).orEmpty() }
        return CatalogItem(
            store = store,
            id = g.offerId,
            title = g.title.ifBlank { g.slug },
            imageUrl = g.wide.ifEmpty { tall }.ifEmpty { null },
            tallImageUrl = tall.ifEmpty { g.wide }.ifEmpty { null },
            publisher = "Electronic Arts",
            owned = true,
            sizeBytes = prefs.getLong("size_${g.offerId}", 0L),
            storeUrl = STORE_URL,
            extra = mapOf("contentId" to g.contentId, "slug" to g.slug, "version" to g.version),
            logoUrl = g.logo.ifEmpty { null },
        )
    }

    /** One entry per game: owned editions of the same base game collapse onto the base offer. */
    internal fun pick(owned: List<EaApi.OwnedProduct>, offers: Map<String, EaApi.Offer>): List<Pair<EaApi.OwnedProduct, EaApi.Offer>> {
        val usable = owned.filter { o ->
            val offer = offers[o.offerId] ?: return@filter false
            !o.trial && o.downloadable && offer.downloadable &&
                (offer.displayType.isEmpty() || offer.displayType.equals("FullGame", true)) &&
                (o.gameType.isEmpty() || o.gameType.equals("BASE_GAME", true))
        }
        return usable.groupBy { it.baseSlug.ifEmpty { it.slug }.ifEmpty { it.offerId } }.values.map { group ->
            val chosen = group.firstOrNull { it.baseSlug.isEmpty() } ?: group.first()
            chosen to offers.getValue(chosen.offerId)
        }.sortedBy { it.first.name.lowercase() }
    }

    private fun art(app: Context, token: String, slugs: Collection<String>, gen: Int): Map<String, EaApi.Images> {
        val prefs = EaPrefs.get(app)
        val now = System.currentTimeMillis()
        val out = java.util.concurrent.ConcurrentHashMap<String, EaApi.Images>()
        val pool = Executors.newFixedThreadPool(ART_THREADS)
        try {
            for (slug in slugs.filter { it.isNotEmpty() }) {
                if (gen != generation.get()) break
                val cached = prefs.getString("art_$slug", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (cached != null && now - cached.optLong("at") < ART_TTL_MS) {
                    out[slug] = EaApi.Images(cached.optString("tall").ifEmpty { null }, cached.optString("wide").ifEmpty { null }, cached.optString("logo").ifEmpty { null })
                    continue
                }
                pool.execute {
                    if (gen != generation.get()) return@execute
                    val images = runCatching { EaApi.images(token, slug) }.getOrNull() ?: return@execute
                    out[slug] = images
                    prefs.edit().putString(
                        "art_$slug",
                        JSONObject().put("at", now).put("tall", images.tall.orEmpty()).put("wide", images.wide.orEmpty()).put("logo", images.logo.orEmpty()).toString(),
                    ).apply()
                }
            }
        } finally {
            pool.shutdown()
            pool.awaitTermination(2, TimeUnit.MINUTES)
        }
        return out
    }

    private fun posters(app: Context, games: List<EaGame>, gen: Int) {
        val prefs = EaPrefs.get(app)
        val todo = games.filter { it.tall.isEmpty() && !prefs.contains("poster_${it.offerId}") }.take(40)
        if (todo.isEmpty()) return
        val pool = Executors.newFixedThreadPool(ART_THREADS)
        try {
            for (g in todo) pool.execute {
                if (gen != generation.get()) return@execute
                prefs.edit().putString("poster_${g.offerId}", runCatching { StoreNet.sgdbPoster(g.title) }.getOrDefault("")).apply()
            }
        } finally {
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.MINUTES)
        }
    }

    private fun publish(app: Context, games: List<EaGame>, gen: Int = generation.get()) {
        if (gen != generation.get()) return
        val items = games.map { toCatalogItem(app, it) }
        StoresState.post {
            if (gen != generation.get()) return@post
            StoresState.library[store] = mergeItems(StoresState.library[store], items)
            val installed = StoresState.installed.filter { it.sidecar.store == store }.map { it.sidecar.id }.toSet()
            StoresState.shelves[store] = StoreShelves(trending = items.filterNot { it.id in installed }, art = StoresState.shelves[store]?.art.orEmpty())
        }
    }

    private inline fun <T> withToken(app: Context, token: String, call: (String) -> T): T = try {
        call(token)
    } catch (e: EaApiException) {
        if (e.status != 401) throw e
        call(EaAuth.refreshNow(app, token) ?: throw e)
    }

    fun sync(context: Context, force: Boolean) {
        if (!gate.enter()) return
        var forced = force
        do {
            runCatching { syncOnce(context.applicationContext, forced) }
            forced = true
        } while (gate.leave())
    }

    private fun syncOnce(app: Context, force: Boolean) {
        val (gen, cache) = snapshot(app)
        val prefs = EaPrefs.get(app)
        val quiet = cache.isNotEmpty() && !force
        fun status(id: Int) { if (!quiet) StoresState.post { StoresState.status[store] = app.getString(id) } }
        fun problem(text: String?) {
            StoresState.post {
                StoresState.status.remove(store)
                if (text == null) StoresState.problems.remove(store) else StoresState.problems[store] = text
            }
        }
        try {
            if (cache.isNotEmpty()) publish(app, cache, gen)
            if (!force && cache.isNotEmpty() && System.currentTimeMillis() - prefs.getLong(KEY_SYNCED_AT, 0L) in 0 until THROTTLE_MS) {
                return problem(null)
            }
            status(R.string.stores_sync_sign_in)
            val token = EaAuth.accessToken(app) ?: return problem(StoresState.notSignedInLine(store))
            status(R.string.stores_sync_game_list)
            val owned = withToken(app, token) { EaApi.owned(it) }
            val offers = withToken(app, token) { EaApi.offers(it, owned.map { o -> o.offerId }) }.associateBy { it.offerId }
            val picked = pick(owned, offers)
            StoresState.logLine("ea: ${owned.size} owned products, ${picked.size} games")
            if (picked.isEmpty()) return problem(if (cache.isEmpty()) app.getString(R.string.stores_sync_no_games, store.label) else null)
            if (gen != generation.get()) return
            status(R.string.stores_sync_cover_art)
            val images = art(app, token, picked.map { it.first.slug }, gen)
            val games = picked.map { (o, offer) ->
                val img = images[o.slug]
                EaGame(
                    offerId = o.offerId, contentId = offer.contentId, title = o.name.ifBlank { offer.displayName }, slug = o.slug,
                    tall = img?.tall.orEmpty(), wide = img?.wide.orEmpty(), logo = img?.logo.orEmpty(), version = offer.gameVersion,
                    executePath = offer.executePathOverride, installCheck = offer.installCheckOverride, parameters = offer.executeParameters,
                    multiplayerId = offer.multiplayerId, masterTitleId = offer.masterTitleId, cloudSaves = offer.cloudSaves, locales = offer.locales,
                )
            }
            if (!storeCache(app, games, gen)) return
            posters(app, games, gen)
            if (gen != generation.get()) return
            publish(app, games, gen)
            problem(null)
        } catch (e: EaApiException) {
            StoresState.logLine("ea: library sync failed: ${e.message}")
            if (e.status == 401) StoresState.markSignInExpired(store)
            problem(if (cache.isEmpty()) e.message else null)
        } catch (e: Exception) {
            StoresState.logLine("ea: library sync failed: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            problem(if (cache.isEmpty()) e.message ?: app.getString(R.string.stores_sync_failed) else null)
        }
    }
}

/** One sync at a time; a request made meanwhile makes the running one go round again rather than being lost. */
internal class SyncGate {
    private var busy = false
    private var again = false

    /** True when the caller should run; false when a running sync will pick the request up. */
    @Synchronized
    fun enter(): Boolean {
        if (busy) {
            again = true
            return false
        }
        busy = true
        return true
    }

    /** True when another round was requested while running; the caller stays the runner. */
    @Synchronized
    fun leave(): Boolean {
        if (again) {
            again = false
            return true
        }
        busy = false
        return false
    }
}
