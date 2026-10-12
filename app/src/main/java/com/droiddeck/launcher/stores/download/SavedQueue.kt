package com.droiddeck.launcher.stores.download

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoresState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * The download queue kept on disk, so what was queued, running or paused comes back after the app
 * closes or is killed: the queue itself lives in memory, and a queued game that never started has
 * nothing on disk to find. Each install records the game and where it goes ([remember]); every
 * change of state writes the active rows in queue order ([save]); the first refresh after the app
 * starts asks the stores for them again ([restore]) - running and queued ones carry on, paused ones
 * come back paused.
 */
object SavedQueue {
    private const val TAG = "StoreDownloads"
    private const val FILE = "store-queue.json"

    private class Wanted(val item: CatalogItem, val root: File?)

    private val wanted = HashMap<String, Wanted>()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "store-queue-save") }
    @Volatile private var app: Context? = null
    private var restored = false

    private fun key(item: CatalogItem) = "${item.store.id}:${item.id}"

    /** An install is asked for: what to ask the store for again after a restart. */
    @Synchronized
    fun remember(context: Context, item: CatalogItem, root: File?) {
        app = context.applicationContext
        wanted[key(item)] = Wanted(item, root)
    }

    /** The active rows, in queue order, as they are now. Any thread; written off it. */
    fun save(entries: List<DownloadEntry>) {
        val context = app ?: return
        val rows = synchronized(this) {
            entries.filter { it.isActive }.mapNotNull { e -> wanted[e.key]?.let { e to it } }
        }
        val out = JSONArray()
        for ((e, w) in rows) out.put(JSONObject().apply {
            put("paused", e.state == DownloadState.PAUSED)
            put("root", w.root?.absolutePath ?: "")
            put("item", itemJson(w.item))
        })
        writer.execute {
            runCatching {
                val f = File(context.filesDir, FILE)
                val tmp = File(context.filesDir, "$FILE.tmp")
                tmp.writeText(out.toString())
                tmp.renameTo(f)
            }.onFailure { Log.w(TAG, "could not save the queue", it) }
        }
    }

    /** Once per process: queues again what was in the queue when the app last closed. */
    fun restore(context: Context) {
        synchronized(this) { if (restored) return; restored = true }
        val app = context.applicationContext
        // Read before anything may write it: an install started at once must not save over it.
        val rows = runCatching { JSONArray(File(app.filesDir, FILE).readText()) }.getOrNull()
        this.app = app
        rows ?: return
        Thread({
            val back = (0 until rows.length()).mapNotNull { i ->
                runCatching {
                    val o = rows.getJSONObject(i)
                    Triple(itemOf(o.getJSONObject("item")), o.optString("root").takeIf { it.isNotEmpty() }?.let(::File), o.optBoolean("paused"))
                }.getOrNull()
            }
            if (back.isEmpty()) return@Thread
            StoresState.post {
                for ((item, root, paused) in back) {
                    if (DownloadQueue.entry(key(item)) != null) continue
                    if (paused) DownloadQueue.holdPaused(key(item))
                    StoresState.install(app, item, root)
                }
            }
        }, "store-queue-restore").start()
    }

    private fun itemJson(i: CatalogItem) = JSONObject().apply {
        put("store", i.store.id); put("id", i.id); put("title", i.title)
        put("image", i.imageUrl ?: ""); put("tall", i.tallImageUrl ?: "")
        put("tags", i.tags); put("developer", i.developer); put("description", i.description)
        put("size", i.sizeBytes); put("owned", i.owned)
        put("extra", JSONObject(i.extra))
    }

    private fun itemOf(o: JSONObject): CatalogItem {
        val extra = o.optJSONObject("extra")
        return CatalogItem(
            store = Store.byId(o.getString("store")) ?: error("unknown store"),
            id = o.getString("id"), title = o.getString("title"),
            imageUrl = o.optString("image").ifEmpty { null }, tallImageUrl = o.optString("tall").ifEmpty { null },
            tags = o.optString("tags"), developer = o.optString("developer"), description = o.optString("description"),
            owned = o.optBoolean("owned", true), sizeBytes = o.optLong("size"),
            extra = extra?.keys()?.asSequence()?.associateWith { extra.getString(it) } ?: emptyMap(),
        )
    }
}
