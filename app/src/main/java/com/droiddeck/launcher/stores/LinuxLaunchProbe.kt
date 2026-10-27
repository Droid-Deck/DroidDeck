package com.droiddeck.launcher.stores

import android.content.Context
import com.droiddeck.launcher.agent.AgentGuest
import org.json.JSONObject

internal object LinuxLaunchProbe {
    private const val NAME = "Linux probe"
    private const val TOOL = "droiddeck-fex"

    fun run(context: Context, exe: String) {
        val app = context.applicationContext
        Thread({
            runCatching { probe(app, exe) }.onFailure { log("stopped: ${it.javaClass.simpleName} ${it.message}") }
        }, "linux-launch-probe").start()
    }

    private fun log(line: String) = StoresState.logLine("linux probe: $line")

    private fun guest(context: Context, script: String, arg: String): String {
        val request = JSONObject().put("kind", "exec")
            .put("argv", org.json.JSONArray(listOf("sh", "-c", script.replace('@', '$'), "sh", arg))).put("timeout", 15.0)
        val answer = AgentGuest.call(context, request, 20_000)
        return (answer.optString("stdout") + answer.optString("stderr")).trim().ifEmpty { answer.toString() }
    }

    private fun probe(context: Context, exe: String) {
        val dir = exe.substringBeforeLast('/', "")
        log("start exe=$exe dir=$dir")
        log("file: " + guest(context, "ls -l \"@1\"; od -An -tx1 -N20 \"@1\"", exe).replace("\n", " | "))
        val id = SteamLiveShortcuts.ensure(context, NAME, exe, dir, compat = false)
        if (id == null) { log("no shortcut was added (client not running or not ready)"); return }
        log("shortcut id=$id")
        val spec = AgentGuest.steamEval(
            context,
            "(async () => { try { const r = await SteamClient.Apps.SpecifyCompatTool($id, ${JSONObject.quote(TOOL)}); return 'returned ' + JSON.stringify(r === undefined ? 'undefined' : r); } catch (e) { return 'threw ' + e; } })()",
            20_000,
        )
        log("SpecifyCompatTool: ${spec.opt("value") ?: spec.opt("error")}")
        val mapping = guest(
            context,
            "for f in @HOME/.local/share/Steam/config/config.vdf @HOME/.droiddeck-x64/.local/share/Steam/config/config.vdf; do [ -f @f ] && { echo == @f; grep -n -A3 @1 @f; }; done; true",
            "\"" + id + "\"",
        )
        log("mapping: " + mapping.replace("\n", " | "))
        log("done; launch \"$NAME\" from Steam now")
    }
}
