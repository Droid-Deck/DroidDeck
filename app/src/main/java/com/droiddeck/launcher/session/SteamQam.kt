package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.agent.AgentGuest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Steam renders the quick access menu only while it is open. While the app holds it, the menu is kept open with its
// browser view placed outside the Big Picture window, composition capped at "notification" so gamescope keeps the game
// focused, and gamepad focus on the main UI. The hold is a lease the app renews, so Steam recovers if the app goes away.
object SteamQam {
    data class Target(val port: Int, val path: String)

    const val QUICK_ACCESS = 2
    const val LEASE_MS = 5_000
    private const val TAG = "SteamQam"
    private const val EVAL_MS = 6_000L

    internal fun script(on: Boolean): String = """(() => {
        const g = globalThis, QAM = 2;
        const W = SteamUIStore.WindowStore.SteamUIWindows[0];
        const m = W.MenuStore, c = W.m_CompositionStateStore;
        const nav = W.m_FocusNavContext;
        const popup = g_PopupManager.GetExistingPopup("SP BPM_uid0");
        const held = () => g.__ddQamUntil > Date.now();
        const ours = () => held() && m.m_eOpenSideMenu === QAM;
        if (!c.__ddQam) {
            c.__ddQam = c.ComputeOverallMinimumRequestedComposition;
            c.ComputeOverallMinimumRequestedComposition = function (...a) {
                const v = c.__ddQam.apply(this, a);
                return v === 2 && ours() ? 1 : v;
            };
            m.__ddQam = m.OnMenuDeactivated;
            m.OnMenuDeactivated = function (d) { if (d === QAM && held()) return; return m.__ddQam.call(this, d); };
        }
        const views = () => popup?.m_rgChildBrowserViews || [];
        const backdrop = (hide) => {
            const doc = popup?.m_popup?.document;
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
        const keep = () => {
            for (const v of views()) {
                if (v.__ddQam) continue;
                v.__ddQam = v.SetBounds;
                v.SetBounds = function (x, y, w, h, ...r) {
                    v.__ddAsked = [x, y, w, h];
                    if (w > 0 && ours()) x = popup.m_popup.innerWidth + 64;
                    v.__ddAt = x;
                    return v.__ddQam.call(this, x, y, w, h, ...r);
                };
            }
            if (!held()) {
                if (!g.__ddQamOn) return;
                g.__ddQamOn = false;
                backdrop(false);
                if (m.m_eOpenSideMenu === QAM) m.CloseSideMenus();
                c.EnsureCorrectComposition?.();
                return;
            }
            const decky = !!g.DeckyPluginLoader?.tabsHook?.qamBrowserViewPatch;
            if (m.m_eOpenSideMenu === QAM && decky && !g.__ddQamDecky) m.CloseSideMenus();
            if (m.m_eOpenSideMenu === 0) {
                m.OpenQuickAccessMenu(g.__ddQamTab ?? undefined);
                g.__ddQamDecky = decky;
                c.EnsureCorrectComposition?.();
            }
            if (m.m_eOpenSideMenu !== QAM) return;
            if (m.m_eQuickAccessTab != null) g.__ddQamTab = m.m_eQuickAccessTab;
            if (!g.__ddQamOn) { g.__ddQamOn = true; c.EnsureCorrectComposition?.(); }
            for (const v of views()) {
                if (!v.__ddAsked) {
                    const b = v.GetBounds?.();
                    if (b && b.width > 0) { v.__ddAsked = [b.x, b.y, b.width, b.height]; v.__ddAt = b.x; }
                }
                if (v.__ddAsked?.[2] > 0 && v.__ddAt < popup.m_popup.innerWidth) v.SetBounds(...v.__ddAsked);
            }
            const main = nav?.m_rgGamepadNavigationTrees?.find(t => t.m_ID === "GamepadUI_Full_Root");
            if (main && nav.m_LastActiveNavTree?.m_ID?.startsWith("QuickAccess")) nav.SetActiveNavTree(main);
            backdrop(true);
        };
        g.__ddQamUntil = ${if (on) "Date.now() + $LEASE_MS" else "0"};
        keep();
        if (held() && !g.__ddQamTimer) {
            g.__ddQamTimer = setInterval(() => {
                keep();
                if (!held() && !g.__ddQamOn) { clearInterval(g.__ddQamTimer); g.__ddQamTimer = 0; }
            }, 250);
        }
        return m.m_eOpenSideMenu;
    })()"""

    fun hold(context: Context): Int? = eval(context, script(true))?.toIntOrNull()

    fun release(context: Context, relayPort: Int) {
        eval(context, script(false))
        if (relayPort > 0) helper(context, "stop", relayPort.toString())
    }

    fun target(context: Context): Target? = runCatching {
        val devtools = File(AgentGuest.dir(context), "cdp-port").readText().trim().toInt()
        val relay = helper(context, "start", devtools.toString())?.trim()?.toIntOrNull() ?: return null
        parseTarget(relay, get(relay, "/json"))
    }.onFailure { android.util.Log.e(TAG, "no quick access target: $it") }.getOrNull()

    private fun helper(context: Context, vararg args: String): String? = runCatching {
        val request = JSONObject().put("kind", "exec").put("argv", JSONArray(listOf("droiddeck-qam-relay") + args)).put("timeout", 5.0)
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
        httpBody(s.getInputStream())
    }

    /** Reads one response, by its Content-Length when it has one: DevTools does not always close the connection. */
    internal fun httpBody(input: java.io.InputStream): String {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            check(b >= 0 && head.length < 16_384) { "devtools answered ${head.take(80)}" }
            head.append(b.toChar())
        }
        check(head.startsWith("HTTP/1.1 200")) { "devtools answered ${head.take(80)}" }
        val length = Regex("(?im)^content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toInt()
        val body = if (length == null) input.readBytes() else ByteArray(length).also { buf ->
            var read = 0
            while (read < length) {
                val n = input.read(buf, read, length - read)
                check(n > 0) { "devtools closed after $read of $length bytes" }
                read += n
            }
        }
        return body.toString(Charsets.UTF_8)
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
