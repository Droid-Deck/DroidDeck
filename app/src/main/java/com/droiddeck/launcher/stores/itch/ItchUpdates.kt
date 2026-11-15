package com.droiddeck.launcher.stores.itch

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoresState
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

internal class ItchUpdate(val uploadId: String, val updatedAt: String, val filename: String, val replaced: Boolean)

internal object ItchUpdates {
    private const val KEY_AT = "updates_checked_at"
    private const val KEY_FOUND = "updates_found"
    private const val THROTTLE_MS = 6 * 60 * 60 * 1000L
    private const val GAP_MS = 1_000L

    val available = mutableStateMapOf<String, ItchUpdate>()
    var checkingKey by mutableStateOf<String?>(null)
        private set

    private val running = AtomicBoolean(false)
    private val restored = AtomicBoolean(false)
    @Volatile private var generation = 0

    internal fun evaluate(uploads: JSONArray?, uploadId: String, version: String): ItchUpdate? {
        if (uploads == null || version.isBlank()) return null
        val current = ItchUploads.byId(uploads, uploadId)
        if (current != null) {
            val stamp = ItchUploads.updatedAt(current)
            return if (stamp > version) ItchUpdate(uploadId, stamp, ItchUploads.filename(current), false) else null
        }
        val next = ItchUploads.newestWindows(uploads) ?: return null
        val stamp = ItchUploads.updatedAt(next)
        if (uploadId.isBlank() && stamp <= version) return null
        return ItchUpdate(next.optString("id"), stamp, ItchUploads.filename(next), uploadId.isNotBlank())
    }

    internal fun encode(found: Map<String, ItchUpdate>): String = JSONObject().also { out ->
        for ((key, u) in found) out.put(key, JSONObject().put("uploadId", u.uploadId).put("updatedAt", u.updatedAt).put("filename", u.filename).put("replaced", u.replaced))
    }.toString()

    internal fun decode(raw: String?): Map<String, ItchUpdate> {
        val json = runCatching { JSONObject(raw.orEmpty()) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, ItchUpdate>()
        for (key in json.keys()) {
            val u = json.optJSONObject(key) ?: continue
            val id = u.optString("uploadId")
            if (id.isBlank()) continue
            out[key] = ItchUpdate(id, u.optString("updatedAt"), u.optString("filename"), u.optBoolean("replaced"))
        }
        return out
    }

    private fun restore(app: Context) {
        if (!restored.compareAndSet(false, true)) return
        val started = generation
        val saved = decode(ItchPrefs.get(app).getString(KEY_FOUND, null))
        StoresState.post {
            if (started != generation) return@post
            for ((k, v) in saved) if (k !in available) available[k] = v
        }
    }

    private fun save(app: Context) {
        ItchPrefs.get(app).edit().putString(KEY_FOUND, encode(available.toMap())).apply()
    }

    fun check(context: Context) {
        val app = context.applicationContext
        if (!running.compareAndSet(false, true)) return
        val started = generation
        Thread({
            try {
                restore(app)
                val prefs = ItchPrefs.get(app)
                if (System.currentTimeMillis() - prefs.getLong(KEY_AT, 0L) < THROTTLE_MS) return@Thread
                val key = ItchAuth.key(app)
                if (key.isBlank()) return@Thread
                val games = StoresState.installed.toList().filter { it.sidecar.store == Store.ITCH && it.sidecar.isInstalled }
                val found = LinkedHashMap<String, ItchUpdate>()
                val answered = HashSet<String>()
                val snapshot = HashMap<String, Pair<String, String>>()
                var failed = false
                games.forEachIndexed { i, game ->
                    if (i > 0) Thread.sleep(GAP_MS)
                    val extra = game.sidecar.extra
                    snapshot[game.key] = game.sidecar.installVersion to extra["uploadId"].orEmpty()
                    val gameId = extra["gameId"].orEmpty()
                    if (gameId.isNotEmpty()) {
                        val uploads = ItchApi.uploads(key, gameId, extra["downloadKeyId"].orEmpty())
                        if (uploads == null) failed = true
                        else {
                            answered += game.key
                            evaluate(uploads, extra["uploadId"].orEmpty(), game.sidecar.installVersion)?.let { found[game.key] = it }
                        }
                    } else failed = true
                }
                if (started != generation) return@Thread
                StoresState.post {
                    if (started != generation) return@post
                    for (k in answered) {
                        val now = StoresState.installed.firstOrNull { it.key == k } ?: continue
                        if (snapshot[k] != now.sidecar.installVersion to now.sidecar.extra["uploadId"].orEmpty()) continue
                        val u = found[k]
                        if (u != null) available[k] = u else available.remove(k)
                    }
                    save(app)
                }
                if (!failed && started == generation) prefs.edit().putLong(KEY_AT, System.currentTimeMillis()).apply()
                StoresState.logLine("itch.io: checked ${games.size} installed games, ${found.size} with updates")
            } finally {
                running.set(false)
            }
        }, "itch-updates").start()
    }

    fun checkGame(context: Context, game: InstalledStoreGame) {
        val app = context.applicationContext
        if (checkingKey != null) return
        checkingKey = game.key
        val started = generation
        val version = game.sidecar.installVersion
        val uploadId = game.sidecar.extra["uploadId"].orEmpty()
        Thread({
            try {
                val key = ItchAuth.key(app)
                val extra = game.sidecar.extra
                val gameId = extra["gameId"].orEmpty()
                val uploads = if (key.isBlank() || gameId.isEmpty()) null else ItchApi.uploads(key, gameId, extra["downloadKeyId"].orEmpty())
                val found = evaluate(uploads, uploadId, version)
                StoresState.post {
                    if (started != generation) return@post
                    val now = StoresState.installed.firstOrNull { it.key == game.key }
                    if (uploads != null && now != null && now.sidecar.installVersion == version && now.sidecar.extra["uploadId"].orEmpty() == uploadId) {
                        if (found != null) available[game.key] = found else available.remove(game.key)
                        save(app)
                    }
                    val text = when {
                        uploads == null -> app.getString(R.string.stores_sync_failed)
                        found != null -> app.getString(R.string.stores_itch_updates_found, 1)
                        else -> app.getString(R.string.upd_current_status)
                    }
                    Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
                }
                StoresState.logLine("itch.io: checked ${game.sidecar.title}, ${if (found != null) "update available" else "up to date"}")
            } finally {
                StoresState.post { checkingKey = null }
            }
        }, "itch-update-check").start()
    }

    fun start(context: Context, game: InstalledStoreGame, update: ItchUpdate) {
        val app = context.applicationContext
        if (StoresState.uninstalling.containsKey(game.key)) return
        val root = game.folder.parentFile?.parentFile ?: return
        val extra = game.sidecar.extra + ("uploadId" to update.uploadId)
        val item = CatalogItem(
            Store.ITCH, game.sidecar.id, game.sidecar.title, game.sidecar.hero, tallImageUrl = game.sidecar.cover,
            isFree = extra["downloadKeyId"].isNullOrBlank(), owned = true, extra = extra,
        )
        StoresState.install(app, item, root)
    }

    fun applied(context: Context, key: String) {
        val app = context.applicationContext
        StoresState.post {
            available.remove(key)
            save(app)
        }
    }

    fun clear(context: Context) {
        val app = context.applicationContext
        generation++
        ItchPrefs.get(app).edit().remove(KEY_AT).remove(KEY_FOUND).apply()
        StoresState.post { available.clear() }
    }
}
