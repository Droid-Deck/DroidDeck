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

    private val owners = java.util.concurrent.atomic.AtomicLong(0)

    /** A new panel's lease owner: later panels get larger numbers, also across app restarts. */
    fun newOwner(): Long = owners.updateAndGet { maxOf(it + 1, System.currentTimeMillis()) }

    /**
     * Takes or renews the lease for [owner], or releases it. A live lease held by a newer panel is never taken back by an
     * older one, so a late request from a panel being torn down cannot end its replacement's lease.
     */
    internal fun leaseScript(on: Boolean, owner: Long): String =
        if (on) "if (!(g.__ddQamUntil > Date.now()) || !(g.__ddQamOwner > $owner)) { g.__ddQamOwner = $owner; g.__ddQamUntil = Date.now() + $LEASE_MS; }"
        else "if (g.__ddQamOwner === $owner) g.__ddQamUntil = 0;"

    /** [aspect] is the panel's width over height: the menu is laid out at that shape so every tab shows at one scale. */
    internal fun script(on: Boolean, aspect: Float = 0f, owner: Long = 0): String = """(() => {
        const g = globalThis, QAM = 2;
        const W = SteamUIStore.WindowStore.SteamUIWindows[0];
        const m = W.MenuStore, c = W.m_CompositionStateStore;
        const nav = W.m_FocusNavContext;
        const popup = g_PopupManager.GetExistingPopup("SP BPM_uid0");
        const held = () => g.__ddQamUntil > Date.now();
        const ours = () => held() && m.m_eOpenSideMenu === QAM && !g.__ddQamTop;
        const width = (h) => g.__ddQamAspect > 0 ? Math.round(h * g.__ddQamAspect) : 0;
        if (!c.__ddQam) {
            c.__ddQam = c.ComputeOverallMinimumRequestedComposition;
            m.__ddQam = m.OnMenuDeactivated;
            m.OnMenuDeactivated = function (d) { if (d === QAM && held() && !g.__ddQamTop) return; return m.__ddQam.call(this, d); };
        }
        if (c.__ddQamV !== 2) {
            c.__ddQamV = 2;
            // Only the menu's own overlay request is lowered: a dialog over the game still needs Steam to take input.
            c.ComputeOverallMinimumRequestedComposition = function (...a) {
                const v = c.__ddQam.apply(this, a);
                if (v !== 2 || !ours()) return v;
                m.IsSideMenuVisible = () => false;
                m.IsSideMenuInteractable = () => false;
                let without;
                try { without = c.__ddQam.apply(this, a); } finally { delete m.IsSideMenuVisible; delete m.IsSideMenuInteractable; }
                return without >= 2 ? v : 1;
            };
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
                    if (w > 0 && ours()) { x = popup.m_popup.innerWidth + 64; w = width(h) || w; }
                    v.__ddAt = x;
                    v.__ddW = w;
                    return v.__ddQam.call(this, x, y, w, h, ...r);
                };
            }
            if (!held()) {
                const parked = !g.__ddQamTop;
                g.__ddQamTop = false;
                g.__ddQamPress = undefined;
                if (!g.__ddQamOn) return;
                g.__ddQamOn = false;
                backdrop(false);
                if (parked && m.m_eOpenSideMenu === QAM) m.CloseSideMenus();
                c.EnsureCorrectComposition?.();
                return;
            }
            // Steam sends the pad to an open side menu, so a dialog over the game would get no input: step aside until it closes.
            const dialog = nav?.m_rgGamepadNavigationTrees?.some(t => t.m_ID?.startsWith("ModalDialogOverlay") && t.m_Root?.m_element?.isConnected);
            if (dialog && !g.__ddQamTop) {
                g.__ddQamPress = W.m_flLastQuickAccessPressMS || 0;
                if (m.m_eOpenSideMenu === QAM) m.CloseSideMenus();
                if (g.__ddQamOn) { g.__ddQamOn = false; backdrop(false); c.EnsureCorrectComposition?.(); }
                return;
            }
            // The Quick Access button shows the menu on the top screen as usual; closing it there parks it again.
            const press = W.m_flLastQuickAccessPressMS || 0;
            if (g.__ddQamPress === undefined) g.__ddQamPress = press;
            if (press !== g.__ddQamPress) {
                g.__ddQamPress = press;
                g.__ddQamTop = !g.__ddQamTop;
                if (!g.__ddQamTop) { if (m.m_eOpenSideMenu === QAM) m.CloseSideMenus(); return; }
                backdrop(false);
                if (m.m_eOpenSideMenu !== QAM) m.OpenQuickAccessMenu(g.__ddQamTab ?? undefined);
                for (const v of views()) if (v.__ddAsked?.[2] > 0) { v.__ddAt = v.__ddAsked[0]; v.__ddW = v.__ddAsked[2]; v.__ddQam.call(v, ...v.__ddAsked); }
                c.EnsureCorrectComposition?.();
                return;
            }
            if (g.__ddQamTop) {
                if (m.m_eOpenSideMenu === QAM) {
                    if (m.m_eQuickAccessTab != null) g.__ddQamTab = m.m_eQuickAccessTab;
                    return;
                }
                g.__ddQamTop = false;
            }
            const decky = !!g.DeckyPluginLoader?.tabsHook?.qamBrowserViewPatch;
            // Decky hooks the menu after Steam starts; the menu has to unmount for its tab to appear, so reopen on the next tick.
            if (m.m_eOpenSideMenu === QAM && decky && !g.__ddQamDecky) { g.__ddQamDecky = true; m.CloseSideMenus(); return; }
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
                    if (b && b.width > 0) { v.__ddAsked = [b.x, b.y, b.width, b.height]; v.__ddAt = b.x; v.__ddW = b.width; }
                }
                const a = v.__ddAsked;
                if (a?.[2] > 0 && (v.__ddAt < popup.m_popup.innerWidth || (width(a[3]) && v.__ddW !== width(a[3])))) v.SetBounds(...a);
            }
            const main = nav?.m_rgGamepadNavigationTrees?.find(t => t.m_ID === "GamepadUI_Full_Root");
            if (main && nav.m_LastActiveNavTree?.m_ID?.startsWith("QuickAccess")) nav.SetActiveNavTree(main);
            backdrop(true);
        };
        ${leaseScript(on, owner)}
        ${if (on && aspect > 0f) "g.__ddQamAspect = $aspect;" else ""}
        keep();
        if (held() && !g.__ddQamTimer) {
            g.__ddQamTimer = setInterval(() => {
                keep();
                if (!held() && !g.__ddQamOn) { clearInterval(g.__ddQamTimer); g.__ddQamTimer = 0; }
            }, 250);
        }
        return JSON.stringify([m.m_eOpenSideMenu, g.__ddQamTop ? 1 : 0]);
    })()"""

    /** [open] is Steam's open side menu; [top] is true while the menu is shown on the top screen. */
    data class Hold(val open: Int, val top: Boolean)

    fun hold(context: Context, aspect: Float, owner: Long): Hold? = eval(context, script(true, aspect, owner))?.let(::parseHold)

    internal fun parseHold(text: String): Hold? = runCatching {
        val a = JSONArray(text)
        Hold(a.getInt(0), a.getInt(1) == 1)
    }.getOrNull()

    fun release(context: Context, relayPort: Int, owner: Long) {
        eval(context, script(false, owner = owner))
        stopRelay(context, relayPort)
    }

    fun stopRelay(context: Context, relayPort: Int) {
        if (relayPort > 0) helper(context, "stop", relayPort.toString())
    }

    /** Starts the guest relay to Steam's DevTools and finds the menu's page; the relay is stopped again when there is none. */
    fun target(context: Context): Target? = runCatching {
        val devtools = File(AgentGuest.dir(context), "cdp-port").readText().trim().toInt()
        val (relay, secret) = parseRelay(helper(context, "start", devtools.toString())) ?: return null
        val found = runCatching { parseTarget(relay, get(relay, "/$secret/json"))?.let { it.copy(path = "/$secret${it.path}") } }
        if (found.getOrNull() == null) helper(context, "stop", relay.toString())
        found.getOrThrow()
    }.onFailure { android.util.Log.e(TAG, "no quick access target: $it") }.getOrNull()

    internal fun parseRelay(text: String?): Pair<Int, String>? {
        val parts = text?.trim()?.split(' ') ?: return null
        val port = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val secret = parts.getOrNull(1)?.takeIf { it.matches(Regex("[0-9a-f]{16,64}")) } ?: return null
        return port to secret
    }

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
