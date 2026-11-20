package com.droiddeck.launcher.agent

import android.content.Context
import com.droiddeck.launcher.frontend.LibraryCache
import com.droiddeck.launcher.stores.CloudSaves
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.xbox.Xodus
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

internal object AgentStores {
    private const val OUTPUT_CAP = 160 * 1024

    fun library(context: Context, request: JSONObject): JSONObject {
        val query = request.optString("query").trim()
        val source = request.optString("source").trim()
        val games = JSONArray()
        LibraryCache.load(context)
            .filter { (query.isEmpty() || it.name.contains(query, ignoreCase = true)) && (source.isEmpty() || it.source.equals(source, ignoreCase = true)) }
            .forEach {
                games.put(JSONObject().put("id", it.gameIdString).put("name", it.name).put("source", it.source)
                    .put("storeId", it.storeId ?: JSONObject.NULL).put("folder", it.gameFiles?.path ?: JSONObject.NULL))
            }
        return JSONObject().put("ok", true).put("command", "library").put("games", games)
    }

    fun xodus(context: Context, request: JSONObject): JSONObject {
        AgentAccess.requireCommands(context)
        val list = request.optJSONArray("args")
        val args = if (list == null) emptyList() else List(list.length()) { list.optString(it) }
        if (args.isEmpty() || args.any { it.isEmpty() }) throw AgentException("INVALID_REQUEST", "xodus needs its arguments")
        val timeoutMs = (request.optDouble("timeout", 120.0) * 1000).toLong().coerceIn(1_000L, 900_000L)
        return Xodus.start(context.applicationContext, args).use { child ->
            val out = Tail(child.process.inputStream)
            val err = Tail(child.process.errorStream)
            val finished = child.process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) child.close()
            JSONObject().put("ok", true).put("command", "xodus").put("timedOut", !finished)
                .put("exit", if (finished) child.process.exitValue() else JSONObject.NULL)
                .put("stdout", out.text()).put("stderr", err.text())
        }
    }

    fun cloud(context: Context, request: JSONObject): JSONObject {
        AgentAccess.requireCommands(context)
        val store = Store.byId(request.optString("store"))?.takeIf(CloudSaves::supports)
            ?: throw AgentException("INVALID_REQUEST", "store must be one with cloud saves")
        val id = request.optString("id").ifEmpty { throw AgentException("INVALID_REQUEST", "cloud needs a game id") }
        val force = request.optBoolean("force")
        val result = when (val direction = request.optString("direction")) {
            "down" -> CloudSaves.download(context.applicationContext, store, id, force)
            "up" -> CloudSaves.upload(context.applicationContext, store, id, force)
            else -> throw AgentException("INVALID_REQUEST", "direction '$direction' is not down or up")
        }
        return JSONObject().put("ok", true).put("command", "cloud").put("result", result.result).put("files", result.files)
            .put("bytes", result.bytes).put("reason", result.reason).put("conflicts", result.conflicts).put("detail", result.detail)
    }

    private class Tail(stream: InputStream) {
        private val kept = ByteArrayOutputStream()
        private var dropped = false
        private val reader = Thread({
            val buffer = ByteArray(8192)
            stream.use { input ->
                while (true) {
                    val n = runCatching { input.read(buffer) }.getOrDefault(-1)
                    if (n < 0) break
                    synchronized(kept) {
                        kept.write(buffer, 0, n)
                        if (kept.size() > OUTPUT_CAP * 2) {
                            val bytes = kept.toByteArray()
                            kept.reset()
                            kept.write(bytes, bytes.size - OUTPUT_CAP, OUTPUT_CAP)
                            dropped = true
                        }
                    }
                }
            }
        }, "agent-xodus-output").apply { isDaemon = true; start() }

        fun text(): String {
            reader.join(2_000)
            val bytes = synchronized(kept) { kept.toByteArray() }
            val tail = if (bytes.size > OUTPUT_CAP) bytes.copyOfRange(bytes.size - OUTPUT_CAP, bytes.size) else bytes
            return (if (dropped || bytes.size > OUTPUT_CAP) "...\n" else "") + String(tail, Charsets.UTF_8)
        }
    }
}
