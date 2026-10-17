package com.droiddeck.launcher.stores

import android.content.Context
import android.os.FileObserver
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import org.json.JSONObject

object StoreLaunchRequests {
    private const val TAG = "StoreLaunchRequests"
    private val lock = Any()
    private var observer: FileObserver? = null

    @Volatile var lastLaunched: Pair<Store, String>? = null

    fun dir(context: Context) = File(LinuxRuntime.sessionRoot(context), "stores")

    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            observer?.stopWatching()
            val root = dir(app)
            root.deleteRecursively()
            lastLaunched = null
            val req = File(root, "req").apply { mkdirs() }
            File(root, "resp").mkdirs()
            @Suppress("DEPRECATION")
            observer = object : FileObserver(req.path, MOVED_TO or CLOSE_WRITE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null || !path.endsWith(".json")) return
                    val file = File(req, path)
                    Thread({ answer(app, file) }, "store-launch-request").start()
                }
            }.also { it.startWatching() }
        }
    }

    fun stop() {
        synchronized(lock) {
            observer?.stopWatching()
            observer = null
        }
    }

    private fun answer(app: Context, file: File) {
        val request = try {
            JSONObject(file.readText()).also { file.delete() }
        } catch (e: Exception) {
            return
        }
        val reply = when (request.optString("op")) {
            "gog-achievements" -> runCatching { com.droiddeck.launcher.stores.gog.GogComet.prepare(app, request.optString("id")) }
                .getOrElse { JSONObject().put("ok", false).put("ready", false).put("reason", "service-unavailable") }
            "epic-code" -> {
                val id = request.optString("id")
                if (id.isEmpty()) JSONObject().put("ok", false).put("reason", "no-id")
                else {
                    lastLaunched = Store.EPIC to id
                    val code = StoreLaunch.epicCode(app, id)
                    JSONObject().put("ok", true).put("code", code.written).put("reason", code.reason)
                }
            }
            "cloud-down" -> {
                val store = Store.byId(request.optString("store"))
                val id = request.optString("id")
                if (store == null || id.isEmpty()) JSONObject().put("ok", false).put("reason", "no-game")
                else {
                    lastLaunched = store to id
                    CloudSaves.markDirty(app, store, id)
                    CloudSaves.downloadOrDefer(app, store, id).let { JSONObject().put("ok", it.ok).put("result", it.result).put("files", it.files).put("reason", it.reason) }
                }
            }
            "cloud-up" -> {
                val store = Store.byId(request.optString("store"))
                val id = request.optString("id")
                if (store != null && id.isNotEmpty()) Thread({ CloudSaves.uploadDirty(app, store, id, "exit") }, "cloud-up").start()
                JSONObject().put("ok", true)
            }
            "open-url" -> {
                val url = request.optString("url")
                if (!(url.startsWith("https://") || url.startsWith("http://"))) JSONObject().put("ok", false).put("reason", "not-http")
                else try {
                    app.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    Log.i(TAG, "open-url: " + StoreLog.redactUrl(url))
                    JSONObject().put("ok", true)
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("reason", e.javaClass.simpleName)
                }
            }
            else -> JSONObject().put("ok", false).put("reason", "unknown-op")
        }
        try {
            val resp = File(dir(app), "resp").apply { mkdirs() }
            val tmp = File(resp, file.name + ".tmp")
            tmp.writeText(reply.toString())
            tmp.renameTo(File(resp, file.name))
        } catch (e: Exception) {
            Log.w(TAG, "could not answer ${file.name}: ${e.message}")
        }
    }
}
