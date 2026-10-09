package com.droiddeck.launcher.stores

import android.content.Context
import android.os.FileObserver
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import org.json.JSONObject

/**
 * The app's half of droiddeck-store-launch: the compat tool asks, before Proton starts a store
 * game, for what the store needs at launch, through files under the session root's `stores/`
 * (`req/<id>.json` in, `resp/<id>.json` out, both written tmp + rename). Today that is an Epic
 * game's exchange code, which [StoreLaunch.epicCode] writes into the game's own folder; the answer
 * says only whether it did and why not. Watched while a session runs.
 */
object StoreLaunchRequests {
    private const val TAG = "StoreLaunchRequests"
    private val lock = Any()
    private var observer: FileObserver? = null

    fun dir(context: Context) = File(LinuxRuntime.sessionRoot(context), "stores")

    /** Before each session: nothing of the last one's requests; then watch for new ones. */
    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            observer?.stopWatching()
            val root = dir(app)
            root.deleteRecursively()
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
            return  // withdrawn already, or not complete yet (the rename brings it again)
        }
        val reply = when (request.optString("op")) {
            "epic-code" -> {
                val id = request.optString("id")
                if (id.isEmpty()) JSONObject().put("ok", false).put("reason", "no-id")
                else StoreLaunch.epicCode(app, id).let { JSONObject().put("ok", true).put("code", it.written).put("reason", it.reason) }
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
