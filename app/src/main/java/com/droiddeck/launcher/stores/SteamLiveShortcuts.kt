package com.droiddeck.launcher.stores

import android.content.Context
import android.util.Base64
import android.util.Log
import com.droiddeck.launcher.agent.AgentGuest
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.frontend.LogoPosition
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import org.json.JSONArray
import org.json.JSONObject

object SteamLiveShortcuts {
    private const val TAG = "SteamLive"
    private const val TAG_NAME = "droiddeck-app"
    private const val COMPAT_TOOL = "droiddeck-proton-arm64"
    private const val EVAL_TIMEOUT_MS = 20_000L
    private const val THAW_WAIT_MS = 15_000L

    fun sync(context: Context, games: List<AddedGames.Game>, include: (AddedGames.Game) -> Boolean = { it.source != Library.ADDED }) {
        val wanted = games.filter(include)
        if (wanted.isEmpty() || !clientRunning()) return
        val list = JSONArray()
        for (g in wanted) list.put(JSONObject().put("name", g.name).put("exe", g.guestExe).put("dir", g.guestDir).put("appid", g.appId and 0xFFFFFFFFL).put("launch", g.launchOptions))
        val js = """
            (async () => {
              const want = ${list};
              let added = 0;
              for (const w of want) {
                if (await ${KNOWN}(w.appid, w.name, w.exe)) continue;
                if (await ${ADD}(w.name, w.exe, w.dir, true, ${JSONObject.quote(compatTool(context))}, w.launch)) added++;
              }
              return added;
            })()
        """.trimIndent()
        val result = evaluate(context, js) ?: return
        Log.i(TAG, "live shortcuts: $result added")
        if (result != "0") StoresState.logLine("Steam: $result shortcut(s) added to the running client")
    }

    fun ensure(context: Context, name: String, exe: String, dir: String, compat: Boolean, appId: Long? = null): Long? {
        if (!awake(THAW_WAIT_MS)) return null
        val js = """
            (async () => {
              const known = await ${KNOWN}(${appId?.and(0xFFFFFFFFL) ?: 0}, ${JSONObject.quote(name)}, ${JSONObject.quote(exe)});
              if (known) return known;
              return await ${ADD}(${JSONObject.quote(name)}, ${JSONObject.quote(exe)}, ${JSONObject.quote(dir)}, $compat, ${JSONObject.quote(compatTool(context))});
            })()
        """.trimIndent()
        val id = evaluate(context, js)?.toLongOrNull()?.and(0xFFFFFFFFL)?.takeIf { it != 0L } ?: return null
        Log.i(TAG, "live shortcuts: $name is $id")
        return id
    }

    fun known(context: Context, appId: Long): Boolean? {
        if (!clientRunning()) return null
        val js = """
            (() => {
              const store = globalThis.appStore;
              if (typeof store?.GetAppOverviewByAppID !== 'function' || !store.m_bIsInitialized) return 'unknown';
              return store.GetAppOverviewByAppID(${appId and 0xFFFFFFFFL}) != null ? 'yes' : 'no';
            })()
        """.trimIndent()
        return when (evaluate(context, js)) {
            "yes" -> true
            "no" -> false
            else -> null
        }
    }

    fun remove(context: Context, appId: Long) {
        if (!clientRunning()) return
        val result = evaluate(context, "(async () => { await SteamClient.Apps.RemoveShortcut(${appId and 0xFFFFFFFFL}); return 1; })()")
        if (result != null) Log.i(TAG, "live shortcuts: removed $appId")
    }

    fun update(context: Context, game: AddedGames.Game) {
        if (!clientRunning()) return
        val id = game.appId and 0xFFFFFFFFL
        val js = """
            (async () => {
              const id = $id;
              try { SteamClient.Apps.SetShortcutName(id, ${JSONObject.quote(game.name)}); } catch (e) {}
              try { SteamClient.Apps.SetShortcutExe(id, ${JSONObject.quote("\"" + game.guestExe + "\"")}); } catch (e) {}
              try { SteamClient.Apps.SetShortcutStartDir(id, ${JSONObject.quote("\"" + game.guestDir + "\"")}); } catch (e) {}
              try { SteamClient.Apps.SetShortcutLaunchOptions(id, ${JSONObject.quote(game.launchOptions)}); } catch (e) {}
              return 1;
            })()
        """.trimIndent()
        if (evaluate(context, js) != null) Log.i(TAG, "live shortcuts: updated $id")
    }

    fun setArt(context: Context, appId: Long, assetType: Int?, file: java.io.File?, iconPath: String? = null) {
        if (!clientRunning()) return
        val id = appId and 0xFFFFFFFFL
        val js = when {
            iconPath != null -> "(async () => { try { SteamClient.Apps.SetShortcutIcon($id, ${JSONObject.quote(iconPath)}); } catch (e) {} return 1; })()"
            assetType == null -> return
            file == null -> "(async () => { try { await SteamClient.Apps.ClearCustomArtworkForApp($id, $assetType); } catch (e) {} return 1; })()"
            else -> {
                val data = runCatching { Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) }.getOrNull() ?: return
                val type = if (file.extension.equals("png", true)) "png" else "jpg"
                "(async () => { try { await SteamClient.Apps.SetCustomArtworkForApp($id, ${JSONObject.quote(data)}, ${JSONObject.quote(type)}, $assetType); } catch (e) {} return 1; })()"
            }
        }
        if (evaluate(context, js) != null) Log.i(TAG, "live shortcuts: art $assetType for $id")
    }

    /** Moves and resizes a game's logo in the running client; with null the client's default position comes back. */
    fun setLogoPosition(context: Context, appId: Long, position: LogoPosition.Value?) {
        if (!clientRunning()) return
        val id = appId and 0xFFFFFFFFL
        val js = if (position == null) "(async () => { try { await SteamClient.Apps.ClearCustomLogoPositionForApp($id); } catch (e) {} return 1; })()"
        else "(async () => { try { await SteamClient.Apps.SetCustomLogoPositionForApp($id, ${JSONObject.quote(LogoPosition.toJson(position).toString())}); } catch (e) {} return 1; })()"
        if (evaluate(context, js) != null) Log.i(TAG, "live shortcuts: logo position for $id")
    }

    private fun clientRunning(): Boolean =
        SessionState.running && SessionState.mode == SessionService.MODE_STEAM && !SessionState.stopRequested && !SessionState.suspended

    private fun awake(waitMs: Long): Boolean {
        val end = android.os.SystemClock.uptimeMillis() + waitMs
        while (SessionState.running && SessionState.suspended && android.os.SystemClock.uptimeMillis() < end) Thread.sleep(100)
        return clientRunning()
    }

    private fun evaluate(context: Context, expression: String): String? {
        val answer = try {
            AgentGuest.steamEval(context, expression, EVAL_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "cdp: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        if (!answer.optBoolean("ok")) {
            Log.w(TAG, "cdp: ${answer.optJSONObject("error")?.optString("message")}")
            return null
        }
        return answer.opt("value")?.takeIf { it != JSONObject.NULL }?.toString()
    }

    private val KNOWN = """(async (appid, name, exe) => {
          const store = globalThis.appStore;
          if (typeof store?.GetAppOverviewByAppID !== 'function' || !store.m_bIsInitialized) throw new Error("appStore is not ready");
          const byId = appid ? store.GetAppOverviewByAppID(appid) : null;
          if (byId) return byId.appid;
          const strip = s => (s || "").replace(/^"|"$/g, "");
          const details = id => new Promise(done => {
            let handle = null, answer;
            const finish = d => { clearTimeout(timer); try { handle?.unregister(); } catch (e) {} done(d); };
            const timer = setTimeout(() => finish(null), 4000);
            handle = SteamClient.Apps.RegisterForAppDetails(id, d => { answer = d; if (handle) finish(d); });
            if (answer !== undefined) finish(answer);
          });
          for (const a of store.allApps.filter(a => a.app_type == 1073741824 && a.display_name === name)) {
            const d = await details(a.appid);
            if (!d) throw new Error("no details for shortcut " + a.appid);
            if (strip(d.strShortcutExe) === strip(exe)) return a.appid;
          }
          return 0;
        })"""

    private fun compatTool(context: Context): String =
        com.droiddeck.launcher.session.ProtonDefault.state(context).default ?: COMPAT_TOOL

    private val ADD = """(async (name, exe, dir, compat, tool, launch) => {
          const id = await SteamClient.Apps.AddShortcut(name, exe, dir, launch || "");
          if (!id) return 0;
          try { SteamClient.Apps.SetShortcutName(id, name); } catch (e) {}
          try { SteamClient.Apps.AddUserTagToApps([id], ${JSONObject.quote(TAG_NAME)}); } catch (e) {}
          if (compat) { try { SteamClient.Apps.SpecifyCompatTool(id, tool); } catch (e) {} }
          for (let i = 0; i < 40; i++) {
            try { if (globalThis.appStore.GetAppOverviewByAppID(id)) break; } catch (e) {}
            await new Promise(r => setTimeout(r, 250));
          }
          return id;
        })"""
}
