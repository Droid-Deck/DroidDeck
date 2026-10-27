package com.droiddeck.launcher.stores.xbox

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableStateListOf
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoresState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Xbox games added to the Games tab to stream before (or without) installing them. */
object XboxCloudGames {
    class Entry(val id: String, val title: String, val poster: String?, val hero: String?)

    private const val PREFS = "xbox_cloud_games"
    private val idPattern = Regex("[A-Z0-9]{12}")
    val entries = mutableStateListOf<Entry>()
    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val list = decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("games", null))
        StoresState.post { entries.clear(); entries.addAll(list) }
    }

    fun isAdded(id: String) = entries.any { it.id == id }

    fun add(context: Context, item: CatalogItem) {
        val app = context.applicationContext
        if (isAdded(item.id)) return
        entries.add(Entry(item.id, item.title, item.tallImageUrl, item.imageUrl))
        save(app)
        Thread({
            fetch(item.tallImageUrl, File(artDir(app, item.id), "poster"))
            fetch(item.imageUrl, File(artDir(app, item.id), "hero"))
            StoresState.post { StoresState.notifyLibraryChanged(app) }
        }, "xbox-cloud-art").start()
    }

    fun remove(context: Context, id: String) {
        val app = context.applicationContext
        entries.removeAll { it.id == id }
        save(app)
        artDir(app, id).deleteRecursively()
    }

    private fun save(app: Context) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("games", encode(entries.toList())).apply()
    }

    internal fun encode(list: List<Entry>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("poster", it.poster.orEmpty()).put("hero", it.hero.orEmpty())) }
    }.toString()

    internal fun decode(text: String?): List<Entry> = runCatching {
        val array = JSONArray(text ?: return emptyList())
        (0 until minOf(array.length(), 2000)).mapNotNull { array.optJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("id")
            val title = o.optString("title")
            if (!idPattern.matches(id) || title.isBlank()) null
            else Entry(id, title.take(200), o.optString("poster").takeIf { it.isNotEmpty() }, o.optString("hero").takeIf { it.isNotEmpty() })
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    /** Games-tab rows for the added games that are not installed (an installed copy has its own row). */
    fun rows(context: Context, installedIds: Set<String>): List<Library.SteamGame> = entries.filter { it.id !in installedIds }.map { e ->
        val dir = artDir(context, e.id)
        val appId = rowId(e.id)
        Library.SteamGame(appId = appId, name = e.title, art = File(dir, "poster").takeIf { it.isFile }, library = Library.XBOX_CLOUD,
            hero = File(dir, "hero").takeIf { it.isFile }, source = Store.XBOX.id, storeId = e.id)
    }

    internal fun rowId(id: String): Int = ("xbox-cloud:$id").hashCode() or Int.MIN_VALUE

    private fun artDir(context: Context, id: String) = File(context.filesDir, "xbox-cloud-art/$id")

    private fun fetch(url: String?, target: File) {
        if (url == null) return
        runCatching {
            val parsed = URL(url)
            require(parsed.protocol == "https" && parsed.host in setOf("store-images.s-microsoft.com", "store-images.microsoft.com"))
            val connection = parsed.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000; connection.readTimeout = 30_000
                check(connection.responseCode == 200)
                val bytes = connection.inputStream.use { it.readBytes() }
                require(bytes.size in 1..12_000_000)
                target.parentFile?.mkdirs()
                File(target.path + ".tmp").apply { writeBytes(bytes); check(renameTo(target)) }
            } finally { connection.disconnect() }
        }
    }

    private val prefetching = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Fetches the streaming tokens in the background while a cloud game is on screen (about 20 s), so Stream starts at
     * once; both players take them from this process (the native one through XboxCloudTokenService).
     */
    fun prefetch(context: Context) {
        val app = context.applicationContext
        if (!prefetching.compareAndSet(false, true)) return
        Thread({
            try {
                val account = com.droiddeck.launcher.stores.StoreAccounts.signedInAs(app, Store.XBOX) ?: return@Thread
                runCatching { XboxCloudSession.get(app, account) }
                    .onFailure { StoresState.logLine("Xbox: cloud tokens not fetched ahead: ${it.message?.take(160)}") }
            } finally { prefetching.set(false) }
        }, "xcloud-prefetch").start()
    }

    /** Streaming runs outside the Linux session; the session owns the screen, audio and controllers while it runs. */
    fun stream(context: Context, id: String, title: String) {
        if (SessionState.running) {
            android.widget.Toast.makeText(context, "Close the Steam session to stream Xbox games.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        context.startActivity(XboxCloudPlayerActivity.playerIntent(context, id, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Whether the signed-in library says this game streams (cached library; reads disk). */
    fun streams(context: Context, id: String): Boolean = runCatching {
        StoresState.library[Store.XBOX]?.firstOrNull { it.id == id }?.let { return it.xboxCloud }
        val app = context.applicationContext
        val account = com.droiddeck.launcher.stores.StoreAccounts.signedInAs(app, Store.XBOX) ?: return false
        XboxLibraryCache.read(app, account, Xodus.market())?.games?.firstOrNull { it.id == id }?.xboxCloud == true
    }.getOrDefault(false)
}
