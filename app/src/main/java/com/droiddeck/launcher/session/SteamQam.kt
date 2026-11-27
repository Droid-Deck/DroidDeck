package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.agent.AgentGuest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Steam renders the quick access menu only while it is open. It stays open with its window parked outside the Big
// Picture window, composition held at "notification" so gamescope keeps the game focused, and gamepad focus on the main UI.
object SteamQam {
    data class Target(val port: Int, val path: String)

    const val QUICK_ACCESS = 2
    private const val TAG = "SteamQam"
    private const val EVAL_MS = 6_000L

    internal fun script(on: Boolean): String = """(() => {
        const W = SteamUIStore.WindowStore.SteamUIWindows[0];
        const m = W.MenuStore, c = W.m_CompositionStateStore;
        const nav = SteamUIStore.m_GamepadNavigationManager.m_ActiveContext;
        const doc = g_PopupManager.GetExistingPopup("SP BPM_uid0")?.m_popup?.document;
        const QAM = 2;
        if (!c.__dd) {
            c.__dd = c.ComputeOverallMinimumRequestedComposition;
            c.ComputeOverallMinimumRequestedComposition = function (...a) {
                const v = c.__dd.apply(this, a);
                return globalThis.__ddQam && v === 2 && m.m_eOpenSideMenu === QAM ? 1 : v;
            };
            m.__dd = m.OnMenuDeactivated;
            m.OnMenuDeactivated = function (d) { if (globalThis.__ddQam && d === QAM) return; return m.__dd.call(this, d); };
        }
        const backdrop = (hide) => {
            if (!doc) return;
            for (const e of doc.querySelectorAll("div")) {
                if (e.dataset.ddQam) { if (!hide) { e.style.removeProperty("visibility"); delete e.dataset.ddQam; } continue; }
                if (!hide) continue;
                const s = getComputedStyle(e), r = e.getBoundingClientRect(), v = doc.defaultView;
                if (s.backdropFilter && s.backdropFilter !== "none" && r.width >= v.innerWidth - 2 && r.height >= v.innerHeight - 2) {
                    e.dataset.ddQam = "1";
                    e.style.setProperty("visibility", "hidden", "important");
                }
            }
        };
        if (${if (on) "true" else "false"}) {
            globalThis.__ddQam = true;
            if (m.m_eOpenSideMenu === 0) m.OpenQuickAccessMenu();
            if (m.m_eOpenSideMenu === QAM) {
                const main = nav.m_rgGamepadNavigationTrees.find(t => t.m_ID === "GamepadUI_Full_Root");
                if (main && nav.m_LastActiveNavTree?.m_ID?.startsWith("QuickAccess")) nav.SetActiveNavTree(main);
                backdrop(true);
            }
        } else {
            globalThis.__ddQam = false;
            backdrop(false);
            if (m.m_eOpenSideMenu === QAM) m.CloseSideMenus();
        }
        c.EnsureCorrectComposition?.();
        return m.m_eOpenSideMenu;
    })()"""

    fun hold(context: Context): Int? = eval(context, script(true))?.toIntOrNull()

    fun release(context: Context) {
        eval(context, script(false))
        helper(context, "stop")
    }

    fun park(context: Context, width: Int, height: Int) {
        helper(context, "size", width.toString(), height.toString())
    }

    fun target(context: Context): Target? = runCatching {
        val devtools = File(AgentGuest.dir(context), "cdp-port").readText().trim().toInt()
        val relay = helper(context, "start", devtools.toString())?.trim()?.toIntOrNull() ?: return null
        parseTarget(relay, get(relay, "/json"))
    }.onFailure { android.util.Log.w(TAG, "no quick access target: $it") }.getOrNull()

    private fun helper(context: Context, vararg args: String): String? = runCatching {
        val request = JSONObject().put("kind", "exec").put("argv", JSONArray(listOf("droiddeck-qam-park") + args)).put("timeout", 5.0)
        val answer = AgentGuest.call(context, request, 8_000, trusted = true)
        if (answer.optInt("exitCode", -1) == 0) answer.optJSONObject("stdout")?.optString("text") ?: answer.optString("stdout") else null
    }.getOrNull()

    private fun get(port: Int, path: String): String = java.net.Socket().use { s ->
        s.connect(java.net.InetSocketAddress("127.0.0.1", port), 2_000)
        s.soTimeout = 3_000
        s.getOutputStream().apply {
            write("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAccept: */*\r\nConnection: close\r\n\r\n".toByteArray())
            flush()
        }
        val response = s.getInputStream().readBytes().toString(Charsets.UTF_8)
        val split = response.indexOf("\r\n\r\n")
        check(response.startsWith("HTTP/1.1 200") && split > 0) { "devtools answered ${response.take(80)}" }
        response.substring(split + 4)
    }

    internal fun parseTarget(port: Int, json: String): Target? {
        val list = JSONArray(json)
        for (i in 0 until list.length()) {
            val t = list.optJSONObject(i) ?: continue
            if (!t.optString("title").startsWith("QuickAccess")) continue
            val ws = t.optString("webSocketDebuggerUrl")
            val path = Regex("^ws://[^/]+(/devtools/page/[A-Za-z0-9-]+)$").find(ws)?.groupValues?.get(1) ?: continue
            return Target(port, path)
        }
        return null
    }

    private fun eval(context: Context, expression: String): String? = runCatching {
        val answer = AgentGuest.steamEval(context, expression, EVAL_MS)
        if (answer.optBoolean("ok")) answer.opt("value")?.takeIf { it != JSONObject.NULL }?.toString() else null
    }.getOrNull()
}
