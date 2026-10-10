package com.droiddeck.launcher.session

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.droiddeck.launcher.core.ArchivePaths
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.runtime.GuestCommand
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Windows components (OpenAL, d3dx9, XAudio, VC++ DLLs...) for a game's Proton prefix, picked per
 * game, from Bannerlator's catalog (components.json on winlator-contents, Bottles' manifest format).
 *
 * Installing one runs its steps once, into the runtime: the archives it names are downloaded and
 * its DLLs copied to /opt/droiddeck/wincomponents/<name>/{system32,syswow64}, with component.json
 * holding its DLL overrides and the components it bundles. The picks go to the runtime as
 * ~/.config/droiddeck/wincomponents.json, and droiddeck-wincomponents copies the picked components
 * into the game's prefix at every launch (see that script for why every launch).
 *
 * A component whose installer is a Windows Installer package (.msi: XNA, PhysX, Games for Windows
 * Live, MSXML...) installs here too, without running it: droiddeck-msi-install reads the package's
 * tables in the runtime and lays out its files (Program Files, the .NET GAC, winsxs) and registry
 * values the way Wine's msiexec would, under the same store folder. One that runs any other
 * installer (.exe), unpacks a cabinet or edits the prefix's registry by itself is listed with the
 * reason it cannot be installed yet.
 */
object WinComponents {
    private const val TAG = "WinComponents"
    const val CATALOG_URL = "https://raw.githubusercontent.com/Droid-Deck/DroidDeck-Components/main/components.json"
    private const val SELECTION = "wincomponents.json"
    private const val GUEST_SELECTION = "root/.config/droiddeck/wincomponents.json"
    private const val STORE = "opt/droiddeck/wincomponents"
    private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    // register_dll is left out on purpose: the DLLs it names are ones Wine ships and has registered
    // at the same path, so the native file at that path is the one the registration loads.
    private val FILE_STEPS = setOf("download_archive", "archive_extract", "copy_dll", "copy_file", "override_dll", "register_dll")
    private val INSTALLER_STEPS = setOf("install_exe", "install_msi")
    /**
     * Steps done here without any installer running: a Windows version or registry value for the
     * prefix (written by droiddeck-wincomponents with the rest), files picked out of a cabinet, and
     * "uninstall Wine Mono", which the mscoree override beside it already does for the game.
     */
    private val OFFLINE_STEPS = setOf("set_windows", "set_register_key", "get_from_cab", "uninstall")
    /**
     * Installers that are their own setup program (NSIS, InnoSetup, IExpress scripts): nothing
     * inside them is a package to lay out, so they wait for a way to run them.
     */
    private val RUNS_OWN_SETUP = setOf(
        "K-Lite", "ffdshow", "lavfilters702", "lavfilters741", "quicktime72", "dirac", "webview2", "aairruntime",
        "gfw", "ie8_kb2936068", "art2k7min", "vcredist6sp6", "VulkanRT", "jet40", "mdac28", "oalinst",
        "dotnet20", "dotnet20sp1", "dotnet35", "dotnet35sp1",
    )
    private const val MSI_INSTALL = "/usr/local/bin/droiddeck-msi-install"
    /** Where a downloaded package or installer waits for the installer, inside the runtime so it can read it. */
    private const val MSI_CACHE = "$STORE/.packages"
    /**
     * Catalog entries every Proton here already ships, which an install would only shadow: Wine
     * Gecko and Mono, the Chinese/Japanese/Korean fonts (Malgun, MS Gothic, YaHei, SimSun are in
     * share/fonts of Proton Experimental, GE-Proton and proton-cachyos alike), Wine's own
     * PowerShell wrapper and the Vulkan loader (vulkan-1.dll, 32- and 64-bit, in all three).
     */
    private val PROTON_PROVIDES = setOf("gecko", "mono", "cjkfonts", "powershell", "VulkanRT")
    /** The keys the catalog nests under "environment" on a few .NET entries. */
    private val NESTED_KEYS = setOf("url", "file_name", "file_checksum", "file_size")

    fun validId(id: String): Boolean = ID.matches(id) && ".." !in id

    class Step(val action: String, val json: JSONObject) {
        fun str(key: String): String = json.optString(key, "").ifEmpty {
            if (key in NESTED_KEYS) json.optJSONObject("environment")?.optString(key, "").orEmpty() else ""
        }
    }

    class Component(
        val name: String, val description: String, val provider: String, val status: String,
        val dependencies: List<String>, val steps: List<Step>,
        /**
         * A recording of what the component's own installer leaves in a prefix (winlator-contents'
         * component-snapshots release, made by tools/snapshot/record.py): the files it places and
         * the registry values it writes. With one, the installer never runs: the files come from
         * the archive beside the recording or out of the vendor's installer, the registry from the
         * recording. Empty for a component without one.
         */
        val snapshot: String = "",
    )

    enum class Support { READY, NEEDS_INSTALLER, UNSUPPORTED }

    /** The two halves of an install the page shows one after the other, each 0..100. */
    enum class Phase { DOWNLOAD, INSTALL }

    /** Progress: the component being worked on, what it is doing, the phase, 0..100 or -1 when unknown. */
    class Progress(val component: String, val stage: String, val phase: Phase, val percent: Int)

    /** The percent a droiddeck-msi-install progress line carries ("progress 37% placing files"), or -1. */
    private val PERCENT_LINE = Regex("""^(\d{1,3})% (.*)$""")
    private fun engineLine(text: String): Pair<String, Int> =
        PERCENT_LINE.find(text)?.let { it.groupValues[2] to it.groupValues[1].toInt().coerceIn(0, 100) } ?: (text to -1)

    /**
     * A step that installs a Windows Installer package, or an installer .exe that is a wrapper
     * around packages (a WiX bundle, a self-extracting cabinet or 7-Zip archive), which
     * droiddeck-msi-install lays out here without running it.
     */
    private fun isPackage(step: Step): Boolean = step.action in INSTALLER_STEPS && step.str("url").startsWith("https://") &&
        (step.action == "install_msi" || listOf(step.str("file_name"), step.str("url").substringBefore('?'))
            .any { it.endsWith(".msi", ignoreCase = true) || it.endsWith(".exe", ignoreCase = true) })

    /**
     * Catalog entries nothing here can use: the IE8 security update patches a browser no prefix
     * has; the OpenAL setup program is a twin of oalinst_dll, which copies the same DLLs; and
     * WebView2 can only be installed by running Microsoft's installer in the prefix (its files
     * sit in a container nothing opens and may not be hosted), which no runtime here does.
     */
    private val POINTLESS = setOf("ie8_kb2936068", "oalinst", "webview2")

    private fun protonProvides(name: String): Boolean = name in PROTON_PROVIDES || name in POINTLESS || name.startsWith("mono-")

    fun hasSnapshot(c: Component): Boolean = c.snapshot.startsWith("https://github.com/") && c.snapshot.endsWith(".snapshot.json")

    /** The steps a recording makes redundant: everything that places files or edits the registry. */
    private val SNAPSHOT_COVERS = INSTALLER_STEPS + setOf("download_archive", "archive_extract", "copy_dll", "copy_file") + OFFLINE_STEPS

    fun fetch(): List<Component>? {
        val body = Downloader.downloadString(CATALOG_URL) ?: return null
        return try {
            val arr = JSONObject(body).getJSONArray("components")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                if (!validId(name)) return@mapNotNull null
                val steps = o.optJSONArray("steps") ?: JSONArray()
                val deps = o.optJSONArray("dependencies") ?: JSONArray()
                Component(
                    name, o.optString("description"), o.optString("provider"), o.optString("status"),
                    (0 until deps.length()).map { deps.getString(it) }.filter { validId(it) },
                    (0 until steps.length()).map { steps.getJSONObject(it).let { s -> Step(s.optString("action"), s) } },
                    o.optString("snapshot"),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "catalog: $e"); null
        }
    }

    /** Whether [c] can be installed here, its bundled components included. */
    fun support(c: Component, all: Map<String, Component>, depth: Int = 0): Support {
        if (c.status != "ready" || depth > 8 || protonProvides(c.name)) return Support.UNSUPPORTED
        val installers = c.steps.filter { it.action in INSTALLER_STEPS }
        // A recording of the installer's result stands in for running it, whatever the steps say.
        if (hasSnapshot(c)) {
            return c.dependencies.map { all[it]?.let { d -> support(d, all, depth + 1) } ?: Support.UNSUPPORTED }
                .maxOrNull() ?: Support.READY
        }
        fun offline(step: Step) = step.action == "delete_dlls" || step.action in OFFLINE_STEPS || step.action in FILE_STEPS && fileStepOk(step)
        val own = when {
            installers.isNotEmpty() && c.name !in RUNS_OWN_SETUP && installers.all { isPackage(it) } &&
                c.steps.all { it in installers || offline(it) } -> Support.READY
            installers.isNotEmpty() -> Support.NEEDS_INSTALLER
            c.steps.all { offline(it) } -> Support.READY
            else -> Support.UNSUPPORTED
        }
        if (own != Support.READY) return own
        return c.dependencies.map { all[it]?.let { d -> support(d, all, depth + 1) } ?: Support.UNSUPPORTED }
            .maxOrNull() ?: Support.READY
    }

    private fun fileStepOk(step: Step): Boolean = when (step.action) {
        // An .exe or .cab archive (DirectX's redistributable) is opened by the runtime's tools.
        "download_archive", "archive_extract" -> step.str("url").let { url ->
            !url.startsWith("http") || url.startsWith("https://github.com/") &&
                url.substringBefore('?').lowercase().let { it.endsWith(".tar.xz") || it.endsWith(".zip") || it.endsWith(".exe") || it.endsWith(".cab") }
        }
        "copy_dll", "copy_file" -> ".." !in step.str("dest")
        else -> true
    }

    /** The installed version of a component, or null. */
    fun installed(context: Context, id: String): String? = runCatching {
        JSONObject(File(LinuxRuntime.rootDir(context), "$STORE/$id/component.json").readText()).optString("version", "?")
    }.getOrNull()

    /** Every component in the runtime, by id. */
    fun installedIds(context: Context): List<String> =
        File(LinuxRuntime.rootDir(context), STORE).listFiles { f -> f.isDirectory && validId(f.name) }
            .orEmpty().map { it.name }.filter { installed(context, it) != null }.sorted()

    /**
     * Installs [c] and the components it bundles that are not installed yet. [onProgress] gets a
     * line for the step and 0..100 (or -1). Returns null on success, else a message.
     */
    fun install(context: Context, c: Component, all: Map<String, Component>, onProgress: (Progress) -> Unit): String? {
        if (support(c, all) != Support.READY) return "${c.name} cannot be installed here yet"
        val order = ArrayList<Component>()
        fun visit(x: Component, depth: Int) {
            if (depth > 8 || order.any { it.name == x.name }) return
            x.dependencies.mapNotNull { all[it] }.forEach { visit(it, depth + 1) }
            if (x.name == c.name || installed(context, x.name) == null) order.add(x)
        }
        visit(c, 0)
        // One download per archive however many components copy out of it (DirectMusic's eleven
        // parts all come from dxnt, 100 MB).
        val work = File(context.cacheDir, "wincomponents").apply { FileUtils.delete(this); mkdirs() }
        val archives = HashMap<String, File>()
        return try {
            for (x in order) {
                onProgress(Progress(x.name, x.name, Phase.DOWNLOAD, -1))
                installOne(context, x, work, archives, onProgress)?.let { return "${x.name}: $it" }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "install ${c.name}", e)
            e.message ?: "Install failed"
        } finally {
            FileUtils.delete(work)
        }
    }

    private fun installOne(context: Context, c: Component, work: File, archives: HashMap<String, File>, onProgress: (Progress) -> Unit): String? {
        val root = LinuxRuntime.rootDir(context)
        if (!root.isDirectory) return "The Linux runtime is not installed"
        val staging = File(root, "$STORE/.${c.name}.new").apply { FileUtils.delete(this); mkdirs() }
        val overrides = ArrayList<String>()
        var source: File? = null
        var msi: JSONObject? = null
        // The registry values the steps themselves set (a Windows version, a key), beside the packages'.
        val registry = ArrayList<JSONObject>()
        // Archives the runtime's tools opened (installer .exe, cabinets), by the name the catalog
        // calls them, and the folders they were opened into; cleaned up at the end.
        val runtimeArchives = HashMap<String, File>()
        val runtimeDirs = ArrayList<File>()
        val cabs = HashMap<String, File>()
        val temp = File(root, "$MSI_CACHE/.t-${c.name}").apply { FileUtils.delete(this) }
        try {
        if (hasSnapshot(c)) {
            val (result, problem) = installSnapshot(context, c, staging, work, onProgress)
            if (result == null) return problem
            msi = result
        }
        for (step in c.steps) when {
            hasSnapshot(c) && step.action in SNAPSHOT_COVERS -> Unit
            else -> when (step.action) {
            // A component may have several packages (PowerShell's 32- and 64-bit): each adds to the
            // same folder, the first one names the component.
            "install_msi", "install_exe" -> {
                val (result, problem) = installPackage(context, c, step, staging, onProgress)
                if (result == null) return problem
                msi = msi?.apply {
                    val notes = optJSONArray("notes") ?: JSONArray().also { put("notes", it) }
                    result.optJSONArray("notes")?.let { for (i in 0 until it.length()) notes.put(it.get(i)) }
                } ?: result
            }
            // A catalog step that makes room for the installer's own copy; ours is copied anyway.
            "delete_dlls" -> Unit
            "download_archive", "archive_extract" -> {
                val url = step.str("url")
                if (!url.startsWith("http")) continue
                source = archives[url] ?: run {
                    val name = url.substringBefore('?').substringAfterLast('/')
                    // A zip or tar.xz is opened here; an .exe or .cab by the runtime's tools, so it
                    // is downloaded where the runtime can read it and kept for get_from_cab steps.
                    val byRuntime = name.lowercase().let { it.endsWith(".exe") || it.endsWith(".cab") }
                    val file = if (byRuntime) File(root, "$MSI_CACHE/${archives.size}-$name").apply { parentFile?.mkdirs() }
                        else File(work, "${archives.size}-$name")
                    val ok = Downloader.downloadFile(url, file, false) { f -> onProgress(Progress(c.name, name, Phase.DOWNLOAD, if (f < 0) -1 else Math.round(f * 100f))) }
                    if (!ok) return "download failed: $name"
                    step.str("file_checksum").takeIf { it.length == 32 }?.let { md5 ->
                        if (!digest(file, "MD5").equals(md5, ignoreCase = true)) return "checksum mismatch: $name"
                    }
                    onProgress(Progress(c.name, "unpacking $name", Phase.INSTALL, -1))
                    val dir = if (byRuntime) {
                        runtimeArchives[step.str("file_name").ifEmpty { name }] = file
                        runtimeDirs += file
                        unpackInRuntime(context, c, file, "${archives.size}-x", onProgress)?.also { runtimeDirs += it }
                            ?: return "could not unpack $name"
                    } else {
                        File(work, "${archives.size}-x").apply { mkdirs() }.also { if (!extract(file, it)) return "could not unpack $name" }
                            .also { file.delete() }
                    }
                    dir.also { archives[url] = it }
                }
            }
            // Files picked out of a cabinet: one the catalog downloaded (DirectX's redistributable,
            // a self-extracting cabinet), or cabinets an earlier pick put under temp/.
            "get_from_cab" -> {
                val pattern = step.str("source")
                val containers = runtimeArchives[pattern]?.let { listOf(it) } ?: run {
                    val folder = File(temp, pattern.substringBeforeLast('/', "").replace('\\', '/'))
                    val rx = globRegex(pattern.substringAfterLast('/'))
                    folder.listFiles { f -> f.isFile && rx.matches(f.name) }?.sortedBy { it.name }.orEmpty()
                }
                if (containers.isEmpty()) return "nothing is called $pattern"
                val dest = step.str("dest").replace('\\', '/')
                for (container in containers) {
                    val unpacked = cabs.getOrPut(container.path) {
                        unpackInRuntime(context, c, container, "c${cabs.size}", onProgress)?.also { runtimeDirs += it }
                            ?: return "could not open ${container.name}"
                    }
                    if (dest.startsWith("temp/", ignoreCase = true)) {
                        copyMatching(unpacked, step.str("file_name"), File(temp, dest.substring(5).trimEnd('/')), null)
                    } else {
                        val (arch, base) = when (dest.substringBefore('/').lowercase()) {
                            "win64", "system32" -> "win64" to "system32"
                            "win32", "syswow64" -> "win32" to "syswow64"
                            else -> null to "system32"
                        }
                        val sub = dest.substringAfter('/', "").trimEnd('/')
                        copyMatching(unpacked, step.str("file_name"), File(staging, if (sub.isEmpty()) base else "$base/$sub"), arch)
                    }
                }
            }
            // The Windows version a game sees: HKCU\Software\Wine "Version", the last word wins.
            "set_windows" -> step.str("version").takeIf { it.isNotEmpty() }?.let { version ->
                registry.removeAll { it.optString("key") == "Software\\Wine" && it.optString("name") == "Version" }
                registry += JSONObject().put("hive", "HKCU").put("key", "Software\\Wine").put("name", "Version").put("type", "sz").put("data", version)
            }
            "set_register_key" -> {
                val full = step.str("key").replace("\\\\", "\\").trim('\\')
                val hive = when (full.substringBefore('\\').uppercase()) {
                    "HKLM", "HKEY_LOCAL_MACHINE" -> "HKLM"
                    "HKCU", "HKEY_CURRENT_USER" -> "HKCU"
                    else -> return "a registry key outside HKLM and HKCU: $full"
                }
                val key = full.substringAfter('\\', "")
                if (key.isEmpty()) return "a registry value without a key: $full"
                val data = step.str("data")
                val value = JSONObject().put("hive", hive).put("key", key).put("name", step.str("value"))
                when (step.str("type").uppercase()) {
                    "REG_DWORD" -> value.put("type", "dword").put("data", data.trim().let { it.removePrefix("0x").toLongOrNull(if (it.startsWith("0x")) 16 else 10) ?: 0L })
                    "REG_EXPAND_SZ" -> value.put("type", "expand_sz").put("data", data)
                    else -> value.put("type", "sz").put("data", data)
                }
                registry += value
            }
            // "Uninstall Wine Mono" before a real .NET Framework: the mscoree override the catalog
            // sets beside it is what takes Mono out of the game's way; nothing to remove here.
            "uninstall" -> Unit
            "copy_dll", "copy_file" -> {
                val from = source ?: return "a copy step before any archive"
                val dest = step.str("dest")
                val (arch, base) = when (dest.substringBefore('/').lowercase()) {
                    "win64", "system32" -> "win64" to "system32"
                    "win32", "syswow64" -> "win32" to "syswow64"
                    else -> null to "system32"
                }
                val sub = dest.substringAfter('/', "")
                copyMatching(from, step.str("file_name"), File(staging, if (sub.isEmpty()) base else "$base/$sub"), arch)
            }
            "override_dll" -> {
                fun add(dll: String, type: String) {
                    if (dll.isEmpty()) return
                    overrides += when (type.ifEmpty { "native,builtin" }) {
                        "native,builtin" -> dll
                        "native" -> "$dll=n"
                        "builtin" -> "$dll=b"
                        "builtin,native" -> "$dll=b,n"
                        else -> dll
                    }
                }
                add(step.str("dll"), step.str("type"))
                step.json.optJSONArray("bundle")?.let { b ->
                    for (i in 0 until b.length()) b.getJSONObject(i).let { add(it.optString("value"), it.optString("data")) }
                }
            }
            "register_dll" -> Unit
            else -> return "unsupported step ${step.action}"
            }
        }
        } finally {
            runtimeDirs.forEach { FileUtils.delete(it) }
            FileUtils.delete(temp)
        }
        if (registry.isNotEmpty()) {
            // The packages' values are already in registry.json; the steps' own go after them.
            val file = File(staging, "registry.json")
            val values = runCatching { JSONObject(file.readText()).optJSONArray("values") }.getOrNull() ?: JSONArray()
            registry.forEach { values.put(it) }
            FileUtils.writeString(file, JSONObject().put("version", 1).put("values", values).toString(1))
        }
        val version = msi?.optString("version")?.takeIf { it.isNotEmpty() }
            ?: Regex("""\b(\d+\.\d+(\.\d+)*)\b""").find(c.description)?.value ?: "catalog"
        val meta = JSONObject()
            .put("id", c.name).put("version", version)
            .put("overrides", JSONArray(overrides.distinct()))
            .put("requires", JSONArray(c.dependencies))
        msi?.let { meta.put("kind", it.optString("kind", "msi")).put("product", it.optString("product")).put("notes", it.optJSONArray("notes") ?: JSONArray()) }
        FileUtils.writeString(File(staging, "component.json"), meta.toString(1))
        val target = File(root, "$STORE/${c.name}")
        FileUtils.delete(target)
        if (!staging.renameTo(target)) return "could not place the files"
        return null
    }

    /**
     * Lays the component out from its recording: downloads the snapshot, then its files (the
     * archive beside it when the recording carries them, else out of the vendor's installer by
     * the paths the recording maps, small generated ones inline) into [staging]/drive_c, and the
     * recorded registry values into registry.json. Returns a result like the MSI engine's, or
     * null and why.
     */
    private fun installSnapshot(context: Context, c: Component, staging: File, work: File, onProgress: (Progress) -> Unit): Pair<JSONObject?, String> {
        val root = LinuxRuntime.rootDir(context)
        val name = c.snapshot.substringAfterLast('/')
        val json = File(work, "${c.name}.snapshot.json")
        if (!Downloader.downloadFile(c.snapshot, json, false) { f -> onProgress(Progress(c.name, name, Phase.DOWNLOAD, if (f < 0) -1 else Math.round(f * 100f))) }) {
            return null to "download failed: $name"
        }
        val snapshot = runCatching { JSONObject(json.readText()) }.getOrNull() ?: return null to "the recording could not be read"
        if (snapshot.optInt("schema") != 1) return null to "the recording is of a newer kind than this app reads"
        val files = snapshot.optJSONArray("files") ?: JSONArray()
        val notes = ArrayList<String>()
        snapshot.optJSONArray("notes")?.let { for (i in 0 until it.length()) notes += it.getString(i) }
        val drive = File(staging, "drive_c")
        var placed = 0
        val archive = snapshot.optJSONObject("files_archive")
        if (archive != null) {
            val archiveName = archive.optString("name")
            val url = c.snapshot.substringBeforeLast('/') + "/" + archiveName
            val file = File(work, "${c.name}.files.tar.xz")
            if (!Downloader.downloadFile(url, file, false) { f -> onProgress(Progress(c.name, archiveName, Phase.DOWNLOAD, if (f < 0) -1 else Math.round(f * 100f))) }) {
                return null to "download failed: $archiveName"
            }
            archive.optString("sha256").takeIf { it.length == 64 }?.let { sha ->
                if (!digest(file, "SHA-256").equals(sha, ignoreCase = true)) return null to "checksum mismatch: $archiveName"
            }
            onProgress(Progress(c.name, "unpacking $archiveName", Phase.INSTALL, -1))
            if (!extract(file, staging)) return null to "could not unpack $archiveName"
            file.delete()
            placed = drive.walkTopDown().count { it.isFile }
        } else {
            // The files come out of the installer the catalog names, by the paths the recording maps.
            val step = c.steps.firstOrNull { it.action in INSTALLER_STEPS && it.str("url").startsWith("https://") }
                ?: return null to "the recording needs the installer, which the catalog does not name"
            val url = step.str("url")
            val installerName = url.substringBefore('?').substringAfterLast('/')
            val cache = File(root, MSI_CACHE).apply { mkdirs() }
            val installer = File(cache, "${c.name}-snapshot-src.bin")
            var unpacked: File? = null
            try {
                if (!Downloader.downloadFile(url, installer, false) { f -> onProgress(Progress(c.name, installerName, Phase.DOWNLOAD, if (f < 0) -1 else Math.round(f * 100f))) }) {
                    return null to "download failed: $installerName"
                }
                unpacked = unpackInRuntime(context, c, installer, "snapshot", onProgress) ?: return null to "could not open $installerName"
                var missing = 0
                for (i in 0 until files.length()) {
                    val entry = files.getJSONObject(i)
                    val rel = entry.optString("path")
                    val target = ArchivePaths.inside(drive, rel) ?: continue
                    val source = entry.optString("source")
                    when {
                        source.isNotEmpty() -> ArchivePaths.inside(unpacked, source)?.takeIf { it.isFile }?.let { src ->
                            target.parentFile?.mkdirs(); src.copyTo(target, overwrite = true); placed++
                        } ?: run { missing++ }
                        entry.has("data") -> {
                            target.parentFile?.mkdirs()
                            target.writeBytes(android.util.Base64.decode(entry.getString("data"), android.util.Base64.DEFAULT)); placed++
                        }
                        else -> missing++
                    }
                    if (i % 20 == 0) onProgress(Progress(c.name, "placing files ($placed of ${files.length()})", Phase.INSTALL, (i + 1) * 100 / maxOf(1, files.length())))
                }
                if (missing > 0) notes += "$missing file(s) the installer generates could not be reproduced here"
            } finally {
                installer.delete()
                unpacked?.let { FileUtils.delete(it) }
            }
        }
        onProgress(Progress(c.name, "writing the registry values", Phase.INSTALL, 95))
        val values = snapshot.optJSONArray("registry") ?: JSONArray()
        FileUtils.writeString(File(staging, "registry.json"), JSONObject().put("version", 1).put("values", values).toString(1))
        if (placed == 0 && values.length() == 0) return null to "the recording holds nothing to install"
        val recorded = snapshot.optString("recorded").take(10)
        val installerVersion = snapshot.optJSONObject("installer")?.optString("name").orEmpty()
        val version = Regex("""\b(\d+\.\d+(\.\d+)*)\b""").find(c.description)?.value ?: recorded.ifEmpty { "recorded" }
        return JSONObject()
            .put("kind", "snapshot").put("product", c.description.ifEmpty { c.name })
            .put("version", version)
            .put("counts", JSONObject().put("files", placed).put("registry", values.length()))
            .put("notes", JSONArray(notes + listOfNotNull(installerVersion.takeIf { it.isNotEmpty() }?.let { "recorded from $it on ${snapshot.optString("wine")}" }))) to ""
    }

    /** The regex for a catalog file pattern: * matches anything, case does not matter. */
    private fun globRegex(pattern: String) =
        Regex("^" + pattern.split("*").joinToString(".*") { Regex.escape(it) } + "$", RegexOption.IGNORE_CASE)

    /**
     * Has the runtime's tools open [archive] (an installer .exe, a cabinet, a self-extracting
     * one) into a folder of the package cache called [tag]; that folder, or null and a log line.
     */
    private fun unpackInRuntime(context: Context, c: Component, archive: File, tag: String, onProgress: (Progress) -> Unit): File? {
        val root = LinuxRuntime.rootDir(context)
        val cache = File(root, MSI_CACHE)
        val dir = File(cache, ".x-$tag").apply { FileUtils.delete(this); mkdirs() }
        val inside = archive.relativeTo(root).path
        var problem: String? = null
        var ok = false
        val status = GuestCommand.run(context, listOf(MSI_INSTALL, "--unpack", "/$inside", "/$MSI_CACHE/${dir.name}"),
            logName = "wincomponents-${c.name}") { line ->
            when {
                line.startsWith("progress ") -> engineLine(line.removePrefix("progress ")).let { (text, _) -> onProgress(Progress(c.name, text, Phase.INSTALL, -1)) }
                line.startsWith("result ") -> ok = true
                line.startsWith("error ") -> problem = line.removePrefix("error ")
            }
        }
        if (status != 0 || !ok) {
            Log.w(TAG, "unpack ${archive.name}: ${problem ?: "status $status"}")
            FileUtils.delete(dir)
            return null
        }
        return dir
    }

    /**
     * Downloads the component's package (.msi) or installer (.exe) into the runtime and has
     * droiddeck-msi-install lay it out in [staging]. Returns the installer's result (product,
     * version, counts, notes), or null and why. Everything the installer prints also goes to
     * files/logs/tools.
     */
    private fun installPackage(context: Context, c: Component, step: Step, staging: File, onProgress: (Progress) -> Unit): Pair<JSONObject?, String> {
        val root = LinuxRuntime.rootDir(context)
        val url = step.str("url")
        val name = url.substringBefore('?').substringAfterLast('/')
        val cache = File(root, MSI_CACHE).apply { mkdirs() }
        val ext = if (listOf(step.str("file_name"), name).any { it.endsWith(".exe", ignoreCase = true) }) "exe" else "msi"
        val file = File(cache, "${c.name}-${c.steps.indexOf(step)}.$ext")
        try {
            if (!Downloader.downloadFile(url, file, false) { f -> onProgress(Progress(c.name, name, Phase.DOWNLOAD, if (f < 0) -1 else Math.round(f * 100f))) }) {
                return null to "download failed: $name"
            }
            step.str("file_checksum").takeIf { it.length == 32 }?.let { md5 ->
                if (!digest(file, "MD5").equals(md5, ignoreCase = true)) return null to "checksum mismatch: $name"
            }
            onProgress(Progress(c.name, "installing", Phase.INSTALL, 0))
            var result: JSONObject? = null
            var problem: String? = null
            val status = GuestCommand.run(context, listOf(MSI_INSTALL, "/$MSI_CACHE/${file.name}", "/$STORE/${staging.name}"),
                logName = "wincomponents-${c.name}") { line ->
                when {
                    line.startsWith("progress ") -> engineLine(line.removePrefix("progress ")).let { (text, pct) -> onProgress(Progress(c.name, text, Phase.INSTALL, pct)) }
                    line.startsWith("result ") -> result = runCatching { JSONObject(line.removePrefix("result ")) }.getOrNull()
                    line.startsWith("error ") -> problem = line.removePrefix("error ")
                }
            }
            val done = result
            if (status != 0 || done == null) return null to (problem ?: "the installer stopped (status $status)")
            return done to ""
        } catch (e: Exception) {
            Log.e(TAG, "package ${c.name}", e)
            return null to (e.message ?: "the installer failed")
        } finally {
            file.delete()
        }
    }

    private fun digest(file: File, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extract(archive: File, dest: File): Boolean = try {
        if (archive.name.endsWith(".zip", ignoreCase = true)) ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val out = ArchivePaths.inside(dest, entry.name)
                if (out != null && !entry.isDirectory) {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { FileUtils.copy(zip, it) }
                }
                entry = zip.nextEntry
            }
        } else TarArchiveInputStream(XZCompressorInputStream(BufferedInputStream(FileInputStream(archive), 1 shl 16))).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val out = ArchivePaths.inside(dest, entry.name)
                if (out != null && entry.isFile) {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { FileUtils.copy(tar, it) }
                }
                entry = tar.nextEntry
            }
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "extract ${archive.name}", e); false
    }

    /**
     * Bannerlator's rule: the manifest's own temp paths are Bottles', so the files are found by name
     * anywhere in the unpacked tree, preferring the ones under the matching win32/win64 folder.
     */
    private fun copyMatching(srcRoot: File, pattern: String, dest: File, arch: String?) {
        if (pattern.isEmpty()) return
        val rx = Regex("^" + pattern.split("*").joinToString(".*") { Regex.escape(it) } + "$", RegexOption.IGNORE_CASE)
        var matches = srcRoot.walkTopDown().filter { it.isFile && rx.matches(it.name) }.toList()
        if (arch != null) {
            val inArch = matches.filter { it.path.contains("/$arch/", true) }
            val other = if (arch == "win64") "win32" else "win64"
            matches = inArch.ifEmpty { matches.filterNot { it.path.contains("/$other/", true) } }
        }
        dest.mkdirs()
        matches.forEach { f -> f.copyTo(File(dest, f.name.lowercase()), overwrite = true) }
    }

    /** Removes a component from the runtime and from every game's picks. */
    @Synchronized
    fun uninstall(context: Context, id: String) {
        require(validId(id))
        FileUtils.delete(File(LinuxRuntime.rootDir(context), "$STORE/$id"))
        save(context, read(context).mapValues { (_, ids) -> ids - id }.filterValues { it.isNotEmpty() })
    }

    /** Each game's picks, by the appid its prefix is named after. */
    @Synchronized
    fun read(context: Context): Map<String, List<String>> = try {
        val json = JSONObject(AtomicFile(File(context.filesDir, SELECTION)).readFully().toString(Charsets.UTF_8))
        val games = json.optJSONObject("games") ?: JSONObject()
        games.keys().asSequence().associateWith { app ->
            val ids = games.getJSONArray(app)
            (0 until ids.length()).map { ids.getString(it) }.filter { validId(it) }
        }
    } catch (_: java.io.FileNotFoundException) {
        emptyMap()
    }

    fun picks(context: Context, appKey: String): List<String> = read(context)[appKey].orEmpty()

    @Synchronized
    fun setPicks(context: Context, appKey: String, ids: List<String>) {
        require(ids.all { validId(it) })
        val all = read(context).toMutableMap()
        if (ids.isEmpty()) all.remove(appKey) else all[appKey] = ids.distinct()
        save(context, all)
    }

    private fun save(context: Context, games: Map<String, List<String>>) {
        val text = JSONObject().put("version", 1).put("games", JSONObject().apply {
            games.forEach { (app, ids) -> put(app, JSONArray(ids)) }
        }).toString()
        write(File(context.filesDir, SELECTION), text)
        publish(context, text)
    }

    /**
     * Writes the picks where the launch script reads them; SessionFiles calls this at every session
     * start too, so a runtime reinstalled since keeps them. An empty selection still writes a file,
     * which is how a component turned off for every game is taken out of their prefixes.
     */
    @Synchronized
    fun publish(context: Context, text: String? = null) {
        val body = text ?: runCatching { AtomicFile(File(context.filesDir, SELECTION)).readFully().toString(Charsets.UTF_8) }.getOrNull() ?: return
        val root = LinuxRuntime.rootDir(context)
        if (root.isDirectory) write(File(root, GUEST_SELECTION), body)
    }

    private fun write(file: File, text: String) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }
}
