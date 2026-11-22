package com.droiddeck.launcher.runtime

import android.content.Context
import com.droiddeck.launcher.agent.AgentException
import com.droiddeck.launcher.agent.AgentGuest
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

object CurseForgeApp {
    const val NAME = "CurseForge"
    const val FEED = "https://curseforge.overwolf.com/electron/linux/latest-linux.yml"
    internal const val PORT = 9229
    internal const val WOW = 1
    private const val HELPER = "/usr/local/bin/droiddeck-curseforge"
    private const val READY_MS = 120_000L
    private const val INSTALL_MS = 300_000L

    enum class Start { READY, BUSY, NO_DISPLAY, FAILED }

    class Failure(message: String) : Exception(message)

    fun find(apps: List<UserApps.App>): UserApps.App? = apps.firstOrNull { it.feed == FEED }

    fun request(): UserApps.Request = UserApps.Request(UserApps.Source.Feed(FEED), NAME, null)

    internal fun dir(app: UserApps.App): String? = app.args.firstOrNull()?.takeIf { it.startsWith("/") }

    internal fun startState(exitCode: Int): Start = when (exitCode) {
        0 -> Start.READY
        3 -> Start.BUSY
        4 -> Start.NO_DISPLAY
        else -> Start.FAILED
    }

    internal fun sortOf(sort: WowAddonCatalog.Sort): Pair<Int, Int> = when (sort) {
        WowAddonCatalog.Sort.POPULAR -> 8 to 2
        WowAddonCatalog.Sort.UPDATED -> 16 to 2
        WowAddonCatalog.Sort.NAME -> 1 to 1
        WowAddonCatalog.Sort.DOWNLOADS -> 7 to 2
    }

    internal fun searchRequest(guid: String, text: String, sort: WowAddonCatalog.Sort, page: Int): JSONObject {
        val (column, order) = sortOf(sort)
        return JSONObject().put("gameId", WOW).put("searchText", text.trim()).put("page", page + 1)
            .put("sortColumn", column).put("sortOrder", order).put("gameInstanceGuid", guid)
    }

    internal fun installRequest(guid: String, projectId: String): JSONObject =
        JSONObject().put("instanceId", guid).put("projectId", projectId.toLong()).put("categoryClassId", 1)
            .put("installLatestFile", true).put("installSource", 4)

    private const val SERVICES = """(() => {
  const seen = new Set(), out = {};
  const walk = (o, d) => {
    if (!o || typeof o !== 'object' || seen.has(o) || d > 4) return;
    seen.add(o);
    if (typeof o._nameOfService === 'string') { out[o._nameOfService] = o; return; }
    let keys; try { keys = Object.keys(o); } catch (e) { return; }
    for (const k of keys.slice(0, 4000)) {
      let v; try { v = o[k]; } catch (e) { continue; }
      if (v instanceof Map) { for (const x of v.values()) walk(x, d + 1); } else walk(v, d + 1);
    }
  };
  walk(window, 0);
  return out;
})()"""

    internal fun expression(service: String, method: String, args: JSONArray?, pick: String = "r => r"): String {
        val name = JSONObject.quote(method)
        val run = if (args == null) "true" else "($pick)(await s[$name](...$args))"
        val inner = "(async () => { const s = $SERVICES[${JSONObject.quote(service)}]; " +
            "if (!s || typeof s[$name] !== 'function') return { missing: true }; " +
            "return { value: $run }; })()"
        return "(() => { const w = require('electron').BrowserWindow.getAllWindows()" +
            ".find(w => !w.isDestroyed() && w.webContents.getURL().includes('desktop.html')); " +
            "if (!w) return JSON.stringify({ missing: true }); " +
            "return w.webContents.executeJavaScript(${JSONObject.quote(inner)}).then(v => JSON.stringify(v)); })()"
    }

    internal fun unwrap(answer: JSONObject): Any? {
        if (!answer.optBoolean("ok")) {
            val error = answer.optJSONObject("error")
            throw Failure(error?.optString("message")?.takeIf { it.isNotBlank() } ?: answer.toString())
        }
        val text = answer.opt("value") as? String ?: throw Failure("CurseForge gave no answer")
        val reply = JSONObject(text)
        if (reply.optBoolean("missing")) throw Failure("CurseForge is not ready")
        return reply.opt("value")
    }

    internal fun parseSearch(value: Any?): WowAddonCatalog.Page {
        val reply = value as? JSONObject ?: throw Failure("CurseForge search failed")
        if (reply.has("success") && !reply.optBoolean("success")) throw Failure("CurseForge search failed")
        val mods = reply.optJSONArray("mods") ?: JSONArray()
        val items = (0 until mods.length()).mapNotNull { mods.optJSONObject(it) }.mapNotNull { mod ->
            val id = mod.optLong("id", 0L).takeIf { it > 0 } ?: return@mapNotNull null
            val name = mod.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val author = mod.optJSONObject("primaryAuthor")?.optString("name")?.takeIf { it.isNotBlank() }
                ?: mod.optJSONArray("authors")?.optJSONObject(0)?.optString("name").orEmpty()
            val date = mod.optJSONObject("updateVersion")?.optString("fileDate")?.takeIf { it.isNotBlank() }
                ?: mod.optString("projectLatestReleaseDate")
            WowAddonCatalog.Addon(
                id.toString(), name, author, "", mod.optLong("downloads", 0L).coerceAtLeast(0L),
                runCatching { Instant.parse(date).toEpochMilli() }.getOrDefault(0L),
                mod.optString("thumbnailUrl").takeIf { WowAddonCatalog.isHttps(it) },
            )
        }
        val page = reply.optJSONObject("pagination")
        val more = page != null && page.optInt("index") + page.optInt("resultCount") < page.optInt("totalCount")
        return WowAddonCatalog.Page(items, more)
    }

    internal fun samePath(a: String, b: String) = a.trimEnd('/') == b.trimEnd('/')

    internal fun instanceFor(instances: Any?, flavorDir: String, detected: String?): String? {
        val list = instances as? JSONArray
        val match = list?.let { all ->
            (0 until all.length()).mapNotNull { all.optJSONObject(it) }
                .firstOrNull { samePath(it.optString("installPath"), flavorDir) }?.optString("guid")
        }
        return match?.takeIf { it.isNotBlank() } ?: detected?.takeIf { it.isNotBlank() && (list == null || list.length() <= 1) }
    }

    internal fun projectIds(value: Any?): Set<String> {
        val list = value as? JSONArray ?: return emptySet()
        return (0 until list.length()).mapNotNull { list.opt(it)?.toString()?.takeIf { id -> id.isNotBlank() && id != "null" } }.toSet()
    }

    private fun call(context: Context, service: String, method: String, args: JSONArray?, timeoutMs: Long, pick: String = "r => r"): Any? {
        val request = JSONObject().put("kind", "cdp").put("port", PORT).put("targetType", "node").put("commandLineApi", true)
            .put("expression", expression(service, method, args, pick)).put("timeout", timeoutMs / 1000.0)
        return unwrap(AgentGuest.call(context, request, timeoutMs + 3_000, trusted = true))
    }

    fun start(context: Context, app: UserApps.App): Start {
        val dir = dir(app) ?: return Start.FAILED
        val request = JSONObject().put("kind", "exec").put("argv", JSONArray(listOf(HELPER, "start", dir, PORT.toString())))
            .put("timeout", 90.0)
        val answer = runCatching { AgentGuest.call(context, request, 95_000, trusted = true) }.getOrNull() ?: return Start.FAILED
        if (!answer.optBoolean("ok") || answer.optBoolean("timedOut")) return Start.FAILED
        val state = startState(answer.optInt("exitCode", -1))
        if (state != Start.READY) return state
        val deadline = System.currentTimeMillis() + READY_MS
        while (System.currentTimeMillis() < deadline) {
            val ready = runCatching { call(context, "InstalledProjectsAppService", "installAddon", null, 10_000) }
            if (ready.isSuccess) return Start.READY
            val error = ready.exceptionOrNull()
            if (error is AgentException && error.code == "NO_ACTIVE_SESSION") return Start.FAILED
            Thread.sleep(1_000)
        }
        return Start.FAILED
    }

    fun stop(context: Context, app: UserApps.App) {
        val dir = dir(app) ?: return
        runCatching { call(context, "CoreService", "shutdown", JSONArray(), 30_000, "r => true") }
        val request = JSONObject().put("kind", "exec").put("argv", JSONArray(listOf(HELPER, "stop", dir, PORT.toString())))
            .put("timeout", 30.0)
        runCatching { AgentGuest.call(context, request, 35_000, trusted = true) }
    }

    private fun connect(context: Context) {
        call(context, "CoreService", "connect", JSONArray(), 120_000, "r => true")
    }

    fun instance(context: Context, root: String, flavorDir: String): String {
        connect(context)
        val detected = call(context, "GameDetectionAppService", "detectGameFromPath", JSONArray().put(root).put(WOW), 60_000) as? JSONObject
        val instances = call(context, "GameAppService", "getGameInstances", JSONArray().put(WOW), 30_000,
            "r => (r || []).map(i => ({ guid: i.guid, installPath: i.installPath }))")
        return instanceFor(instances, flavorDir, detected?.optString("gameInstanceGuid")) ?: throw Failure("CurseForge did not find $flavorDir")
    }

    fun search(context: Context, guid: String, text: String, sort: WowAddonCatalog.Sort, page: Int): WowAddonCatalog.Page =
        parseSearch(call(context, "ProjectAppService", "search", JSONArray().put(searchRequest(guid, text, sort, page)), 60_000))

    fun installed(context: Context, guid: String): Set<String> {
        connect(context)
        return projectIds(call(context, "InstalledProjectsAppService", "getProjects", JSONArray().put(guid), 30_000, "r => (r || []).map(p => String(p.id))"))
    }

    fun install(context: Context, guid: String, projectId: String): Boolean {
        connect(context)
        if (call(context, "InstalledProjectsAppService", "installAddon", JSONArray().put(installRequest(guid, projectId)), 300_000) != true) return false
        val deadline = System.currentTimeMillis() + INSTALL_MS
        while (System.currentTimeMillis() < deadline) {
            if (projectId in installed(context, guid)) return true
            Thread.sleep(1_000)
        }
        return false
    }
}
