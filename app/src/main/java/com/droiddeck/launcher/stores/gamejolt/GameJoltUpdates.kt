package com.droiddeck.launcher.stores.gamejolt

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
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

internal class GameJoltUpdate(val buildId: String, val stamp: String, val filename: String, val replaced: Boolean)

internal object GameJoltUpdates {
    private const val KEY_AT = "updates_checked_at"
    private const val KEY_FOUND = "updates_found"
    private const val THROTTLE_MS = 6 * 60 * 60 * 1000L
    private const val GAP_MS = 1_000L

    val available = mutableStateMapOf<String, GameJoltUpdate>()
    var checkingKey by mutableStateOf<String?>(null)
        private set

    private val running = AtomicBoolean(false)
    private val restored = AtomicBoolean(false)
    @Volatile private var generation = 0

    internal fun evaluate(builds: List<GameJoltBuild>?, buildId: String, packageId: String, version: String): GameJoltUpdate? {
        if (builds == null || buildId.isBlank()) return null
        val installable = builds.filter { it.installable }
        val samePackage = installable.filter { it.packageId == packageId }
        if (samePackage.isNotEmpty()) {
            if (samePackage.any { it.id == buildId }) return null
            val same = samePackage.firstOrNull { newer(it, buildId, version) } ?: return null
            return GameJoltUpdate(same.id, same.stamp, same.filename, false)
        }
        val next = installable.firstOrNull() ?: return null
        return if (next.id == buildId) null else GameJoltUpdate(next.id, next.stamp, next.filename, true)
    }

    private fun newer(build: GameJoltBuild, buildId: String, version: String): Boolean {
        val stamp = build.stamp.toLongOrNull() ?: 0L
        val have = version.toLongOrNull() ?: 0L
        if (stamp != have) return stamp > have
        return (build.id.toLongOrNull() ?: 0L) > (buildId.toLongOrNull() ?: 0L)
    }

    internal fun encode(found: Map<String, GameJoltUpdate>): String = JSONObject().also { out ->
        for ((key, u) in found) out.put(key, JSONObject().put("buildId", u.buildId).put("stamp", u.stamp).put("filename", u.filename).put("replaced", u.replaced))
    }.toString()

    internal fun decode(raw: String?): Map<String, GameJoltUpdate> {
        val json = runCatching { JSONObject(raw.orEmpty()) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, GameJoltUpdate>()
        for (key in json.keys()) {
            val u = json.optJSONObject(key) ?: continue
            val id = u.optString("buildId")
            if (id.isBlank()) continue
            out[key] = GameJoltUpdate(id, u.optString("stamp"), u.optString("filename"), u.optBoolean("replaced"))
        }
        return out
    }

    private fun restore(app: Context) {
        if (!restored.compareAndSet(false, true)) return
        val started = generation
        val saved = decode(GameJoltPrefs.get(app).getString(KEY_FOUND, null))
        StoresState.post {
            if (started != generation) return@post
            for ((k, v) in saved) if (k !in available) available[k] = v
        }
    }

    private fun save(app: Context) {
        GameJoltPrefs.get(app).edit().putString(KEY_FOUND, encode(available.toMap())).apply()
    }

    private fun snapshot(game: InstalledStoreGame): Triple<String, String, String> =
        Triple(game.sidecar.installVersion, game.sidecar.extra["buildId"].orEmpty(), game.sidecar.extra["packageId"].orEmpty())

    fun check(context: Context) {
        val app = context.applicationContext
        if (!running.compareAndSet(false, true)) return
        val started = generation
        Thread({
            try {
                restore(app)
                val prefs = GameJoltPrefs.get(app)
                if (System.currentTimeMillis() - prefs.getLong(KEY_AT, 0L) < THROTTLE_MS) return@Thread
                if (GameJoltAuth.cookies(app).isBlank()) return@Thread
                val games = StoresState.installed.toList().filter { it.sidecar.store == Store.GAMEJOLT && it.sidecar.isInstalled }
                val found = LinkedHashMap<String, GameJoltUpdate>()
                val answered = HashSet<String>()
                val seen = HashMap<String, Triple<String, String, String>>()
                var failed = false
                games.forEachIndexed { i, game ->
                    if (i > 0) Thread.sleep(GAP_MS)
                    val extra = game.sidecar.extra
                    seen[game.key] = snapshot(game)
                    val builds = GameJoltBuilds.load(app, extra["gameId"].orEmpty().ifEmpty { game.sidecar.id })
                    if (builds == null) failed = true
                    else {
                        answered += game.key
                        evaluate(builds, extra["buildId"].orEmpty(), extra["packageId"].orEmpty(), game.sidecar.installVersion)?.let { found[game.key] = it }
                    }
                }
                if (started != generation) return@Thread
                StoresState.post {
                    if (started != generation) return@post
                    for (k in answered) {
                        val now = StoresState.installed.firstOrNull { it.key == k } ?: continue
                        if (seen[k] != snapshot(now)) continue
                        val u = found[k]
                        if (u != null) available[k] = u else available.remove(k)
                    }
                    save(app)
                }
                if (!failed && started == generation) prefs.edit().putLong(KEY_AT, System.currentTimeMillis()).apply()
                StoresState.logLine("Game Jolt: checked ${games.size} installed games, ${found.size} with updates")
            } finally {
                running.set(false)
            }
        }, "gamejolt-updates").start()
    }

    fun checkGame(context: Context, game: InstalledStoreGame) {
        val app = context.applicationContext
        if (checkingKey != null) return
        checkingKey = game.key
        val started = generation
        val before = snapshot(game)
        Thread({
            try {
                val extra = game.sidecar.extra
                val builds = GameJoltBuilds.load(app, extra["gameId"].orEmpty().ifEmpty { game.sidecar.id })
                val found = evaluate(builds, before.second, before.third, before.first)
                StoresState.post {
                    if (started != generation) return@post
                    val now = StoresState.installed.firstOrNull { it.key == game.key }
                    if (builds != null && now != null && snapshot(now) == before) {
                        if (found != null) available[game.key] = found else available.remove(game.key)
                        save(app)
                    }
                    val text = when {
                        builds == null -> app.getString(R.string.stores_sync_failed)
                        found != null -> app.getString(R.string.stores_itch_updates_found, 1)
                        else -> app.getString(R.string.upd_current_status)
                    }
                    Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
                }
                StoresState.logLine("Game Jolt: checked ${game.sidecar.title}, ${if (found != null) "update available" else "up to date"}")
            } finally {
                StoresState.post { checkingKey = null }
            }
        }, "gamejolt-update-check").start()
    }

    fun start(context: Context, game: InstalledStoreGame, update: GameJoltUpdate) {
        val app = context.applicationContext
        if (StoresState.uninstalling.containsKey(game.key)) return
        val root = game.folder.parentFile?.parentFile ?: return
        val item = CatalogItem(
            Store.GAMEJOLT, game.sidecar.id, game.sidecar.title, game.sidecar.hero, tallImageUrl = game.sidecar.cover,
            isFree = game.sidecar.extra["paid"] != "1", owned = true, extra = game.sidecar.extra + ("buildId" to update.buildId),
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
        GameJoltPrefs.get(app).edit().remove(KEY_AT).remove(KEY_FOUND).apply()
        StoresState.post { available.clear() }
    }
}
