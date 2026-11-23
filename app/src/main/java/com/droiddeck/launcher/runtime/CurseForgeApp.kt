package com.droiddeck.launcher.runtime

import android.content.Context
import com.droiddeck.launcher.stores.battlenet.BattleNetInstall
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object CurseForgeApp {
    const val NAME = "CurseForge"
    const val FEED = "https://curseforge.overwolf.com/electron/linux/latest-linux.yml"
    internal const val WOW = 1
    const val HELPER = "/usr/local/bin/droiddeck-curseforge"
    internal const val AGENT = "/usr/local/bin/droiddeck-agent"
    private const val READY_MS = 120_000L
    private const val INSTALL_MS = 300_000L

    enum class Start { READY, BUSY, FAILED }

    class Started(val state: Start, val inspector: String? = null)

    internal val INSPECTOR = Regex("ws://127\\.0\\.0\\.1:[0-9]{4,5}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    class Failure(message: String, val code: String? = null) : Exception(message) {
        val gone get() = code == "DEVTOOLS_UNREACHABLE"
    }

    fun find(apps: List<UserApps.App>): UserApps.App? = apps.firstOrNull { it.feed == FEED }

    fun request(): UserApps.Request = UserApps.Request(UserApps.Source.Feed(FEED), NAME, null)

    internal fun dir(app: UserApps.App): String? = app.args.firstOrNull()?.takeIf { it.startsWith("/") }

    internal fun helperArgv(command: String, dir: String): List<String> = listOf(HELPER, command, dir)

    internal fun evalArgv(inspector: String, expression: String, timeoutMs: Long): List<String> =
        listOf(AGENT, "--cdp-inspector", inspector, (timeoutMs / 1000.0).toString(), expression)

    internal fun lastJson(lines: List<String>): JSONObject? =
        lines.asReversed().firstNotNullOfOrNull { line -> runCatching { JSONObject(line.trim()) }.getOrNull() }

    internal fun inspectorUrl(stdout: String): String? = stdout.lineSequence().map { it.trim() }.lastOrNull { INSPECTOR.matches(it) }

    internal fun startState(exitCode: Int): Start = when (exitCode) {
        0 -> Start.READY
        3 -> Start.BUSY
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
            throw Failure(error?.optString("message")?.takeIf { it.isNotBlank() } ?: answer.toString(), error?.optString("code")?.takeIf { it.isNotBlank() })
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
        if (list != null && list.length() > 0) return match?.takeIf { it.isNotBlank() }
        return detected?.takeIf { it.isNotBlank() }
    }

    internal fun projectIds(value: Any?): Set<String> {
        val list = value as? JSONArray ?: return emptySet()
        return (0 until list.length()).mapNotNull { list.opt(it)?.toString()?.takeIf { id -> id.isNotBlank() && id != "null" } }.toSet()
    }

    internal var guest: (Context, List<String>, AtomicBoolean?, (String) -> Unit) -> Int = { context, argv, cancelled, onLine ->
        GuestCommand.run(context, argv, logName = "curseforge", extraBinds = BattleNetInstall.binds(context), fastPath = true,
            cancelled = cancelled, redact = INSPECTOR, onLine = onLine)
    }

    private val holds = ConcurrentHashMap<String, AtomicBoolean>()

    private fun lines(context: Context, argv: List<String>): List<String> {
        val out = ArrayList<String>()
        guest(context, argv, null) { out += it }
        return out
    }

    private fun call(context: Context, inspector: String, service: String, method: String, args: JSONArray?, timeoutMs: Long, pick: String = "r => r"): Any? =
        unwrap(lastJson(lines(context, evalArgv(inspector, expression(service, method, args, pick), timeoutMs))) ?: throw Failure("CurseForge gave no answer"))

    private sealed class Launch {
        class Url(val inspector: String) : Launch()
        class Exit(val code: Int) : Launch()
    }

    private fun launch(context: Context, dir: String, hold: AtomicBoolean): Launch? {
        val first = LinkedBlockingQueue<Launch>()
        Thread({
            val code = runCatching {
                guest(context, helperArgv("start", dir), hold) { line -> inspectorUrl(line)?.let { first.offer(Launch.Url(it)) } }
            }.getOrDefault(-1)
            first.offer(Launch.Exit(code))
        }, "curseforge-headless").apply { isDaemon = true; start() }
        return first.poll(READY_MS, TimeUnit.MILLISECONDS)
    }

    fun start(context: Context, app: UserApps.App, hold: AtomicBoolean): Started {
        val dir = dir(app) ?: return Started(Start.FAILED)
        if (hold.get()) return Started(Start.FAILED)
        holds.put(dir, hold)?.takeIf { it !== hold }?.let {
            it.set(true)
            runCatching { lines(context, helperArgv("stop", dir)) }
        }
        val inspector = when (val first = launch(context, dir, hold)) {
            is Launch.Url -> first.inspector
            is Launch.Exit -> if (startState(first.code) == Start.BUSY) {
                holds.remove(dir, hold)
                return Started(Start.BUSY)
            } else null
            null -> null
        }
        if (inspector == null) {
            stop(context, app, null, hold)
            return Started(Start.FAILED)
        }
        val deadline = System.currentTimeMillis() + READY_MS
        while (System.currentTimeMillis() < deadline && !hold.get()) {
            if (runCatching { call(context, inspector, "InstalledProjectsAppService", "installAddon", null, 10_000) }.isSuccess) return Started(Start.READY, inspector)
            Thread.sleep(1_000)
        }
        stop(context, app, inspector, hold)
        return Started(Start.FAILED)
    }

    fun stop(context: Context, app: UserApps.App, inspector: String?, hold: AtomicBoolean) {
        val dir = dir(app) ?: return
        if (holds[dir] !== hold) {
            hold.set(true)
            return
        }
        if (inspector != null) runCatching { call(context, inspector, "CoreService", "shutdown", JSONArray(), 30_000, "r => true") }
        hold.set(true)
        if (holds.remove(dir, hold)) runCatching { lines(context, helperArgv("stop", dir)) }
    }

    private fun connect(context: Context, inspector: String) {
        call(context, inspector, "CoreService", "connect", JSONArray(), 120_000, "r => true")
    }

    fun instance(context: Context, inspector: String, root: String, flavorDir: String): String {
        connect(context, inspector)
        val detected = call(context, inspector, "GameDetectionAppService", "detectGameFromPath", JSONArray().put(root).put(WOW), 60_000) as? JSONObject
        val instances = call(context, inspector, "GameAppService", "getGameInstances", JSONArray().put(WOW), 30_000,
            "r => (r || []).map(i => ({ guid: i.guid, installPath: i.installPath }))")
        return instanceFor(instances, flavorDir, detected?.optString("gameInstanceGuid")) ?: throw Failure("CurseForge did not find $flavorDir")
    }

    fun search(context: Context, inspector: String, guid: String, text: String, sort: WowAddonCatalog.Sort, page: Int): WowAddonCatalog.Page =
        parseSearch(call(context, inspector, "ProjectAppService", "search", JSONArray().put(searchRequest(guid, text, sort, page)), 60_000))

    fun installed(context: Context, inspector: String, guid: String): Set<String> {
        connect(context, inspector)
        return projectIds(call(context, inspector, "InstalledProjectsAppService", "getProjects", JSONArray().put(guid), 30_000, "r => (r || []).map(p => String(p.id))"))
    }

    fun install(context: Context, inspector: String, guid: String, projectId: String): Boolean {
        connect(context, inspector)
        if (call(context, inspector, "InstalledProjectsAppService", "installAddon", JSONArray().put(installRequest(guid, projectId)), 300_000) != true) return false
        val deadline = System.currentTimeMillis() + INSTALL_MS
        while (System.currentTimeMillis() < deadline) {
            val listed = runCatching { installed(context, inspector, guid) }
            val error = listed.exceptionOrNull()
            if (error is Failure && error.gone) throw error
            if (projectId in listed.getOrDefault(emptySet())) return true
            Thread.sleep(1_000)
        }
        return false
    }
}
