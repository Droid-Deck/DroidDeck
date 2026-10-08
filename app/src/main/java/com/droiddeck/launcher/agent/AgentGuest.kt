package com.droiddeck.launcher.agent

import android.content.Context
import android.os.FileObserver
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionEvents
import com.droiddeck.launcher.session.SessionState
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The app's side of the guest agent (tools/linuxfs/overlay/usr/local/bin/droiddeck-agent): requests
 * go in as files under the session root's agent directory and answers come back the same way, so
 * whatever runs does so inside the live session with its own environment.
 */
object AgentGuest {
    private const val TAG = "AgentGuest"
    private const val POLL_MS = 50L
    private val lock = Any()
    private var observer: FileObserver? = null
    private var lastFocusKey: String? = null

    fun dir(context: Context) = File(LinuxRuntime.sessionRoot(context), "agent")

    /**
     * Before each session: nothing of the last one's daemon, answers or focus may be read as
     * current. Then watch for the new daemon's hello and focus snapshots, which go into the
     * session's events.jsonl.
     */
    fun reset(context: Context) {
        synchronized(lock) {
            observer?.stopWatching()
            observer = null
            lastFocusKey = null
            val dir = dir(context)
            dir.deleteRecursively()
            dir.mkdirs()
            @Suppress("DEPRECATION")
            observer = object : FileObserver(dir.path, MOVED_TO or CLOSE_WRITE) {
                override fun onEvent(event: Int, path: String?) {
                    when (path) {
                        "hello.json" -> readJson(File(dir, path))?.let {
                            SessionEvents.record("agent.ready", mapOf("pid" to it.optInt("pid"), "focus" to it.optBoolean("focus")))
                        }
                        "focus.json" -> readJson(File(dir, path))?.let(::recordFocus)
                    }
                }
            }.also { it.startWatching() }
        }
    }

    /** At the end of a session: no more events for a folder that is being closed. */
    fun stop() {
        synchronized(lock) {
            observer?.stopWatching()
            observer = null
        }
    }

    private fun recordFocus(snapshot: JSONObject) {
        val fields = linkedMapOf<String, Any?>(
            "focusedApp" to snapshot.opt("focusedApp"),
            "focusableApps" to snapshot.optJSONArray("focusableApps")?.toString(),
            "baselayerAppIds" to snapshot.optJSONArray("baselayerAppIds")?.toString(),
        )
        val key = fields.values.joinToString("|")
        synchronized(lock) {
            if (key == lastFocusKey) return
            lastFocusKey = key
        }
        SessionEvents.record("focus.changed", fields + ("seq" to snapshot.optInt("seq")))
    }

    /** The daemon's hello, when it is running in the current session. */
    fun hello(context: Context): JSONObject? {
        if (!SessionState.running) return null
        val hello = readJson(File(dir(context), "hello.json")) ?: return null
        val pid = hello.optInt("pid", -1)
        return hello.takeIf { pid > 1 && File("/proc/$pid").exists() }
    }

    /** The newest focus snapshot the daemon published, when a session is running. */
    fun focus(context: Context): JSONObject? =
        if (SessionState.running) readJson(File(dir(context), "focus.json")) else null

    /** The Steam library game whose tile currently has controller or keyboard focus. */
    fun selectedSteamAppId(context: Context): Long? {
        val result = call(context, JSONObject().put("kind", "steam-selection"), 6_000)
        if (!result.optBoolean("ok") || result.isNull("appId")) return null
        return result.optLong("appId").takeIf { it in 1..4_294_967_295L }
    }

    /** Send one request and wait for its answer. Throws [AgentException] with a stable code. */
    fun call(context: Context, request: JSONObject, timeoutMs: Long): JSONObject {
        if (request.optString("kind") in setOf("exec", "cdp")) AgentAccess.requireCommands(context)
        if (!SessionState.running) throw AgentException("NO_ACTIVE_SESSION", "There is no running session")
        hello(context) ?: throw AgentException("AGENT_UNAVAILABLE", "The guest agent is not running in this session (yet)")
        val dir = dir(context)
        val requests = File(dir, "req").apply { mkdirs() }
        val answers = File(dir, "resp").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val staged = File(requests, "$id.json.tmp")
        val published = File(requests, "$id.json")
        val answer = File(answers, "$id.json")
        staged.writeText(request.toString())
        if (!staged.renameTo(published)) {
            staged.delete()
            throw AgentException("AGENT_UNAVAILABLE", "Could not hand the request to the guest agent")
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (answer.isFile) {
                if (answer.length() > 1024 * 1024) {
                    answer.delete()
                    throw AgentException("RESPONSE_TOO_LARGE", "The guest response exceeds 1 MiB")
                }
                val result = readJson(answer)
                answer.delete()
                return result ?: throw AgentException("INVALID_AGENT_RESPONSE", "The guest agent's answer was not JSON")
            }
            Thread.sleep(POLL_MS)
        }
        published.delete()
        Log.w(TAG, "no answer to ${request.optString("kind")} in ${timeoutMs}ms")
        throw AgentException("TIMEOUT", "The guest agent did not answer within ${timeoutMs / 1000}s")
    }

    private fun readJson(file: File): JSONObject? = try {
        if (file.isFile && file.length() <= 1024 * 1024) JSONObject(file.readText()) else null
    } catch (e: Exception) {
        null
    }
}
