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
    private const val HANDED_BACK = "handedBack"
    internal const val HAND_BACK_FILES = "handBackFiles"
    private const val IMPORTED = "imported"
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

    private fun Map<String, Map<String, ComponentsManager.Choice>>.choiceOf(p: ComponentsManager.Proton, comp: String): ComponentsManager.Choice? =
        this[p.id]?.get(comp)?.takeIf { it.protonVersion == p.version }

    private fun Map<String, Map<String, ComponentsManager.Choice>>.fileOf(p: ComponentsManager.Proton, comp: String): String? = choiceOf(p, comp)?.file

    private class Target(val comp: String, val p: ComponentsManager.Proton, val line: ComponentUpdates.Line, val ls: JSONObject) {
        val key: String get() = line.key
    }

    internal data class PackageRef(val local: String, val name: String, val id: String)

    internal fun catalogRef(item: ComponentsManager.CatalogItem): PackageRef =
        PackageRef(item.localFile, item.file, item.id.takeIf { item.sourceId.isNotEmpty() }.orEmpty())

    internal fun packageRef(context: Context, line: JSONObject, key: String): PackageRef {
        val local = line.optString(key)
        if (local.isEmpty()) return PackageRef("", "", "")
        val stored = ComponentsManager.packageCatalogIdentity(context, local)
        return PackageRef(
            local,
            line.optString("${key}Name").ifEmpty { stored?.name ?: local },
            line.optString("${key}Id").ifEmpty { stored?.id.orEmpty() },
        )
    }

    internal fun putPackageRef(line: JSONObject, key: String, ref: PackageRef?) {
        if (ref == null || ref.local.isEmpty()) {
            line.remove(key)
            line.remove("${key}Name")
            line.remove("${key}Id")
            return
        }
        line.put(key, ref.local).put("${key}Name", ref.name)
        if (ref.id.isEmpty()) line.remove("${key}Id") else line.put("${key}Id", ref.id)
    }

    internal fun relisted(catalog: List<ComponentsManager.CatalogItem>, ref: PackageRef): ComponentsManager.CatalogItem? {
        if (ref.id.isNotEmpty()) return catalog.singleOrNull { it.id == ref.id }
        catalog.singleOrNull { it.localFile == ref.local }?.let { return it }
        return catalog.singleOrNull { it.localFile == it.file && it.file == ref.local && it.file == ref.name }
    }

    internal fun markBad(line: JSONObject, ref: PackageRef) {
        if (ref.id.isEmpty()) line.put("bad", JSONArray(line.strings("bad") + ref.name))
        else line.put("badIds", JSONArray(line.strings("badIds") + ref.id))
    }

    internal fun identityMatches(ref: PackageRef, identity: ComponentsManager.PackageCatalogIdentity?): Boolean =
        ref.id.isEmpty() || (identity?.id == ref.id && identity.name == ref.name)

    internal fun currentAvailable(
        current: PackageRef,
        stored: Boolean,
        identity: ComponentsManager.PackageCatalogIdentity?,
    ): Boolean = current.local.isEmpty() || stored && identityMatches(current, identity)

    internal fun packageRefAvailable(context: Context, ref: PackageRef): Boolean =
        ref.local.isNotEmpty() && ComponentsManager.isStored(context, ref.local) &&
            identityMatches(ref, ComponentsManager.packageCatalogIdentity(context, ref.local))

    internal fun adopt(line: JSONObject, current: PackageRef, target: PackageRef, keepPrevious: Boolean) {
        putPackageRef(line, "previous", current.takeIf { keepPrevious && it.local.isNotEmpty() })
        putPackageRef(line, "current", target)
    }

    internal fun recordRollback(line: JSONObject, bad: PackageRef, back: PackageRef?) {
        markBad(line, bad)
        putPackageRef(line, "current", back)
        putPackageRef(line, "previous", null)
    }

    internal fun badOwned(
        owned: Set<String>,
        keep: Set<String>,
        badIds: Set<String>,
        identityOf: (String) -> ComponentsManager.PackageCatalogIdentity?,
    ): List<String> = owned.filter { identityOf(it)?.id in badIds && it !in keep }

    private fun JSONObject.obj(key: String): JSONObject = optJSONObject(key) ?: JSONObject().also { put(key, it) }

    private fun JSONArray?.strings(): Set<String> = this?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()

    private fun JSONObject.strings(key: String): Set<String> = optJSONArray(key).strings()

    private fun JSONObject?.ints(): Map<String, Int> = this?.keys()?.asSequence()?.associateWith { optInt(it) } ?: emptyMap()

    private fun JSONObject?.texts(): Map<String, String> = this?.keys()?.asSequence()?.associateWith { optString(it) } ?: emptyMap()

    fun settle(context: Context) {
        val owned = load(context).optJSONObject("lines")?.let { lines ->
            lines.keys().asSequence().flatMap { lines.optJSONObject(it)?.strings("owned").orEmpty() }.toSet()
        } ?: emptySet()
        val own = (runCatching { ComponentsManager.protons(context) }.getOrNull() ?: return).filter(ComponentUpdates::follows).mapTo(HashSet()) { it.id }
        val choices = ComponentsManager.activeChoices(context).filterKeys { it in own }.values.flatMap { it.entries }
        for (comp in ComponentsManager.COMPONENTS) {
            if (SessionPrefs.componentModeChosen(context, comp)) continue
            val manual = choices.any { (c, choice) -> c == comp && ComponentUpdates.manual(choice, null, owned) }
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
        if (!ComponentUpdates.follows(p) || !ComponentsManager.supports(p, comp)) return null
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
        val found = runCatching { ComponentsManager.protons(context) }.getOrNull()
        val protons = found ?: emptyList()
        if (found != null) handBack(context, state, found)
        judge(context, state, protons, notes)
        val due = force || System.currentTimeMillis() - state.optLong("checkedAt") >= CHECK_EVERY_MS
        val wanted = ComponentsManager.COMPONENTS.filter { managed(context, it) }
        if (wanted.isEmpty() || protons.isEmpty()) {
            save(context, state)
            return notes
        }
        try {
            dropRemoved(state, protons)
            val checked = due && check(context, state, protons, wanted, force, notes)
            apply(context, state, protons, wanted, notes)
            prune(context, state)
            if (checked) state.put("checkedAt", System.currentTimeMillis())
        } finally {
            save(context, state)
        }
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
            if (rollingBack(ls)) {
                complete = false
                continue
            }
            var current = packageRef(context, ls, "current")
            val listedCurrent = relisted(catalog, current)
            val currentStored = current.local.isNotEmpty() && ComponentsManager.isStored(context, current.local)
            val currentIdentity = current.local.takeIf { currentStored }?.let { ComponentsManager.packageCatalogIdentity(context, it) }
            val missing = !currentAvailable(current, currentStored, currentIdentity)
            val relisted = if (missing) listedCurrent else null
            if (relisted != null) {
                if (holdBack) {
                    state.put("note", context.getString(R.string.comp_auto_waits_unmetered))
                    complete = false
                    continue
                }
                val got = runCatching { ComponentsManager.download(context, relisted) {} }
                if (got.isFailure) {
                    Log.w(TAG, "download ${relisted.file} again", got.exceptionOrNull())
                    state.put("note", context.getString(R.string.comp_auto_check_failed))
                    complete = false
                    continue
                }
                current = catalogRef(relisted)
                putPackageRef(ls, "current", current)
                own(state, ls, current.local)
            }
            val target = ComponentUpdates.pick(catalog, line, ls.strings("bad"), ls.strings("badIds"))
            if (target == null) continue
            val targetRef = catalogRef(target)
            if ((current.id.isNotEmpty() && current.id == targetRef.id) ||
                (current.id.isEmpty() && targetRef.id.isEmpty() && current.local == targetRef.local && current.name == targetRef.name)) {
                if (current != targetRef) putPackageRef(ls, "current", targetRef)
                continue
            }
            val stored = if (targetRef.id.isEmpty()) ComponentsManager.isStored(context, targetRef.local)
                else ComponentsManager.packageMatchesCatalogItem(context, target)
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
            val stocks = protons.filter { lineOf(context, state, it, comp)?.key == line.key }.map { ComponentsManager.arm64x(context, it, comp) }
            if (ComponentUpdates.needsArm64x(line, stocks) && !ComponentsManager.packageArm64x(context, targetRef.local, comp)) {
                Log.w(TAG, "${target.file} is not ARM64X like ${p.name}'s own ${line.key}; skipped")
                markBad(ls, targetRef)
                if (!stored) runCatching { ComponentsManager.deletePackage(context, targetRef.local) }
                continue
            }
            adopt(ls, current, targetRef, keepPrevious = !(missing && relisted == null))
            ls.put("since", latestStamp(context)).put("failed", JSONObject())
            if (!stored) own(state, ls, targetRef.local)
            ls.remove("note")
            notes += context.getString(R.string.comp_auto_updated, ComponentsManager.LABEL[comp], target.file.removeSuffix(".wcp"))
        }
        return complete
    }

    private fun apply(context: Context, state: JSONObject, protons: List<ComponentsManager.Proton>, wanted: List<String>, notes: MutableList<String>) {
        val lines = state.obj("lines")
        val auto = placed(state)
        val choices = ComponentsManager.activeChoices(context)
        val targets = wanted.flatMap { comp ->
            protons.mapNotNull { p -> lineOf(context, state, p, comp)?.let { line -> lines.optJSONObject(line.key)?.let { Target(comp, p, line, it) } } }
        }.filterNot { t -> ComponentUpdates.manual(choices.choiceOf(t.p, t.comp), t.ls.optJSONObject("applied")?.optString(t.p.id), auto, t.ls.optString("current")) }
        for (t in targets) {
            val from = t.ls.optJSONObject("restore")?.optString(t.p.id).orEmpty()
            val current = packageRef(context, t.ls, "current")
            if (from.isNotEmpty()) {
                if (choices.fileOf(t.p, t.comp) == from) runCatching { ComponentsManager.restore(context, t.p.id, t.comp, ComponentsManager.safeName(t.p.version)) }
                    .onFailure { Log.w(TAG, "restore ${t.p.name} ${t.key}", it) }
            } else if (current.local.isNotEmpty() && choices.fileOf(t.p, t.comp) != current.local) {
                if (!packageRefAvailable(context, current)) continue
                if (ComponentUpdates.needsArm64x(t.line, listOf(ComponentsManager.arm64x(context, t.p, t.comp))) &&
                    !ComponentsManager.packageArm64x(context, current.local, t.comp)) continue
                runCatching { ComponentsManager.swap(context, t.p.id, current.local) }
                    .onFailure { Log.w(TAG, "${t.p.name} ${t.key}", it) }
            }
        }
        val after = ComponentsManager.activeChoices(context)
        val unfinished = HashSet<String>()
        for (t in targets) {
            val restore = t.ls.optJSONObject("restore")
            val from = restore?.optString(t.p.id).orEmpty()
            val current = t.ls.optString("current")
            if (from.isNotEmpty()) {
                if (after.fileOf(t.p, t.comp) == from) unfinished += t.key
                else {
                    restore?.remove(t.p.id)
                    if (after.choiceOf(t.p, t.comp)?.file?.startsWith("original ") != false) t.ls.optJSONObject("applied")?.remove(t.p.id)
                }
            } else if (current.isNotEmpty()) {
                if (after.fileOf(t.p, t.comp) == current) t.ls.obj("applied").put(t.p.id, current) else unfinished += t.key
            }
        }
        for (key in lines.keys().asSequence().toList()) {
            val ls = lines.optJSONObject(key) ?: continue
            val pending = ls.optString("pendingNote")
            if (pending.isEmpty() || key in unfinished || (ls.optJSONObject("restore")?.length() ?: 0) > 0) continue
            ls.remove("pendingNote")
            ls.put("note", pending)
            notes += pending
        }
    }

    private fun own(state: JSONObject, ls: JSONObject, file: String) {
        ls.put("owned", JSONArray(ls.strings("owned") + file))
        state.put(IMPORTED, JSONArray(state.strings(IMPORTED) - file))
    }

    fun importPackage(context: Context, source: File, name: String): ComponentsManager.Package = synchronized(lock) {
        ComponentsManager.importPackage(context, source, name).also { disown(context, it.file) }
    }

    fun disown(context: Context, file: String) = synchronized(lock) {
        val state = load(context)
        state.optJSONObject("lines")?.let { lines ->
            lines.keys().asSequence().mapNotNull { lines.optJSONObject(it) }.filter { file in it.strings("owned") }
                .forEach { it.put("owned", JSONArray(it.strings("owned") - file)) }
        }
        state.put(IMPORTED, JSONArray(state.strings(IMPORTED) + file))
        save(context, state)
    }

    internal fun placed(state: JSONObject): Set<String> {
        val remembered = state.strings(HAND_BACK_FILES)
        val lines = state.optJSONObject("lines") ?: return remembered - state.strings(IMPORTED)
        return remembered + lines.keys().asSequence().flatMap { k ->
            val ls = lines.optJSONObject(k) ?: return@flatMap emptySequence()
            (ls.strings("owned") + ls.strings("bad") + ls.optString("current") + ls.optString("previous")).asSequence()
        }.filter { it.isNotEmpty() }.toSet() - state.strings(IMPORTED)
    }

    private fun handBack(context: Context, state: JSONObject, protons: List<ComponentsManager.Proton>) {
        if (state.optBoolean(HANDED_BACK)) return
        val placed = placed(state)
        state.put(HAND_BACK_FILES, JSONArray(placed.sorted()))
        val own = protons.filter(ComponentUpdates::follows).mapTo(HashSet()) { it.id }
        runCatching { ComponentsManager.dropQueuedPackages(context, placed) { it in own || ComponentUpdates.ownId(it) } }.onFailure { Log.w(TAG, "drop queued Auto builds", it) }.getOrNull() ?: return
        val strays = ComponentUpdates.strays(protons, ComponentsManager.activeChoices(context), placed)
        for ((p, comp) in strays) {
            runCatching { ComponentsManager.restoreUnlessQueued(context, p.id, comp, ComponentsManager.safeName(p.version)) }
                .onFailure { Log.w(TAG, "hand back ${p.name} $comp", it) }
        }
        val after = ComponentsManager.activeChoices(context)
        if (ComponentUpdates.strays(protons, after, placed).isEmpty() && !ComponentUpdates.unseen(protons, after, placed)) {
            state.put(HANDED_BACK, true)
            state.remove(HAND_BACK_FILES)
        }
    }

    internal fun dropRemoved(state: JSONObject, protons: List<ComponentsManager.Proton>) {
        val installed = protons.mapTo(HashSet()) { it.id }
        val lines = state.optJSONObject("lines") ?: return
        for (key in lines.keys().asSequence().toList()) {
            val restore = lines.optJSONObject(key)?.optJSONObject("restore") ?: continue
            for (id in restore.keys().asSequence().toList()) if (id !in installed) restore.remove(id)
        }
    }

    internal fun rollingBack(ls: JSONObject): Boolean =
        (ls.optJSONObject("restore")?.length() ?: 0) > 0 || ls.optString("pendingNote").isNotEmpty()

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
        val bad = packageRef(context, ls, "current")
        if (bad.local.isEmpty()) return
        val onLine = protons.filter { lineOf(context, state, it, comp)?.key == key }
        val line = ComponentUpdates.LINES.first { it.key == key }
        val previous = packageRef(context, ls, "previous")
        val back = previous.takeIf { candidate -> packageRefAvailable(context, candidate) &&
            (!ComponentUpdates.needsArm64x(line, onLine.map { ComponentsManager.arm64x(context, it, comp) }) ||
                ComponentsManager.packageArm64x(context, candidate.local, comp)) }
        val choices = ComponentsManager.activeChoices(context)
        val targets = onLine.filter { choices.fileOf(it, comp) == bad.local }
        for (p in targets) {
            runCatching {
                if (back == null) ComponentsManager.restore(context, p.id, comp, ComponentsManager.safeName(p.version))
                else ComponentsManager.swap(context, p.id, back.local)
            }.onFailure { Log.w(TAG, "roll back ${p.name} $key", it) }
        }
        val after = ComponentsManager.activeChoices(context)
        val unfinished = targets.filter { after.fileOf(it, comp) == bad.local }
        for (p in targets - unfinished.toSet()) {
            if (back == null) ls.optJSONObject("applied")?.remove(p.id)
            else if (after.fileOf(p, comp) == back.local) ls.obj("applied").put(p.id, back.local)
        }
        recordRollback(ls, bad, back)
        ls.put("since", latestStamp(context)).put("failed", JSONObject())
        if (back == null) ls.obj("restore").let { restore -> unfinished.forEach { restore.put(it.id, bad.local) } }
        val label = ComponentsManager.LABEL[comp]
        val shown = back?.name?.removeSuffix(".wcp") ?: context.getString(R.string.comp_tag_original)
        val note = context.getString(R.string.comp_auto_rolled_back, label, bad.name.removeSuffix(".wcp"), shown)
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
        val stored = ComponentsManager.storedFiles(context)
        val ownedFiles = lines.keys().asSequence().flatMap { lines.optJSONObject(it)?.strings("owned").orEmpty() }.toSet()
        val identities = (stored + ownedFiles).distinct().associateWith { ComponentsManager.packageCatalogIdentity(context, it) }
        val followed = ComponentUpdates.LINES.filter { managed(context, it.comp) }
        for (k in lines.keys().asSequence().toList()) {
            val ls = lines.optJSONObject(k) ?: continue
            val owned = ls.strings("owned")
            val current = packageRef(context, ls, "current")
            val staleByVersion = ComponentUpdates.stale(
                followed.firstOrNull { it.key == k }, owned, stored, keep, current.name, ls.strings("bad"),
            ) { identities[it]?.name }
            val badIds = ls.strings("badIds")
            val stale = (staleByVersion + badOwned(owned, keep, badIds, identities::get)).distinct()
            val gone = stale.filter { runCatching { ComponentsManager.deleteUnused(context, it) }.getOrDefault(false) }
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
            val current = ls?.let { packageRef(context, it, "current").name }.orEmpty()
            val previous = ls?.let { packageRef(context, it, "previous").name }.orEmpty()
            Status(line.key, current, previous, on.map { it.name }, note)
        }
    }
}
