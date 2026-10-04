package com.droiddeck.launcher.store

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.UserApps

/**
 * The Desktop page's added apps and the one add or remove in progress. State changes only on the
 * main thread; the work runs on a thread of its own and posts back.
 */
object UserAppsState {
    private val main = Handler(Looper.getMainLooper())

    var items by mutableStateOf<List<UserApps.App>>(emptyList())
        private set
    /** What is being added or removed; null when idle. */
    var working by mutableStateOf<String?>(null)
        private set
    var stage by mutableStateOf<String?>(null)
        private set
    var percent by mutableStateOf(-1)
        private set
    /** Why the last add or remove failed, shown on the page until the next one. */
    var lastError by mutableStateOf<String?>(null)
        private set
    /** The app whose updates are being looked up, and what each lookup found, by app key. */
    var checking by mutableStateOf<String?>(null)
        private set
    var updates by mutableStateOf<Map<String, UserApps.UpdateCheck>>(emptyMap())
        private set
    /** Bumped per listing; only the newest one is shown. */
    private var listing = 0

    fun refresh(context: Context) {
        val app = context.applicationContext
        val seq = ++listing
        Thread({
            val list = runCatching { UserApps.list(app) }.getOrDefault(emptyList())
            main.post { if (seq == listing) items = list }
        }, "user-apps-list").start()
    }

    fun add(context: Context, request: UserApps.Request, label: String) =
        run(context, label, R.string.user_apps_added) { app, progress -> UserApps.add(app, request, progress) }

    fun remove(context: Context, target: UserApps.App) {
        updates = updates - target.key
        run(context, target.name, R.string.user_apps_removed) { app, progress -> UserApps.remove(app, target, progress) }
    }

    fun edit(context: Context, target: UserApps.App, name: String, icon: String?) =
        run(context, name, R.string.user_apps_saved) { app, _ -> UserApps.edit(app, target, name, icon) }

    fun checkUpdate(context: Context, target: UserApps.App) {
        if (checking != null) return
        val app = context.applicationContext
        checking = target.key
        updates = updates - target.key
        Thread({
            val result = try {
                UserApps.checkUpdate(app, target)
            } catch (e: Exception) {
                UserApps.UpdateCheck.Failed(e.message ?: e.toString())
            }
            main.post { checking = null; updates = updates + (target.key to result) }
        }, "user-apps-check").start()
    }

    fun update(context: Context, target: UserApps.App, release: UserApps.Release) =
        run(context, target.name, R.string.user_apps_updated) { app, progress ->
            UserApps.update(app, target, release, progress).also { if (it == null) main.post { updates = updates - target.key } }
        }

    private fun run(context: Context, label: String, done: Int, work: (Context, (String, Int) -> Unit) -> String?) {
        if (working != null) return
        val app = context.applicationContext
        working = label; stage = null; percent = -1; lastError = null
        Thread({
            val problem = try {
                work(app) { s, p -> main.post { stage = s; percent = p } }
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            main.post {
                working = null; stage = null; percent = -1
                lastError = problem?.let { "$label: $it" }
                if (problem == null) Toast.makeText(app, app.getString(done, label), Toast.LENGTH_SHORT).show()
                refresh(app)
                StoreState.refresh(app)
            }
        }, "user-apps-work").start()
    }
}
