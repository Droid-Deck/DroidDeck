package com.droiddeck.launcher.stores.gamejolt

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.mergeItems
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

internal object GameJoltLibrary {
    private const val MAX_PAGES = 20
    private const val THROTTLE_MS = 10 * 60 * 1000L

    private val running = AtomicBoolean(false)
    @Volatile private var syncedAt = 0L

    fun sync(context: Context, force: Boolean) {
        val app = context.applicationContext
        if (!running.compareAndSet(false, true)) return
        try {
            val cookies = GameJoltAuth.cookies(app)
            val name = GameJoltAuth.username(app)
            if (cookies.isBlank() || name.isBlank()) {
                StoresState.post {
                    StoresState.status.remove(Store.GAMEJOLT)
                    StoresState.problems[Store.GAMEJOLT] = app.getString(R.string.stores_sync_sign_in)
                }
                return
            }
            val have = StoresState.library[Store.GAMEJOLT].orEmpty()
            if (!force && have.isNotEmpty() && System.currentTimeMillis() - syncedAt < THROTTLE_MS) return
            if (have.isEmpty()) StoresState.post { StoresState.status[Store.GAMEJOLT] = app.getString(R.string.stores_sync_game_list) }
            val items = games(app, "/web/library/games/owned/@${URLEncoder.encode(name, "UTF-8")}", true)
            if (items == null) {
                StoresState.post {
                    StoresState.status.remove(Store.GAMEJOLT)
                    StoresState.problems[Store.GAMEJOLT] = app.getString(R.string.stores_sync_failed)
                }
                return
            }
            syncedAt = System.currentTimeMillis()
            StoresState.post {
                StoresState.status.remove(Store.GAMEJOLT)
                StoresState.problems.remove(Store.GAMEJOLT)
                StoresState.library[Store.GAMEJOLT] = mergeItems(StoresState.library[Store.GAMEJOLT], items)
            }
            StoresState.logLine("Game Jolt: ${items.size} owned games")
        } finally {
            running.set(false)
        }
    }

    fun reset() {
        syncedAt = 0L
    }

    fun games(context: Context, path: String, owned: Boolean): List<CatalogItem>? {
        val cookies = GameJoltAuth.cookies(context)
        val out = ArrayList<CatalogItem>()
        val seen = HashSet<String>()
        for (page in 1..MAX_PAGES) {
            val answer = GameJoltApi.get(if (page == 1) path else "$path?page=$page", cookies)
            if (!answer.ok) return if (page == 1) null else out
            val batch = GameJoltCatalog.items(answer.payload?.optJSONArray("games"), owned).filter { seen.add(it.id) }
            if (batch.isEmpty()) break
            out.addAll(batch)
            val total = answer.payload?.optInt("totalGamesCount") ?: 0
            if (total in 1..out.size) break
        }
        return out
    }
}
