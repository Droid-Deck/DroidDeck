package com.droiddeck.launcher.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.os.FileObserver
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionEvents
import com.droiddeck.launcher.session.SessionState
import org.json.JSONObject
import java.io.File
import java.util.UUID

object AgentGuest {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    var focusRevision by mutableIntStateOf(0)
        private set
    var steamKeyboard by mutableStateOf(false)
        private set
    private const val TAG = "AgentGuest"
    private const val POLL_MS = 50L
    private val lock = Any()
    private var observer: FileObserver? = null
    private var lastFocusKey: String? = null

    fun dir(context: Context) = File(LinuxRuntime.sessionRoot(context), "agent")

    fun reset(context: Context) {
        synchronized(lock) {
            observer?.stopWatching()
            observer = null
            lastFocusKey = null
            main.post { steamKeyboard = false }
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
                        "keyboard.json" -> readJson(File(dir, path))?.let { recordKeyboard(this, it) }
                    }
                }
            }.also { it.startWatching() }
        }
    }

    fun stop() {
        synchronized(lock) {
            observer?.stopWatching()
            observer = null
            main.post { steamKeyboard = false }
        }
    }

    private fun recordKeyboard(source: FileObserver, snapshot: JSONObject) {
        val showing = snapshot.optBoolean("steamKeyboard")
        synchronized(lock) {
            if (source !== observer) return
            main.post { steamKeyboard = showing }
        }
        SessionEvents.record("keyboard.changed", mapOf("steamKeyboard" to showing, "seq" to snapshot.optInt("seq")))
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
        main.post { focusRevision++ }
        SessionEvents.record("focus.changed", fields + ("seq" to snapshot.optInt("seq")))
    }

    fun hello(context: Context): JSONObject? {
        if (!SessionState.running) return null
        val hello = readJson(File(dir(context), "hello.json")) ?: return null
        val pid = hello.optInt("pid", -1)
        return hello.takeIf { pid > 1 && File("/proc/$pid").exists() }
    }

    fun focus(context: Context): JSONObject? =
        if (SessionState.running) readJson(File(dir(context), "focus.json")) else null

    fun selectedSteamAppId(context: Context): Long? {
        val result = call(context, JSONObject().put("kind", "steam-selection"), 6_000)
        if (!result.optBoolean("ok") || result.isNull("appId")) return null
        return result.optLong("appId").takeIf { it in 1..4_294_967_295L }
    }

    fun activeSteamAppId(context: Context, installedAppIds: Set<Long>): Long? {
        val snapshot = focus(context) ?: return null
        val focusedAppId = snapshot.optLong("focusedApp").takeIf { snapshot.has("focusedApp") }
        val focusableAppIds = snapshot.optJSONArray("focusableApps")?.let { apps ->
            buildList {
                for (index in 0 until apps.length()) {
                    apps.optLong(index).takeIf { it > 0 }?.let(::add)
                }
            }
        }.orEmpty()
        val baselayerAppIds = snapshot.optJSONArray("baselayerAppIds")?.let { apps ->
            buildList {
                for (index in 0 until apps.length()) {
                    apps.optLong(index).takeIf { it > 0 }?.let(::add)
                }
            }
        }.orEmpty()
        return activeInstalledSteamAppId(focusedAppId, focusableAppIds, installedAppIds, baselayerAppIds)
    }

    internal fun focusedInstalledSteamAppId(focusedAppId: Long?, installedAppIds: Set<Long>): Long? =
        focusedAppId?.takeIf { it in installedAppIds }

    internal fun activeInstalledSteamAppId(
        focusedAppId: Long?,
        focusableAppIds: List<Long>,
        installedAppIds: Set<Long>,
        baselayerAppIds: List<Long> = emptyList(),
    ): Long? = focusedInstalledSteamAppId(focusedAppId, installedAppIds)
        ?: focusableAppIds.filter { it in installedAppIds }.distinct().singleOrNull()
        ?: baselayerAppIds.filter { it in installedAppIds }.distinct().singleOrNull()

    internal fun preferredInstalledSteamAppId(
        selectedAppId: Long?,
        activeAppId: Long?,
        installedAppIds: Set<Long>,
    ): Long? = selectedAppId?.takeIf { it in installedAppIds }
        ?: activeAppId?.takeIf { it in installedAppIds }

    internal fun selectedOrActiveInstalledSteamAppId(
        selectedAppId: Result<Long?>,
        activeAppId: Long?,
        installedAppIds: Set<Long>,
    ): Long? {
        preferredInstalledSteamAppId(selectedAppId.getOrNull(), activeAppId, installedAppIds)?.let { return it }
        selectedAppId.exceptionOrNull()?.let { throw it }
        return null
    }

    fun steamEval(context: Context, expression: String, timeoutMs: Long): JSONObject =
        call(context, JSONObject().put("kind", "cdp").put("expression", expression).put("timeout", timeoutMs / 1000.0),
            timeoutMs + 2_000, trusted = true)

    fun call(context: Context, request: JSONObject, timeoutMs: Long, trusted: Boolean = false): JSONObject {
        if (!trusted && request.optString("kind") in setOf("exec", "cdp")) AgentAccess.requireCommands(context)
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
