package com.droiddeck.launcher.session

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.AutoComponents
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ComponentUpdater {
    private const val TAG = "ComponentUpdater"
    private const val CHECK_EVERY_MS = 3_600_000L
    private const val BOOTED_KEPT = 200
    private val lock = Any()
    private val io = Any()

    data class Status(val line: String, val current: String, val previous: String, val protons: List<String>, val note: String)

    private fun file(context: Context) = File(File(context.filesDir, "components").apply { mkdirs() }, "updates.json")

    private fun load(context: Context): JSONObject = synchronized(io) {
        runCatching { JSONObject(String(AtomicFile(file(context)).readFully(), Charsets.UTF_8)) }.getOrDefault(JSONObject())
    }

    private fun save(context: Context, state: JSONObject) {
        synchronized(io) {
            val atomic = AtomicFile(file(context))
            val output = runCatching { atomic.startWrite() }.onFailure { Log.w(TAG, "could not save", it) }.getOrNull() ?: return
            try {
                output.write(state.toString(1).toByteArray(Charsets.UTF_8))
                atomic.finishWrite(output)
            } catch (error: Exception) {
                atomic.failWrite(output)
                Log.w(TAG, "could not save", error)
            }
        }
    }

    private fun Map<String, Map<String, ComponentsManager.Choice>>.fileOf(p: ComponentsManager.Proton, comp: String): String? =
        this[p.id]?.get(comp)?.takeIf { it.protonVersion == p.version }?.file

    private fun JSONObject.obj(key: String): JSONObject = optJSONObject(key) ?: JSONObject().also { put(key, it) }

    private fun JSONArray?.strings(): Set<String> = this?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()

    private fun JSONObject.strings(key: String): Set<String> = optJSONArray(key).strings()

    private fun JSONObject?.ints(): Map<String, Int> = this?.keys()?.asSequence()?.associateWith { optInt(it) } ?: emptyMap()

    private fun JSONObject?.texts(): Map<String, String> = this?.keys()?.asSequence()?.associateWith { optString(it) } ?: emptyMap()

    fun settle(context: Context) {
        val owned = load(context).optJSONObject("lines")?.let { lines -> lines.keys().asSequence().flatMap { lines.optJSONObject(it).strings("owned") }.toSet() } ?: emptySet()
        val choices = ComponentsManager.activeChoices(context).values.flatMap { it.entries }
        for (comp in ComponentsManager.COMPONENTS) {
            if (SessionPrefs.componentModeChosen(context, comp)) continue
            val manual = choices.any { (c, choice) -> c == comp && !choice.auto && choice.file !in owned && !choice.file.startsWith("original ") }
            SessionPrefs.setComponentMode(context, comp, if (manual) ComponentUpdates.MANUAL else ComponentUpdates.AUTO)
        }
    }

    private fun managed(context: Context, comp: String): Boolean =
        SessionPrefs.componentMode(context, comp) == ComponentUpdates.AUTO && !(comp == "dxvk" && ComponentsManager.hardwareDxvk(context))

    private fun logLines(context: Context): Sequence<String> {
        val dir = ComponentsManager.steamLogs(context)
        return sequenceOf("console_log.previous.txt", "console_log.txt").map { File(dir, it) }.filter { it.isFile }
            .flatMap { f -> runCatching { f.readLines() }.getOrDefault(emptyList()).asSequence() }
    }

    private val STAMP = Regex("""^\[(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)]""")

    private fun latestStamp(context: Context): String =
        logLines(context).mapNotNull { STAMP.find(it)?.groupValues?.get(1) }.maxOrNull() ?: "0"

    private fun lineOf(context: Context, state: JSONObject, p: ComponentsManager.Proton, comp: String): ComponentUpdates.Line? {
        if (!ComponentsManager.supports(p, comp)) return null
        if (comp == "fex") return ComponentUpdates.line("fex", false)
        val key = "${p.id}|${p.version}"
        val flavors = state.obj("flavors").obj(key)
        val arm64ec = if (flavors.has(comp)) flavors.optBoolean(comp) else ComponentsManager.arm64ec(context, p, comp)?.also { flavors.put(comp, it) }
        return arm64ec?.let { ComponentUpdates.line(comp, it) }
    }

    fun ensure(context: Context, force: Boolean): List<String> = synchronized(lock) {
        if (SessionState.running) return emptyList()
        settle(context)
        val state = load(context)
        val notes = ArrayList<String>()
        val protons = runCatching { ComponentsManager.protons(context) }.getOrDefault(emptyList())
        judge(context, state, protons, notes)
        val due = force || System.currentTimeMillis() - state.optLong("checkedAt") >= CHECK_EVERY_MS
        val wanted = ComponentsManager.COMPONENTS.filter { managed(context, it) }
        if (wanted.isEmpty() || protons.isEmpty()) {
            save(context, state)
            return notes
        }
        val checked = due && check(context, state, protons, wanted, force, notes)
        apply(context, state, protons, wanted, notes)
        prune(context, state)
        if (checked) state.put("checkedAt", System.currentTimeMillis())
        save(context, state)
        notes
    }

    private fun check(
        context: Context, state: JSONObject, protons: List<ComponentsManager.Proton>, wanted: List<String>, force: Boolean, notes: MutableList<String>,
    ): Boolean {
        if (!AutoComponents.isOnline(context)) {
            state.put("note", context.getString(R.string.comp_auto_waits_network))
            return false
        }
        val catalog = runCatching { ComponentsManager.catalog(context, true).items }
            .onFailure { Log.w(TAG, "catalog", it) }.getOrDefault(emptyList())
        if (catalog.none { it.release == ComponentsManager.DD_NIGHTLY }) {
            state.put("note", context.getString(R.string.comp_auto_check_failed))
            return false
        }
        val holdBack = !force && AutoComponents.constrained(context)
        val lines = state.obj("lines")
        var complete = true
        state.remove("note")
        for (comp in wanted) for (p in protons) {
            val line = lineOf(context, state, p, comp) ?: continue
            val ls = lines.obj(line.key)
            val target = ComponentUpdates.pick(catalog, line, ls.strings("bad"))
            if (target == null || ls.optString("current") == target.file) continue
            val stored = ComponentsManager.isStored(context, target.file)
            if (!stored) {
                if (holdBack) {
                    state.put("note", context.getString(R.string.comp_auto_waits_unmetered))
                    complete = false
                    continue
                }
                val got = runCatching { ComponentsManager.download(context, target) {} }
                if (got.isFailure) {
                    Log.w(TAG, "download ${target.file}", got.exceptionOrNull())
                    state.put("note", context.getString(R.string.comp_auto_check_failed))
                    complete = false
                    continue
                }
            }
            ls.put("previous", ls.optString("current").ifEmpty { ComponentUpdates.ORIGINAL })
                .put("current", target.file).put("since", latestStamp(context)).put("failed", JSONObject())
            if (!stored) ls.put("owned", JSONArray(ls.strings("owned") + target.file))
            ls.remove("note")
            notes += context.getString(R.string.comp_auto_updated, ComponentsManager.LABEL[comp], target.file.removeSuffix(".wcp"))
        }
        return complete
    }

    private fun apply(context: Context, state: JSONObject, protons: List<ComponentsManager.Proton>, wanted: List<String>, notes: MutableList<String>) {
        val lines = state.obj("lines")
        val choices = ComponentsManager.activeChoices(context)
        val unfinished = HashSet<String>()
        for (comp in wanted) for (p in protons) {
            val line = lineOf(context, state, p, comp) ?: continue
            val ls = lines.optJSONObject(line.key) ?: continue
            val restore = ls.strings("restore")
            if (p.id in restore) {
                val done = runCatching { ComponentsManager.restore(context, p.id, comp, ComponentsManager.safeName(p.version)) }
                    .onFailure { Log.w(TAG, "restore ${p.name} ${line.key}", it) }.isSuccess
                if (done) ls.put("restore", JSONArray(restore - p.id)) else unfinished += line.key
                continue
            }
            val current = ls.optString("current")
            if (current.isNotEmpty() && choices.fileOf(p, comp) != current) {
                runCatching { ComponentsManager.swap(context, p.id, current) }
                    .onFailure { Log.w(TAG, "${p.name} ${line.key}", it); unfinished += line.key }
            }
        }
        for (key in lines.keys().asSequence().toList()) {
            val ls = lines.optJSONObject(key) ?: continue
            val pending = ls.optString("pendingNote")
            if (pending.isEmpty() || key in unfinished || ls.strings("restore").isNotEmpty()) continue
            ls.remove("pendingNote")
            ls.put("note", pending)
            notes += pending
        }
    }

    private fun judge(context: Context, state: JSONObject, protons: List<ComponentsManager.Proton>, notes: MutableList<String>) {
        val mark = state.optString("logMark")
        val runs = ComponentUpdates.launches(logLines(context), mark)
        if (runs.isEmpty()) return
        state.put("logMark", runs.maxOf { it.start })
        val byTool = protons.associateBy { it.dir.name }
        val lines = state.obj("lines")
        for (comp in ComponentsManager.COMPONENTS) {
            val onLine = HashMap<String, MutableList<ComponentUpdates.Launch>>()
            for (run in runs) {
                val p = byTool[run.tool] ?: continue
                if (ComponentsManager.gameComponents(context, run.app).containsKey(comp)) continue
                val line = lineOf(context, state, p, comp) ?: continue
                onLine.getOrPut(line.key) { ArrayList() } += run
            }
            for ((key, list) in onLine) {
                val ls = lines.obj(key)
                val verdict = ComponentUpdates.judge(ComponentUpdates.Trial(
                    ls.optString("current"), ls.optString("previous"), ls.optString("since"),
                    ls.optJSONObject("booted").texts(), ls.optJSONObject("failed").ints(),
                ), list)
                ls.put("booted", JSONObject(verdict.booted.entries.toList().takeLast(BOOTED_KEPT).associate { it.key to it.value } as Map<*, *>))
                ls.put("failed", JSONObject(verdict.failed as Map<*, *>))
                if (verdict.rollBack && managed(context, comp)) rollBack(context, state, protons, comp, key, notes)
            }
        }
    }

    private fun rollBack(context: Context, state: JSONObject, protons: List<ComponentsManager.Proton>, comp: String, key: String, notes: MutableList<String>) {
        val ls = state.obj("lines").obj(key)
        val bad = ls.optString("current")
        val back = ls.optString("previous")
        val choices = ComponentsManager.activeChoices(context)
        val unfinished = ArrayList<String>()
        for (p in protons) {
            if (lineOf(context, state, p, comp)?.key != key || choices.fileOf(p, comp) != bad) continue
            runCatching {
                if (back == ComponentUpdates.ORIGINAL) ComponentsManager.restore(context, p.id, comp, ComponentsManager.safeName(p.version))
                else ComponentsManager.swap(context, p.id, back)
            }.onFailure { Log.w(TAG, "roll back ${p.name} $key", it); unfinished += p.id }
        }
        ls.put("bad", JSONArray(ls.strings("bad") + bad)).put("current", back).put("previous", ComponentUpdates.ORIGINAL)
            .put("since", latestStamp(context)).put("failed", JSONObject())
        if (back == ComponentUpdates.ORIGINAL) ls.put("restore", JSONArray(ls.strings("restore") + unfinished))
        val label = ComponentsManager.LABEL[comp]
        val shown = back.ifEmpty { context.getString(R.string.comp_tag_original) }.removeSuffix(".wcp")
        val note = context.getString(R.string.comp_auto_rolled_back, label, bad.removeSuffix(".wcp"), shown)
        Log.w(TAG, note)
        if (unfinished.isNotEmpty()) {
            ls.put("pendingNote", note)
            return
        }
        ls.remove("pendingNote")
        ls.put("note", note)
        notes += note
        prune(context, state)
    }

    private fun prune(context: Context, state: JSONObject) {
        val lines = state.obj("lines")
        val selected = ComponentsManager.selectedPackages(context)
        val keep = lines.keys().asSequence().flatMap { k ->
            val ls = lines.optJSONObject(k) ?: return@flatMap emptySequence()
            ComponentUpdates.keep(ls.optString("current"), ls.optString("previous"), selected).asSequence()
        }.toSet()
        for (k in lines.keys().asSequence().toList()) {
            val ls = lines.optJSONObject(k) ?: continue
            val owned = ls.strings("owned")
            val gone = owned.filter { it !in keep && runCatching { ComponentsManager.deletePackage(context, it) }.isSuccess }
            ls.put("owned", JSONArray(owned - gone.toSet()))
        }
    }

    fun status(context: Context, comp: String): List<Status> {
        val state = load(context)
        val lines = state.optJSONObject("lines") ?: JSONObject()
        val protons = runCatching { ComponentsManager.protons(context) }.getOrDefault(emptyList())
        val byLine = protons.groupBy { lineOf(context, state, it, comp)?.key }
        return ComponentUpdates.LINES.filter { it.comp == comp }.mapNotNull { line ->
            val on = byLine[line.key].orEmpty()
            val ls = lines.optJSONObject(line.key)
            if (on.isEmpty() && ls == null) return@mapNotNull null
            val note = ls?.optString("note").orEmpty().ifEmpty { state.optString("note") }
            Status(line.key, ls?.optString("current").orEmpty(), ls?.optString("previous").orEmpty(), on.map { it.name }, note)
        }
    }
}
