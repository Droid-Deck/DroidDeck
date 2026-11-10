package com.droiddeck.launcher.session

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.LinuxWindowActivity
import com.droiddeck.launcher.agent.AgentGuest
import com.droiddeck.launcher.wayland.WaylandCompositor
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class LinuxWindow(
    val key: String,
    val title: String,
    val program: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val icon: Bitmap?,
    val iconName: String? = null,
    val desktop: Boolean = false,
    val minimized: Boolean = false,
)

data class LinuxWindowEditor(val key: String, val context: String, val purpose: Int) {
    companion object {
        fun purpose(purpose: Int, hint: Int): Int = if (hint and 8 != 0) 8 else purpose
    }
}

object LinuxWindows {
    private val main = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "linux-window-metadata")
    }
    private var context: Context? = null
    private var generation = 0
    private var guestGeneration = ""
    private val hosts = mutableMapOf<String, LinuxWindowActivity>()
    private val launched = mutableSetOf<String>()
    private val returned = mutableSetOf<String>()
    private val pendingClose = mutableMapOf<String, String>()
    private val opening = mutableSetOf<String>()
    private var editor: LinuxWindowEditor? = null
    private val textSequence = java.util.concurrent.atomic.AtomicLong(System.nanoTime())
    private val visible = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val sizes = java.util.concurrent.ConcurrentHashMap<String, LinuxWindowSize>()
    private val preparing = mutableMapOf<String, LinuxWindowSize>()
    private val failures = mutableMapOf<String, String>()
    private val surfaceSequence = java.util.concurrent.atomic.AtomicLong(System.nanoTime())
    val hasVisibleWindows get() = visible.isNotEmpty()
    val hasPendingClose get() = pendingClose.isNotEmpty()
    var enabled by mutableStateOf(false)
        private set
    var windows by mutableStateOf<List<LinuxWindow>>(emptyList())
        private set
    var owner by mutableStateOf<String?>(null)
        private set

    fun begin(context: Context) {
        end()
        this.context = context.applicationContext
        removeTasks(context)
        enabled = SessionPrefs.linuxMultiWindow(context) && supported()
        publishEnabled()
        refresh()
    }

    fun end() {
        generation++
        hosts.values.toList().forEach { it.removeFromRegistry() }
        hosts.clear()
        sizes.clear()
        preparing.clear()
        failures.clear()
        publishSizes()
        visible.clear()
        launched.clear()
        returned.clear()
        pendingClose.clear()
        opening.clear()
        editor = null
        windows = emptyList()
        owner = null
        guestGeneration = ""
        context = null
    }

    fun setEnabled(context: Context, value: Boolean) {
        if (value && !supported()) {
            android.widget.Toast.makeText(context, com.droiddeck.launcher.R.string.linux_windows_unavailable,
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (enabled == value) return
        SessionPrefs.setLinuxMultiWindow(context, value)
        enabled = value
        if (this.context == null && SessionState.running) begin(context)
        if (value) File(AgentGuest.dir(context), "windows.json").delete()
        publishEnabled()
        if (value) refresh() else {
            generation++
            hosts.values.toList().forEach { it.removeFromRegistry() }
            hosts.clear()
            sizes.clear()
            preparing.clear()
            failures.clear()
            publishSizes()
            removeTasks(context)
            launched.clear()
            returned.clear()
            pendingClose.clear()
            opening.clear()
            editor = null
            windows = emptyList()
            owner = null
        }
    }

    private fun publishEnabled() {
        val ctx = context ?: return
        val marker = File(AgentGuest.dir(ctx), "windows-enabled")
        if (enabled) marker.writeText("1") else marker.delete()
        publishSizes()
    }

    fun agentFileChanged(path: String?) {
        when (path) {
            "windows.json" -> refresh()
            "window-host.json", "ime.json" -> refreshApplied()
        }
    }

    fun nextSurfaceGeneration(): Long = surfaceSequence.incrementAndGet()

    private fun supported() = runCatching { WaylandCompositor.nativeWindowSupported() }.getOrDefault(false)

    fun prepare(key: String, width: Int, height: Int, generation: Long): Boolean {
        val size = LinuxWindowSize.accept(width, height, generation) ?: return false
        if (!enabled || key in returned || windows.none { it.key == key }) return false
        if (generation < (preparing[key]?.generation ?: 0)) return false
        preparing[key] = size
        return true
    }

    fun presentationFailed(key: String, generation: Long, error: String) {
        if (!enabled || preparing[key]?.generation != generation || key in returned) return
        failures[key] = error
        returned.add(key)
        opening.remove(key)
        sizes.remove(key)
        preparing.remove(key)
        publishSizes()
        command(key, "restore")
        if (owner == key) desktop()
        hosts[key]?.removeFromRegistry()
        com.droiddeck.launcher.wayland.CompositorHost.detachWindow(key, generation)
        context?.let { ctx ->
            val title = windows.firstOrNull { it.key == key }?.title.orEmpty().take(128)
            android.widget.Toast.makeText(ctx, ctx.getString(
                com.droiddeck.launcher.R.string.linux_windows_presentation_failed, title),
                android.widget.Toast.LENGTH_LONG).show()
        }
        Log.w("LinuxWindows", "$key returned to desktop: $error")
    }

    fun resize(key: String, width: Int, height: Int, generation: Long): Boolean {
        val size = LinuxWindowSize.accept(width, height, generation) ?: return false
        if (!enabled || windows.none { it.key == key }) return false
        val previous = sizes[key]
        if (previous != null && previous.generation > generation) return false
        if (size != previous) {
            sizes[key] = size
            publishSizes()
        }
        refreshApplied()
        return true
    }

    private fun refreshApplied() {
        val ctx = context ?: return
        val file = File(AgentGuest.dir(ctx), "window-host.json")
        val token = generation
        worker.execute {
            val state = runCatching {
                if (!file.isFile || file.length() > 1024 * 1024) return@runCatching null
                val host = JSONObject(file.readText())
                val imeFile = File(file.parentFile, "ime.json")
                val ime = if (imeFile.isFile && imeFile.length() <= 65536)
                    JSONObject(imeFile.readText()) else JSONObject()
                host to ime
            }.getOrNull() ?: return@execute
            main.post {
                if (!enabled || generation != token) return@post
                val (host, ime) = state
                val items = host.optJSONArray("windows") ?: org.json.JSONArray()
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val key = item.optString("key")
                    val size = sizes[key] ?: continue
                    if (item.optLong("generation") != size.generation) continue
                    val ready = item.optBoolean("match") && item.optBoolean("detached") &&
                        item.optInt("frameWidth", item.optInt("clientWidth")) == size.width &&
                        item.optInt("frameHeight", item.optInt("clientHeight")) == size.height &&
                        item.optInt("outputWidth") == size.width && item.optInt("outputHeight") == size.height
                    WaylandCompositor.nativeWindowInput(key, size.generation, 7, if (ready) 1 else 0, 0)
                }
                val activeKey = host.optString("activeKey")
                val contextId = ime.optString("context")
                val textField = host.optBoolean("textField") ||
                    (ime.optString("source") in setOf("atspi", "ibus") && ime.optString("key") == activeKey)
                editor = if (textField && ime.optBoolean("textField") &&
                    activeKey == owner && contextId.isNotBlank())
                    LinuxWindowEditor(activeKey, contextId,
                        LinuxWindowEditor.purpose(ime.optInt("purpose"), ime.optInt("hint"))) else null
                hosts.values.toList().forEach { it.textFocusChanged(editor) }
            }
        }
    }

    fun editor(key: String): LinuxWindowEditor? = editor?.takeIf { it.key == key && owner == key }

    fun text(key: String, action: String, text: String, cursor: Int = 1): Boolean {
        val state = editor(key) ?: return true
        val ctx = context ?: return true
        if (text.toByteArray(Charsets.UTF_8).size > 60000) return false
        val directory = File(AgentGuest.dir(ctx), "window-text-requests")
        val id = textSequence.incrementAndGet().toString().padStart(20, '0')
        val body = JSONObject().put("key", key).put("context", state.context)
            .put("action", action).put("text", text).put("cursor", cursor).toString()
        worker.execute {
            runCatching {
                directory.mkdirs()
                val staged = File(directory, "$id.tmp")
                staged.writeText(body)
                check(staged.renameTo(File(directory, "$id.json")))
            }.onFailure { Log.w("LinuxWindows", "Text input delivery failed", it) }
        }
        return true
    }

    private fun publishSizes() {
        val ctx = context ?: return
        val destination = File(AgentGuest.dir(ctx), "window-outputs.json")
        val body = JSONObject().put("version", 1).put("windows", org.json.JSONArray(sizes.map { (key, size) ->
            JSONObject().put("key", key).put("width", size.width).put("height", size.height)
                .put("generation", size.generation)
        })).toString()
        worker.execute {
            val staged = File(destination.parentFile, "window-outputs.tmp")
            runCatching {
                staged.writeText(body)
                check(staged.renameTo(destination))
            }.onFailure { Log.w("LinuxWindows", "Output configuration failed", it) }
        }
    }

    private fun refresh() {
        val ctx = context ?: return
        val token = generation
        worker.execute {
            val result = runCatching {
                val dir = AgentGuest.dir(ctx)
                val file = File(dir, "windows.json")
                if (!file.isFile || file.length() > 1024 * 1024) return@runCatching null
                val json = JSONObject(file.readText())
                if (!json.optBoolean("independent")) return@runCatching null
                val items = json.optJSONArray("windows") ?: return@runCatching null
                val parsed = buildList {
                    for (i in 0 until items.length()) {
                        val w = items.getJSONObject(i)
                        val g = w.optJSONArray("geometry") ?: continue
                        val key = w.optString("key")
                        if (!key.startsWith("wl:") || g.length() != 4) continue
                        val name: String? = null
                        val icon: Bitmap? = null
                        val program = w.optString("program").take(256)
                        add(LinuxWindow(key, w.optString("title").ifBlank { program }.take(512), program,
                            g.optInt(0), g.optInt(1), g.optInt(2), g.optInt(3), icon, name,
                            w.optBoolean("desktop"), w.optInt("state") and 2 != 0))
                    }
                }
                Triple(json.optString("generation"), parsed, json.optJSONArray("closeResults") ?: org.json.JSONArray())
            }.onFailure { Log.w("LinuxWindows", "Window metadata rejected", it) }.getOrNull()
            main.post {
                if (token != generation || !enabled || result == null || !SessionState.running) return@post
                if (guestGeneration.isNotEmpty() && guestGeneration != result.first) {
                    hosts.values.toList().forEach { it.removeFromRegistry() }
                    hosts.clear()
                    sizes.clear()
                    preparing.clear()
                    failures.clear()
                    publishSizes()
                    launched.clear()
                    returned.clear()
                    pendingClose.clear()
                    opening.clear()
                    editor = null
                    removeTasks(ctx)
                }
                guestGeneration = result.first
                val keys = result.second.map { it.key }.toSet()
                hosts.filterKeys { it !in keys }.values.toList().forEach { it.removeFromRegistry() }
                hosts.keys.retainAll(keys)
                preparing.keys.retainAll(keys)
                failures.keys.retainAll(keys)
                returned.retainAll(keys)
                launched.retainAll(keys)
                if (sizes.keys.any { it !in keys }) {
                    sizes.keys.retainAll(keys)
                    publishSizes()
                }
                windows = result.second
                windows.forEach {
                    if (!it.desktop && opening.remove(it.key)) returned.remove(it.key)
                    hosts[it.key]?.metadataChanged(it)
                    if (it.desktop) {
                        returned.add(it.key)
                        hosts[it.key]?.removeFromRegistry()
                        sizes.remove(it.key)?.let { publishSizes() }
                    }
                }
                closeResults(result.third)
                windows.filter { it.key !in launched && it.key !in returned && !it.desktop }.forEach { window ->
                    launched.add(window.key)
                    runCatching {
                        ctx.startActivity(intent(ctx, window.key))
                    }.onFailure {
                        returned.add(window.key)
                        failures[window.key] = it.message ?: "Native task creation failed"
                        command(window.key, "restore")
                        Log.w("LinuxWindows", "Native task creation failed", it)
                    }
                }
            }
        }
    }

    fun intent(context: Context, key: String) = Intent(context, LinuxWindowActivity::class.java)
        .setData(Uri.Builder().scheme("droiddeck-window").authority("linux").appendPath(key).build())
        .putExtra("windowKey", key)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

    fun open(activity: Activity, key: String) {
        if (windows.firstOrNull { it.key == key }?.desktop == true || key in returned) {
            if (!supported()) return
            SessionService.resume(activity)
            if (command(key, "host") != null) {
                failures.remove(key)
                opening.add(key)
                launched.remove(key)
            }
            return
        }
        val manager = activity.getSystemService(ActivityManager::class.java)
        val task = manager.appTasks.firstOrNull { it.taskInfo.baseIntent.getStringExtra("windowKey") == key }
        if (task != null) task.moveToFront() else activity.startActivity(intent(activity, key))
    }

    fun attach(activity: LinuxWindowActivity, key: String): LinuxWindow? {
        val window = windows.firstOrNull { it.key == key } ?: return null
        hosts[key] = activity
        return window
    }

    fun detach(activity: LinuxWindowActivity, key: String) {
        if (hosts[key] === activity) {
            hosts.remove(key)
            if (activity.isFinishing) {
                sizes.remove(key)
                publishSizes()
            }
        }
        if (owner == key) {
            owner = null
        }
    }

    fun claim(key: String) {
        if (owner != key) owner?.let { hosts[it]?.releaseInput() }
        editor = null
        hosts.values.toList().forEach { it.textFocusChanged(null) }
        owner = key
        command(key, "focus")
        refreshApplied()
    }

    fun desktop() {
        owner?.let { hosts[it]?.releaseInput() }
        owner?.let { key -> sizes[key]?.let { WaylandCompositor.nativeWindowInput(key, it.generation, 6, 0, 0) } }
        owner = null
        editor = null
        hosts.values.toList().forEach { it.textFocusChanged(null) }
    }

    fun visibility(activity: Activity, showing: Boolean) {
        if (showing) visible.add(activity.taskId) else visible.remove(activity.taskId)
        SessionService.linuxWindowsVisibilityChanged(activity)
    }

    fun showList(activity: Activity) {
        if (!enabled || !SessionState.running) return
        val items = windows.toList()
        val builder = android.app.AlertDialog.Builder(activity).setTitle(com.droiddeck.launcher.R.string.linux_windows_list)
        if (items.isEmpty()) builder.setMessage(com.droiddeck.launcher.R.string.linux_windows_empty)
        else builder.setItems(items.map { it.title }.toTypedArray()) { _, i -> open(activity, items[i].key) }
        builder.setNegativeButton(android.R.string.cancel, null).show()
    }

    fun closeFromAndroid(key: String) {
        if (key in returned || !enabled || !SessionState.running) return
        returned.add(key)
        val ctx = context ?: return
        val window = windows.firstOrNull { it.key == key } ?: return
        command(key, "close", returnToDesktop = true)?.let { id ->
            pendingClose[id] = key
            SessionService.linuxWindowsVisibilityChanged(ctx)
            val token = generation
            main.postDelayed({
                if (generation != token || pendingClose.remove(id) == null) return@postDelayed
                android.widget.Toast.makeText(ctx, ctx.getString(
                    com.droiddeck.launcher.R.string.linux_windows_close_unconfirmed, window.title.take(128)),
                    android.widget.Toast.LENGTH_LONG).show()
                SessionService.linuxWindowsVisibilityChanged(ctx)
            }, 15_000)
        }
    }

    private fun closeResults(results: org.json.JSONArray) {
        val ctx = context ?: return
        val before = pendingClose.size
        for (i in 0 until results.length()) {
            val result = results.optJSONObject(i) ?: continue
            if (pendingClose.remove(result.optString("id")) == null) continue
            val message = when (result.optString("outcome")) {
                "running" -> com.droiddeck.launcher.R.string.linux_windows_returned
                "minimized", "background" -> com.droiddeck.launcher.R.string.linux_windows_background
                "unsupported" -> com.droiddeck.launcher.R.string.linux_windows_close_unsupported
                else -> continue
            }
            android.widget.Toast.makeText(ctx, ctx.getString(message, result.optString("title").take(128)),
                android.widget.Toast.LENGTH_LONG).show()
        }
        if (before != pendingClose.size) SessionService.linuxWindowsVisibilityChanged(ctx)
    }

    fun command(key: String, action: String, returnToDesktop: Boolean = false): String? {
        val ctx = context ?: return null
        if (!enabled || windows.none { it.key == key }) return null
        val dir = File(AgentGuest.dir(ctx), "window-requests").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val staged = File(dir, "$id.tmp")
        return runCatching {
            staged.writeText(JSONObject().put("id", id).put("key", key).put("action", action)
                .put("returnToDesktop", returnToDesktop).toString())
            check(staged.renameTo(File(dir, "$id.json")))
            id
        }.onFailure {
            staged.delete()
            Log.w("LinuxWindows", "Window action delivery failed", it)
            if (returnToDesktop) android.widget.Toast.makeText(ctx,
                com.droiddeck.launcher.R.string.linux_windows_close_delivery_failed,
                android.widget.Toast.LENGTH_LONG).show()
        }.getOrNull()
    }

    private fun removeTasks(context: Context) {
        context.getSystemService(ActivityManager::class.java).appTasks.forEach {
            if (it.taskInfo.baseIntent.component?.className == LinuxWindowActivity::class.java.name)
                runCatching { it.finishAndRemoveTask() }
        }
    }

    fun diagnostics() = JSONObject().put("enabled", enabled)
        .put("supported", supported())
        .put("owner", owner ?: JSONObject.NULL)
        .put("failures", JSONObject(failures.toMap()))
        .put("windows", org.json.JSONArray(windows.map { w ->
            JSONObject().put("key", w.key).put("title", w.title).put("program", w.program)
                .put("icon", w.icon != null).put("width", w.width).put("height", w.height)
                .put("desktop", w.desktop || w.key in returned).put("minimized", w.minimized)
                .put("iconName", w.iconName ?: JSONObject.NULL)
                .put("iconWidth", w.icon?.width ?: 0).put("iconHeight", w.icon?.height ?: 0)
                .put("taskIconSource", if (w.icon != null) "linux" else "linux-window-resource")
                .put("requested", sizes[w.key]?.let { s ->
                    JSONObject().put("width", s.width).put("height", s.height).put("generation", s.generation)
                } ?: JSONObject.NULL)
                .put("native", org.json.JSONArray(WaylandCompositor.nativeWindowStats(w.key).toList()))
        }))
        .put("editor", editor?.let { JSONObject().put("key", it.key).put("context", it.context)
            .put("purpose", it.purpose) } ?: JSONObject.NULL)
        .put("host", context?.let { ctx ->
            runCatching { JSONObject(File(AgentGuest.dir(ctx), "window-host.json").readText()) }.getOrNull()
        } ?: JSONObject.NULL)
}
