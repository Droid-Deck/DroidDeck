package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.Hashes
import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * FEX, DXVK and VKD3D-Proton per Proton, for the Linux Steam client.
 *
 * Every Proton the client runs games with keeps its own copy of these three inside its tree. This
 * lists them, stores packages (.wcp: tar + zstd with a profile.json) downloaded from the Nightlies
 * "-Linux" releases or imported, swaps one into a Proton, keeps the Proton's shipped files as
 * "original bundles" - one per Proton build, so a Steam update adds a new one instead of replacing
 * the last - restores those, and deletes stored packages.
 *
 * Where a Proton reads each component (its own `proton` script, ARM64 host):
 *   DXVK   files/lib/wine/dxvk/{aarch64,i386}-windows          -> the prefix's system32 / syswow64
 *   VKD3D  files/lib/wine/vkd3d-proton/{aarch64,i386}-windows  -> the same, on every launch
 *   FEX    files/lib/wine/aarch64-windows/lib{arm64ec,wow64}fex.dll
 *          files/lib/wine/aarch64-unix/lib{arm64ec,wow64}fex.so  (FEX 2607 and newer)
 * Proton copies DXVK and VKD3D into the prefix on every normal launch, so a swap applies the next
 * time a game starts. A swap into a Proton a running game is using waits until that game exits.
 *
 * Same formats and data layout as the Decky "Components Manager" plugin, whose data is taken over
 * once on first use.
 */
object ComponentsManager {
    private const val TAG = "Components"
    private const val NIGHTLIES = "The412Banner/Nightlies"
    /** Release tag -> component. Only the Linux repacks: the Android releases carry bionic unixlibs. */
    private val RELEASES = linkedMapOf(
        "FexCore-Linux" to "fex",
        "Dxvk-Linux" to "dxvk",
        "Dxvk-arm64ec-Linux" to "dxvk",
        "Dxvk-gplasync-Linux" to "dxvk",
        "Vkd3d-proton-Linux" to "vkd3d",
    )
    private val TYPES = mapOf("FEXCore-Linux" to "fex", "DXVK-Linux" to "dxvk", "VKD3D-Linux" to "vkd3d")
    private val TYPE_OF = mapOf("fex" to "FEXCore-Linux", "dxvk" to "DXVK-Linux", "vkd3d" to "VKD3D-Linux")
    val COMPONENTS = listOf("fex", "dxvk", "vkd3d")
    val LABEL = mapOf("fex" to "FEX", "dxvk" to "DXVK", "vkd3d" to "VKD3D-Proton")

    private const val WINE = "files/lib/wine"
    private val FEX_FILES = listOf(
        "$WINE/aarch64-windows/libarm64ecfex.dll",
        "$WINE/aarch64-windows/libwow64fex.dll",
        "$WINE/aarch64-unix/libarm64ecfex.so",
        "$WINE/aarch64-unix/libwow64fex.so",
    )
    private val COMP_DIR = mapOf("dxvk" to "$WINE/dxvk", "vkd3d" to "$WINE/vkd3d-proton")
    /** The folders a Proton reads on ARM64. */
    private val PE_ARCHES = listOf("aarch64-windows", "i386-windows")
    /**
     * The DLLs that ARE the component. A swap clears exactly these before unpacking, so no file of
     * the previous version is left mixed in; anything else a Proton keeps beside them - GE's
     * openvr_api_dxvk.dll, which its launcher copies into every prefix and aborts without - stays.
     */
    private val COMP_DLLS = mapOf(
        "dxvk" to setOf("d3d8.dll", "d3d9.dll", "d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll"),
        "vkd3d" to setOf("d3d12.dll", "d3d12core.dll"),
    )
    private const val PLUGIN_DATA = "root/homebrew/data/decky-components-manager"

    data class Proton(val id: String, val name: String, val dir: File, val guestPath: String, val version: String, val valve: Boolean)
    data class Component(val detected: String, val detail: String, val inUse: String, val activeFile: String?, val queued: String?)
    data class Original(val comp: String, val protonVersion: String, val label: String, val size: Long)
    data class Package(val file: String, val comp: String, val version: String, val description: String, val size: Long)
    data class CatalogItem(val file: String, val comp: String, val release: String, val url: String, val size: Long, val digest: String)
    data class ProtonView(
        val proton: Proton, val components: Map<String, Component>, val originals: List<Original>, val inUseByGame: Boolean,
        /** When a launch last had to put this Proton's chosen files back (epoch seconds), or 0. */
        val reappliedAt: Long = 0,
    )
    data class Snapshot(val protons: List<ProtonView>, val packages: List<Package>)
    data class Catalog(val items: List<CatalogItem>, val fetchedAt: Long)

    private val lock = Any()

    // ------------------------------------------------------------------ paths

    private fun root(context: Context) = LinuxRuntime.rootDir(context)
    private fun steam(context: Context) = File(root(context), "root/.local/share/Steam")
    private fun host(context: Context, guest: String) = File(root(context), guest.removePrefix("/"))
    private fun dataDir(context: Context) = File(context.filesDir, "components").apply { mkdirs() }
    private fun packagesDir(context: Context) = File(dataDir(context), "packages").apply { mkdirs() }
    private fun originalsDir(context: Context) = File(dataDir(context), "originals").apply { mkdirs() }
    private fun stateFile(context: Context) = File(dataDir(context), "state.json")
    private fun catalogFile(context: Context) = File(dataDir(context), "catalog.json")
    /**
     * Inside the Linux runtime, read by the Proton launch wrappers (steam-compatibility's
     * bl_components) before every game launch: desired.tsv + an unpacked copy of each package in use.
     */
    private const val LAUNCH_DIR = "root/.local/share/bannerlator-components"
    private fun launchDir(context: Context) = File(root(context), LAUNCH_DIR)

    fun safeName(s: String): String = s.replace(Regex("[^A-Za-z0-9._+-]+"), "_").trim('_').ifEmpty { "unnamed" }

    // ------------------------------------------------------------------ state

    private fun loadState(context: Context): JSONObject =
        runCatching { JSONObject(stateFile(context).readText()) }.getOrElse { JSONObject() }.apply {
            if (!has("active")) put("active", JSONObject())
            if (!has("queued")) put("queued", JSONObject())
        }

    private fun saveState(context: Context, state: JSONObject) {
        val f = stateFile(context)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(state.toString(2))
        tmp.renameTo(f)
        // Every change of choice reaches the launch wrappers at once.
        runCatching { syncLaunchState(context, state) }.onFailure { Log.w(TAG, "launch state", it) }
    }

    private fun JSONObject.sub(key: String): JSONObject = optJSONObject(key) ?: JSONObject().also { put(key, it) }

    /** Takes over the Decky plugin's packages, originals and swap records, once. */
    private fun migratePlugin(context: Context) {
        val marker = File(dataDir(context), ".plugin-imported")
        if (marker.isFile) return
        val plugin = File(root(context), PLUGIN_DATA)
        if (plugin.isDirectory) {
            runCatching {
                File(plugin, "packages").listFiles()?.forEach { f ->
                    if (f.isFile && f.name.endsWith(".wcp")) {
                        val dst = File(packagesDir(context), f.name)
                        if (!dst.exists()) f.copyTo(dst)
                    }
                }
                val originals = File(plugin, "originals")
                if (originals.isDirectory) originals.copyRecursively(originalsDir(context), overwrite = false)
                val ps = File(plugin, "state.json")
                if (ps.isFile && !stateFile(context).isFile) ps.copyTo(stateFile(context))
                Log.i(TAG, "took over the Decky plugin's data from $plugin")
            }.onFailure { Log.w(TAG, "plugin data import", it) }
        }
        marker.writeText("1\n")
    }

    // ------------------------------------------------------------------ Protons

    private fun libraryRoots(context: Context): List<File> {
        val roots = mutableListOf(steam(context))
        val vdf = File(steam(context), "steamapps/libraryfolders.vdf")
        runCatching {
            Regex("\"path\"\\s+\"([^\"]+)\"").findAll(vdf.readText()).forEach {
                val h = host(context, it.groupValues[1])
                if (roots.none { r -> r.canonicalPath == h.canonicalPath }) roots.add(h)
            }
        }
        return roots
    }

    private fun displayName(dir: File): String = runCatching {
        Regex("\"display_name\"\\s+\"([^\"]+)\"").find(File(dir, "compatibilitytool.vdf").readText())?.groupValues?.get(1)
    }.getOrNull() ?: dir.name

    private fun protonVersion(dir: File): String = runCatching {
        val parts = File(dir, "version").useLines { it.firstOrNull() ?: "" }.trim().split(Regex("\\s+"))
        if (parts.size > 1) parts[1] else parts.firstOrNull()?.ifEmpty { null } ?: "unknown"
    }.getOrDefault("unknown")

    /** Every Proton tree with a Wine inside. Launch wrappers pointing at another Proton are left out. */
    fun protons(context: Context): List<Proton> {
        val found = LinkedHashMap<String, Proton>()
        val bases = listOf(File(steam(context), "compatibilitytools.d") to false) +
            libraryRoots(context).map { File(it, "steamapps/common") to true }
        // Not canonical: /data/user/0 is a link to /data/data on many devices, and the guest path is
        // what is left after removing the rootfs prefix exactly as the paths were built.
        val rootPath = root(context).absolutePath.trimEnd('/')
        for ((base, valve) in bases) {
            val entries = base.listFiles()?.sortedBy { it.name } ?: continue
            for (dir in entries) {
                if (!File(dir, WINE).isDirectory || !File(dir, "proton").isFile) continue
                val real = runCatching { dir.canonicalPath }.getOrDefault(dir.path)
                if (found.containsKey(real)) continue
                val guest = if (dir.absolutePath.startsWith(rootPath)) dir.absolutePath.removePrefix(rootPath) else dir.absolutePath
                found[real] = Proton(safeName(dir.name), displayName(dir), dir, guest, protonVersion(dir), valve)
            }
        }
        return found.values.toList()
    }

    private fun proton(context: Context, id: String) = protons(context).firstOrNull { it.id == id }
        ?: throw IllegalStateException("No Proton with id $id")

    /**
     * Is anything running on this Proton right now - a game, or the client's own start-up runs,
     * whose Wine processes can outlive them? Swaps wait while it is. Read from the command lines.
     */
    fun inUse(proton: Proton): Boolean = anyProcess(proton) { needle, text -> text.contains(needle + "proton ") || text.contains(needle + "files/bin") }

    /**
     * Is a game running on this Proton? Only a launch runs proton with the verb waitforexitandrun
     * (Proton's own script stays up until the game exits); the client's start-up runs do not.
     */
    fun gameRunning(proton: Proton): Boolean = anyProcess(proton) { needle, text -> text.contains(needle + "proton waitforexitandrun") }

    private fun anyProcess(proton: Proton, match: (String, String) -> Boolean): Boolean {
        val needle = proton.guestPath.trimEnd('/') + "/"
        val procs = File("/proc").listFiles() ?: return false
        for (p in procs) {
            if (!p.name.all(Char::isDigit)) continue
            val cmd = runCatching { File(p, "cmdline").readBytes() }.getOrNull() ?: continue
            if (cmd.isEmpty()) continue
            if (match(needle, String(cmd).replace('\u0000', ' '))) return true
        }
        return false
    }

    // ------------------------------------------------------------------ component files

    private fun readVersionFile(f: File): String = runCatching {
        val text = f.readText().trim()
        Regex("\\(([^)]+)\\)").find(text)?.groupValues?.get(1) ?: text.split(Regex("\\s+")).last()
    }.getOrDefault("")

    private fun fexVersion(dir: File): String {
        val dll = File(dir, FEX_FILES[0])
        if (!dll.isFile) return ""
        val text = runCatching { String(dll.readBytes(), Charsets.ISO_8859_1) }.getOrDefault("")
        return Regex("FEX-\\d{4}(?:\\.\\d+)?(?:-\\d+-g[0-9a-f]{6,})?").findAll(text).map { it.value }.maxByOrNull { it.length }
            ?: "FEX (build without a version name)"
    }

    private fun compFiles(dir: File, comp: String): List<String> {
        if (comp == "fex") return FEX_FILES.filter { File(dir, it).isFile }
        val base = File(dir, COMP_DIR.getValue(comp))
        return base.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.sorted().toList()
    }

    private fun owned(comp: String, rel: String) = comp == "fex" || PE_ARCHES.any { "/$it/" in rel }

    private fun fingerprint(dir: File, comp: String): Map<String, String> =
        compFiles(dir, comp).filter { owned(comp, it) }.associateWith { Hashes.sha256(File(dir, it)) }

    // ------------------------------------------------------------------ packages

    private fun normalize(name: String) = name.removePrefix("./").trimStart('/')

    private inline fun <T> readWcp(f: File, block: (TarArchiveInputStream) -> T): T =
        TarArchiveInputStream(ZstdInputStream(BufferedInputStream(FileInputStream(f), 1 shl 16))).use(block)

    private fun readProfile(f: File): JSONObject = readWcp(f) { tar ->
        while (true) {
            val e = tar.nextTarEntry ?: break
            if (!e.isDirectory && normalize(e.name) == "profile.json") return@readWcp JSONObject(tar.readBytes().decodeToString())
        }
        throw IllegalArgumentException("no profile.json in ${f.name}")
    }

    private fun packageInfo(f: File): Package {
        val prof = readProfile(f)
        val comp = TYPES[prof.optString("type")] ?: throw IllegalArgumentException("${f.name} is not a Linux component package (type ${prof.optString("type")})")
        return Package(f.name, comp, prof.optString("versionName", f.nameWithoutExtension), prof.optString("description"), f.length())
    }

    fun packages(context: Context): List<Package> = packagesDir(context).listFiles()
        ?.filter { it.isFile && it.name.endsWith(".wcp") }?.sortedBy { it.name }
        ?.mapNotNull { f -> runCatching { packageInfo(f) }.onFailure { Log.w(TAG, "skipping ${f.name}", it) }.getOrNull() }
        ?: emptyList()

    /** Unpacks a package's files/ into a Proton; for DXVK/VKD3D the component's own DLLs are replaced as a set. */
    private fun installFiles(context: Context, dir: File, comp: String, wcp: File): Map<String, String> {
        val staged = File(dataDir(context), "staging-${System.nanoTime()}")
        try {
            readWcp(wcp) { tar ->
                while (true) {
                    val e = tar.nextTarEntry ?: break
                    val rel = normalize(e.name)
                    if (e.isDirectory || !rel.startsWith("files/")) continue
                    require(rel.split('/').none { it == ".." }) { "unsafe path $rel" }
                    val out = File(staged, rel)
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { tar.copyTo(it) }
                }
            }
            require(File(staged, "files").isDirectory) { "${wcp.name} has no files/" }
            COMP_DIR[comp]?.let { compDir ->
                for (arch in PE_ARCHES) {
                    val target = File(dir, "$compDir/$arch")
                    if (!File(staged, "$compDir/$arch").isDirectory || !target.isDirectory) continue
                    target.listFiles()?.filter { it.name.lowercase() in COMP_DLLS.getValue(comp) }?.forEach { it.delete() }
                }
            }
            val hashes = LinkedHashMap<String, String>()
            File(staged, "files").walkTopDown().filter { it.isFile }.forEach { src ->
                val rel = src.relativeTo(staged).path
                val dst = File(dir, rel)
                dst.parentFile?.mkdirs()
                val tmp = File(dst.parentFile, dst.name + ".dcm-new")
                src.copyTo(tmp, overwrite = true)
                if (rel.endsWith(".so")) tmp.setExecutable(true, false)
                tmp.setReadable(true, false)
                if (!tmp.renameTo(dst)) { dst.delete(); check(tmp.renameTo(dst)) { "could not place $rel" } }
                hashes[rel] = Hashes.sha256(dst)
            }
            return hashes
        } finally {
            staged.deleteRecursively()
        }
    }

    private fun writeWcp(dest: File, profile: JSONObject, dir: File, rels: List<String>) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        TarArchiveOutputStream(ZstdOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16), 9)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            val bytes = profile.toString(2).toByteArray()
            tar.putArchiveEntry(TarArchiveEntry("./profile.json").apply { size = bytes.size.toLong() })
            tar.write(bytes); tar.closeArchiveEntry()
            for (rel in rels) {
                val f = File(dir, rel)
                tar.putArchiveEntry(TarArchiveEntry(f, "./$rel"))
                FileInputStream(f).use { it.copyTo(tar) }
                tar.closeArchiveEntry()
            }
        }
        tmp.renameTo(dest)
    }

    // ------------------------------------------------------------------ originals

    private fun originalFile(context: Context, p: Proton, comp: String) =
        File(originalsDir(context), "${p.id}/${safeName(p.version)}/$comp.wcp").apply { parentFile?.mkdirs() }

    fun originals(context: Context, protonId: String): List<Original> {
        val base = File(originalsDir(context), protonId)
        val versions = base.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() } ?: return emptyList()
        return versions.flatMap { v ->
            COMPONENTS.mapNotNull { comp ->
                val f = File(v, "$comp.wcp")
                if (!f.isFile) null else Original(comp, v.name, runCatching { readProfile(f).optString("description") }.getOrDefault(""), f.length())
            }
        }
    }

    /**
     * Saves a Proton's shipped files for this component before they are first replaced in this
     * Proton build. A build already swapped into is not saved again: its files are ours.
     */
    private fun ensureOriginal(context: Context, p: Proton, comp: String, state: JSONObject) {
        val dest = originalFile(context, p, comp)
        if (dest.isFile) return
        val active = state.sub("active").optJSONObject(p.id)?.optJSONObject(comp)
        if (active != null && active.optString("protonVersion") == p.version) return
        val rels = compFiles(p.dir, comp)
        if (rels.isEmpty()) return
        val current = if (comp == "fex") fexVersion(p.dir) else readVersionFile(File(p.dir, "${COMP_DIR.getValue(comp)}/version"))
        val profile = JSONObject()
            .put("type", TYPE_OF.getValue(comp))
            .put("versionName", "original-${p.version}")
            .put("versionCode", 0)
            .put("description", "Original · ${p.name} · ${p.version} · ${LABEL.getValue(comp)} $current".trim())
            .put("files", JSONArray(rels.map { JSONObject().put("source", it).put("target", "\${proton}/$it") }))
        writeWcp(dest, profile, p.dir, rels)
        Log.i(TAG, "saved original $comp of ${p.name} ${p.version}")
    }

    /** Saves the originals of every Proton build not seen yet (first open, and after a Steam update). */
    fun snapshotAll(context: Context) = synchronized(lock) {
        migratePlugin(context)
        val state = loadState(context)
        for (p in protons(context)) for (comp in COMPONENTS) {
            runCatching { ensureOriginal(context, p, comp, state) }.onFailure { Log.w(TAG, "original $comp of ${p.name}", it) }
        }
        runCatching { syncLaunchState(context, state) }.onFailure { Log.w(TAG, "launch state", it) }
    }

    // ------------------------------------------------------------------ view

    fun snapshot(context: Context): Snapshot = synchronized(lock) {
        migratePlugin(context)
        val state = loadState(context)
        val reapplied = HashMap<String, Long>()
        runCatching {
            File(launchDir(context), "reapplied.log").forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size >= 2) parts[0].toLongOrNull()?.let { reapplied[parts[1]] = maxOf(reapplied[parts[1]] ?: 0, it) }
            }
        }
        val views = protons(context).map { p ->
            val comps = COMPONENTS.associateWith { comp ->
                val detected = if (comp == "fex") fexVersion(p.dir) else readVersionFile(File(p.dir, "${COMP_DIR.getValue(comp)}/version"))
                val detail = if (comp == "fex") (if (File(p.dir, FEX_FILES[2]).isFile) "with Linux helpers" else "DLLs only") else ""
                val active = state.sub("active").optJSONObject(p.id)?.optJSONObject(comp)
                val inUseLabel = when {
                    active == null -> "Original"
                    active.optString("protonVersion") != p.version -> "Original (Proton updated since the last swap)"
                    else -> {
                        val want = active.optJSONObject("files") ?: JSONObject()
                        val fp = fingerprint(p.dir, comp)
                        if (want.keys().asSequence().all { fp[it] == want.optString(it) }) active.optString("label", active.optString("file"))
                        else "Changed outside Components"
                    }
                }
                val q = state.sub("queued").optJSONObject(p.id)?.optJSONObject(comp)
                Component(
                    detected.ifEmpty { "not present" }, detail, inUseLabel,
                    active?.optString("file")?.takeIf { active.optString("protonVersion") == p.version },
                    q?.optString("label"),
                )
            }
            ProtonView(p, comps, originals(context, p.id), gameRunning(p), reapplied[p.dir.name] ?: 0)
        }
        Snapshot(views, packages(context))
    }

    // ------------------------------------------------------------------ launch-time enforcement

    /**
     * Writes what each Proton should be using for the launch wrappers: desired.tsv, one line per
     * file (tool dir, Proton build, component, file, sha256, source copy), and an unpacked copy of
     * every package or older-build original in use to copy from. Rebuilt whole after every change, so
     * a restored or deleted choice simply stops being enforced. The Proton's own current-build
     * originals are not listed: the wrappers also give Steam's probe runs their own prefix, which is
     * what overwrote files in the first place.
     */
    private fun syncLaunchState(context: Context, state: JSONObject) {
        val dir = launchDir(context)
        val store = File(dir, "store").apply { mkdirs() }
        val all = protons(context)
        val lines = StringBuilder()
        val keep = HashSet<String>()
        val active = state.sub("active")
        for (pid in active.keys()) {
            val p = all.firstOrNull { it.id == pid } ?: continue
            val comps = active.optJSONObject(pid) ?: continue
            for (comp in comps.keys()) {
                val a = comps.optJSONObject(comp) ?: continue
                if (a.optString("protonVersion") != p.version) continue
                val file = a.optString("file")
                val (key, wcp) = if (file.startsWith("original ")) {
                    val v = file.removePrefix("original ")
                    "original-$pid-${safeName(v)}-$comp" to File(originalsDir(context), "$pid/${safeName(v)}/$comp.wcp")
                } else safeName(file.removeSuffix(".wcp")) to File(packagesDir(context), file)
                if (!wcp.isFile) continue
                val unpacked = File(store, key)
                if (!File(unpacked, ".complete").isFile) {
                    unpacked.deleteRecursively()
                    readWcp(wcp) { tar ->
                        while (true) {
                            val e = tar.nextTarEntry ?: break
                            val rel = normalize(e.name)
                            if (e.isDirectory || !rel.startsWith("files/") || rel.split('/').any { it == ".." }) continue
                            val out = File(unpacked, rel)
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { tar.copyTo(it) }
                        }
                    }
                    File(unpacked, ".complete").writeText("1\n")
                }
                keep += key
                val files = a.optJSONObject("files") ?: continue
                for (rel in files.keys()) {
                    val src = File(unpacked, rel)
                    if (!src.isFile) continue
                    val guestSrc = "/" + src.absolutePath.removePrefix(root(context).absolutePath.trimEnd('/')).trimStart('/')
                    lines.append(p.dir.name).append('\t').append(p.version).append('\t').append(comp).append('\t')
                        .append(rel).append('\t').append(files.optString(rel)).append('\t').append(guestSrc).append('\n')
                }
            }
        }
        store.listFiles()?.filter { it.isDirectory && it.name !in keep }?.forEach { it.deleteRecursively() }
        val list = File(dir, "desired.tsv")
        val tmp = File(dir, "desired.tsv.tmp")
        tmp.writeText(lines.toString())
        tmp.renameTo(list)
    }

    // ------------------------------------------------------------------ actions

    /** Applies a package now, or queues it when a game is running on that Proton. Returns what happened. */
    fun swap(context: Context, protonId: String, file: String): String = synchronized(lock) {
        val p = proton(context, protonId)
        val wcp = File(packagesDir(context), safeName(file))
        val info = packageInfo(wcp)
        val state = loadState(context)
        if (inUse(p)) {
            state.sub("queued").sub(p.id).put(info.comp, JSONObject().put("kind", "package").put("file", wcp.name).put("label", info.version))
            saveState(context, state)
            return "${LABEL[info.comp]} ${info.version} goes into ${p.name} when the game running on it closes."
        }
        applyPackage(context, p, info, wcp, state)
        state.sub("queued").optJSONObject(p.id)?.remove(info.comp)
        saveState(context, state)
        "${LABEL[info.comp]} ${info.version} is now in ${p.name}. It applies the next time a game starts."
    }

    private fun applyPackage(context: Context, p: Proton, info: Package, wcp: File, state: JSONObject) {
        ensureOriginal(context, p, info.comp, state)
        val hashes = installFiles(context, p.dir, info.comp, wcp)
        state.sub("active").sub(p.id).put(info.comp, JSONObject()
            .put("file", wcp.name).put("label", info.version).put("protonVersion", p.version)
            .put("files", JSONObject(hashes.filterKeys { owned(info.comp, it) } as Map<*, *>))
            .put("at", System.currentTimeMillis() / 1000))
        Log.i(TAG, "swapped ${info.comp} ${info.version} into ${p.name} ${p.version}")
    }

    fun restore(context: Context, protonId: String, comp: String, protonVersion: String): String = synchronized(lock) {
        val p = proton(context, protonId)
        val wcp = File(originalsDir(context), "$protonId/${safeName(protonVersion)}/$comp.wcp")
        check(wcp.isFile) { "That original bundle is not stored" }
        val state = loadState(context)
        if (inUse(p)) {
            state.sub("queued").sub(p.id).put(comp, JSONObject().put("kind", "original").put("protonVersion", protonVersion).put("label", "Original $protonVersion"))
            saveState(context, state)
            return "${LABEL[comp]} of ${p.name} goes back to its original when the game running on it closes."
        }
        applyOriginal(context, p, comp, protonVersion, wcp, state)
        state.sub("queued").optJSONObject(p.id)?.remove(comp)
        saveState(context, state)
        "${LABEL[comp]} of ${p.name} restored from its $protonVersion original."
    }

    private fun applyOriginal(context: Context, p: Proton, comp: String, protonVersion: String, wcp: File, state: JSONObject) {
        val current = protonVersion == safeName(p.version)
        if (!current) ensureOriginal(context, p, comp, state)
        installFiles(context, p.dir, comp, wcp)
        val active = state.sub("active").sub(p.id)
        if (current) active.remove(comp)
        else active.put(comp, JSONObject().put("file", "original $protonVersion").put("label", "Original from $protonVersion")
            .put("protonVersion", p.version).put("files", JSONObject(fingerprint(p.dir, comp) as Map<*, *>)).put("at", System.currentTimeMillis() / 1000))
    }

    /** Runs the swaps that waited for a game to close. Cheap when nothing is queued. */
    fun applyQueued(context: Context): List<String> = synchronized(lock) {
        val state = loadState(context)
        val queued = state.sub("queued")
        if (queued.length() == 0) return emptyList()
        val done = mutableListOf<String>()
        val all = protons(context)
        for (pid in queued.keys().asSequence().toList()) {
            val p = all.firstOrNull { it.id == pid } ?: run { queued.remove(pid); null } ?: continue
            if (inUse(p)) continue
            val comps = queued.optJSONObject(pid) ?: continue
            for (comp in comps.keys().asSequence().toList()) {
                val q = comps.optJSONObject(comp) ?: continue
                runCatching {
                    if (q.optString("kind") == "original") {
                        val v = q.optString("protonVersion")
                        applyOriginal(context, p, comp, v, File(originalsDir(context), "$pid/${safeName(v)}/$comp.wcp"), state)
                    } else {
                        val wcp = File(packagesDir(context), q.optString("file"))
                        applyPackage(context, p, packageInfo(wcp), wcp, state)
                    }
                    done += "${LABEL[comp]} ${q.optString("label")} → ${p.name}"
                }.onFailure { Log.w(TAG, "queued $comp for ${p.name}", it) }
                comps.remove(comp)
            }
            if (comps.length() == 0) queued.remove(pid)
        }
        saveState(context, state)
        done
    }

    fun cancelQueued(context: Context, protonId: String, comp: String) = synchronized(lock) {
        val state = loadState(context)
        state.sub("queued").optJSONObject(protonId)?.remove(comp)
        saveState(context, state)
    }

    fun deletePackage(context: Context, file: String): String = synchronized(lock) {
        val name = safeName(file)
        val state = loadState(context)
        val active = state.sub("active")
        for (pid in active.keys()) {
            val comps = active.optJSONObject(pid) ?: continue
            for (comp in comps.keys()) check(comps.optJSONObject(comp)?.optString("file") != name) { "$name is in use in $pid. Swap that Proton to something else first." }
        }
        val f = File(packagesDir(context), name)
        check(f.isFile) { "$name is not stored" }
        f.delete()
        "Deleted $name."
    }

    fun deleteOriginal(context: Context, protonId: String, comp: String, protonVersion: String): String = synchronized(lock) {
        val p = proton(context, protonId)
        check(safeName(p.version) != protonVersion) { "The installed build's original is kept: it is the way back." }
        val f = File(originalsDir(context), "$protonId/${safeName(protonVersion)}/$comp.wcp")
        check(f.isFile) { "Not stored" }
        f.delete()
        f.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
        "Deleted the $protonVersion original of ${LABEL[comp]}."
    }

    /** Imports a -linux .wcp from anywhere the app can read (the file picker hands over a copy). */
    fun importPackage(context: Context, source: File, name: String): Package = synchronized(lock) {
        val info = packageInfo(source)
        val dest = File(packagesDir(context), safeName(name).let { if (it.endsWith(".wcp")) it else "$it.wcp" })
        source.copyTo(File(dest.parentFile, dest.name + ".part"), overwrite = true).renameTo(dest)
        info.copy(file = dest.name)
    }

    // ------------------------------------------------------------------ catalog

    /** The Nightlies listing: cached on disk, fetched again only when [force] (the refresh button). */
    fun catalog(context: Context, force: Boolean): Catalog {
        val cache = catalogFile(context)
        if (!force && cache.isFile) runCatching { return parseCatalog(JSONObject(cache.readText())) }
        if (!force) return Catalog(emptyList(), 0)
        val items = JSONArray()
        for ((tag, comp) in RELEASES) {
            val body = Downloader.downloadString("https://api.github.com/repos/$NIGHTLIES/releases/tags/$tag") ?: continue
            val assets = runCatching { JSONObject(body).optJSONArray("assets") }.getOrNull() ?: continue
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (!name.endsWith(".wcp")) continue
                // Only packages GitHub has a sha256 for are offered: the download is checked against it.
                if (!Hashes.isGithubSha256(a.optString("digest"))) continue
                items.put(JSONObject().put("file", name).put("comp", comp).put("release", tag)
                    .put("url", a.optString("browser_download_url")).put("size", a.optLong("size")).put("digest", a.optString("digest")))
            }
        }
        val out = JSONObject().put("items", items).put("fetchedAt", System.currentTimeMillis() / 1000)
        val tmp = File(cache.parentFile, cache.name + ".tmp")
        tmp.writeText(out.toString()); tmp.renameTo(cache)
        return parseCatalog(out)
    }

    private fun parseCatalog(o: JSONObject): Catalog {
        val arr = o.optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            CatalogItem(it.optString("file"), it.optString("comp"), it.optString("release"), it.optString("url"), it.optLong("size"), it.optString("digest"))
        }.sortedWith(compareBy({ it.comp }, { it.release }, { it.file }))
        return Catalog(items, o.optLong("fetchedAt"))
    }

    /** Downloads a catalog item into storage, verifying the release's sha256 and the package type. */
    fun download(context: Context, item: CatalogItem, progress: (Int) -> Unit): Package {
        require(item.url.startsWith("https://github.com/$NIGHTLIES/releases/download/")) { "Downloads come only from the Nightlies releases" }
        require(Hashes.isGithubSha256(item.digest)) { "This package list predates checksums - refresh it and try again" }
        val dest = File(packagesDir(context), safeName(item.file))
        val part = File(dest.parentFile, dest.name + ".part")
        part.delete()
        check(Downloader.downloadFile(item.url, part, false) { f -> progress(if (f < 0) -1 else (f * 100).toInt().coerceIn(0, 100)) }) { "Download failed" }
        if (!Hashes.sha256(part).equals(item.digest.substringAfter(':'), true)) { part.delete(); error("Checksum mismatch") }
        val info = runCatching { packageInfo(part) }.getOrElse { part.delete(); throw it }
        synchronized(lock) { part.renameTo(dest) }
        return info.copy(file = dest.name)
    }
}
