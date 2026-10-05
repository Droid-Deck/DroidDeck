package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONObject
import java.io.File

object ProtonDefault {
    private const val TAG = "ProtonDefault"
    private const val DIR = "root/.local/share/droiddeck-compat"

    data class Tool(val name: String, val display: String, val dir: String, val valve: Boolean)
    data class Request(val seq: Long, val valve: Boolean, val dir: String)
    data class State(val default: String?, val applied: Long, val live: Boolean, val pending: String?, val tools: List<Tool>)

    enum class Outcome { LIVE, NEXT_START, NOT_RUNNABLE }

    private fun dir(context: Context) = File(LinuxRuntime.rootDir(context), DIR)

    internal fun parseState(text: String?): State {
        val o = runCatching { JSONObject(text ?: "") }.getOrNull() ?: return State(null, 0, false, null, emptyList())
        val tools = o.optJSONArray("tools")
        return State(
            default = o.optString("default").ifEmpty { null },
            applied = o.optLong("applied", 0),
            live = o.optBoolean("live", false),
            pending = o.optString("pending").ifEmpty { null },
            tools = (0 until (tools?.length() ?: 0)).mapNotNull { i ->
                tools?.optJSONObject(i)?.let { t ->
                    Tool(t.optString("name"), t.optString("display"), t.optString("dir"), t.optBoolean("valve"))
                }?.takeIf { it.name.isNotEmpty() }
            },
        )
    }

    internal fun parseRequest(text: String?): Request? {
        val o = runCatching { JSONObject(text ?: "") }.getOrNull() ?: return null
        val seq = o.optLong("seq", 0)
        val dir = o.optString("dir")
        return if (seq > 0 && dir.isNotEmpty()) Request(seq, o.optBoolean("valve"), dir) else null
    }

    internal fun chosen(state: State, request: Request?): Pair<Boolean, String>? {
        if (request != null && request.seq > state.applied) return request.valve to request.dir
        val tool = state.tools.firstOrNull { it.name == state.default } ?: return null
        return tool.valve to tool.dir
    }

    internal fun runnable(state: State, proton: ComponentsManager.Proton): Boolean =
        state.tools.isEmpty() || state.tools.any { it.valve == proton.valve && it.dir == proton.dir.name }

    private fun read(file: File): String? = runCatching { file.readText() }.getOrNull()

    fun state(context: Context): State = parseState(read(File(dir(context), "state.json")))

    fun selectedId(context: Context, protons: List<ComponentsManager.Proton>): String? {
        val d = dir(context)
        val pick = chosen(parseState(read(File(d, "state.json"))), parseRequest(read(File(d, "request.json")))) ?: return null
        return protons.firstOrNull { it.valve == pick.first && it.dir.name == pick.second }?.id
    }

    internal fun nextSeq(now: Long, state: State, previous: Request?): Long =
        maxOf(now, state.applied + 1, (previous?.seq ?: 0) + 1)

    @Synchronized
    fun request(context: Context, proton: ComponentsManager.Proton): Outcome {
        val state = state(context)
        if (!runnable(state, proton)) return Outcome.NOT_RUNNABLE
        val d = dir(context)
        val seq = nextSeq(System.currentTimeMillis(), state, parseRequest(read(File(d, "request.json"))))
        val body = JSONObject().put("seq", seq).put("valve", proton.valve).put("dir", proton.dir.name).toString(1) + "\n"
        val staged = File(d, "request.json.staged")
        try {
            d.mkdirs()
            staged.writeText(body)
            if (!staged.renameTo(File(d, "request.json"))) throw java.io.IOException("rename failed")
        } catch (e: Exception) {
            staged.delete()
            Log.w(TAG, "could not write the Proton request", e)
            throw e
        }
        val tool = state.tools.firstOrNull { it.valve == proton.valve && it.dir == proton.dir.name }
        return if (SessionState.running && state.live && (tool == null || state.pending != tool.name)) Outcome.LIVE else Outcome.NEXT_START
    }
}
