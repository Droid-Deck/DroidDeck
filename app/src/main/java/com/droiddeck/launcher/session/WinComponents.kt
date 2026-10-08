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
    const val CATALOG_URL = "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/components.json"
    private const val SELECTION = "wincomponents.json"
    private const val GUEST_SELECTION = "root/.config/droiddeck/wincomponents.json"
    private const val STORE = "opt/droiddeck/wincomponents"
    private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    // register_dll is left out on purpose: the DLLs it names are ones Wine ships and has registered
    // at the same path, so the native file at that path is the one the registration loads.
    private val FILE_STEPS = setOf("download_archive", "archive_extract", "copy_dll", "copy_file", "override_dll", "register_dll")
    private val INSTALLER_STEPS = setOf("install_exe", "install_msi")
    private const val MSI_INSTALL = "/usr/local/bin/droiddeck-msi-install"
    /** Where a downloaded .msi waits for the installer, inside the runtime so it can read it. */
    private const val MSI_CACHE = "$STORE/.packages"
    /** Catalog entries Proton already provides newer copies of, which an install would only shadow. */
    private val PROTON_PROVIDES = setOf("gecko")

    fun validId(id: String): Boolean = ID.matches(id) && ".." !in id

    class Step(val action: String, val json: JSONObject) {
        fun str(key: String): String = json.optString(key, "")
    }

    class Component(
        val name: String, val description: String, val provider: String, val status: String,
        val dependencies: List<String>, val steps: List<Step>,
    )

    enum class Support { READY, NEEDS_INSTALLER, UNSUPPORTED }

    /** A step that installs a Windows Installer package, which droiddeck-msi-install does here. */
    private fun isMsi(step: Step): Boolean = step.action in INSTALLER_STEPS && step.str("url").startsWith("https://") &&
        (step.action == "install_msi" || listOf(step.str("file_name"), step.str("url").substringBefore('?'))
            .any { it.endsWith(".msi", ignoreCase = true) })

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
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "catalog: $e"); null
        }
    }

    /** Whether [c] can be installed here, its bundled components included. */
    fun support(c: Component, all: Map<String, Component>, depth: Int = 0): Support {
        if (c.status != "ready" || depth > 8 || c.name in PROTON_PROVIDES) return Support.UNSUPPORTED
        val installers = c.steps.filter { it.action in INSTALLER_STEPS }
        val own = when {
            installers.isNotEmpty() && installers.all { isMsi(it) } &&
                c.steps.all { it in installers || it.action == "delete_dlls" || it.action in FILE_STEPS && fileStepOk(it) } -> Support.READY
            installers.isNotEmpty() -> Support.NEEDS_INSTALLER
            c.steps.all { it.action in FILE_STEPS && fileStepOk(it) } -> Support.READY
            else -> Support.UNSUPPORTED
        }
        if (own != Support.READY) return own
        return c.dependencies.map { all[it]?.let { d -> support(d, all, depth + 1) } ?: Support.UNSUPPORTED }
            .maxOrNull() ?: Support.READY
    }

    private fun fileStepOk(step: Step): Boolean = when (step.action) {
        "download_archive", "archive_extract" -> step.str("url").let { url ->
            !url.startsWith("http") || url.startsWith("https://github.com/") &&
                url.substringBefore('?').let { it.endsWith(".tar.xz") || it.endsWith(".zip") }
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
    fun install(context: Context, c: Component, all: Map<String, Component>, onProgress: (String, Int) -> Unit): String? {
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
                onProgress(x.name, -1)
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

    private fun installOne(context: Context, c: Component, work: File, archives: HashMap<String, File>, onProgress: (String, Int) -> Unit): String? {
        val root = LinuxRuntime.rootDir(context)
        if (!root.isDirectory) return "The Linux runtime is not installed"
        val staging = File(root, "$STORE/.${c.name}.new").apply { FileUtils.delete(this); mkdirs() }
        val overrides = ArrayList<String>()
        var source: File? = null
        var msi: JSONObject? = null
        for (step in c.steps) when (step.action) {
            // A component may have several packages (PowerShell's 32- and 64-bit): each adds to the
            // same folder, the first one names the component.
            "install_msi", "install_exe" -> {
                val (result, problem) = installMsi(context, c, step, staging, onProgress)
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
                    val file = File(work, "${archives.size}-$name")
                    val ok = Downloader.downloadFile(url, file, false) { f -> onProgress("${c.name}: $name", if (f < 0) -1 else Math.round(f * 100f)) }
                    if (!ok) return "download failed: $name"
                    step.str("file_checksum").takeIf { it.length == 32 }?.let { md5 ->
                        if (!digest(file, "MD5").equals(md5, ignoreCase = true)) return "checksum mismatch: $name"
                    }
                    onProgress("${c.name}: unpacking $name", -1)
                    val dir = File(work, "${archives.size}-x").apply { mkdirs() }
                    if (!extract(file, dir)) return "could not unpack $name"
                    file.delete()
                    dir.also { archives[url] = it }
                }
            }
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
        val version = msi?.optString("version")?.takeIf { it.isNotEmpty() }
            ?: Regex("""\b(\d+\.\d+(\.\d+)*)\b""").find(c.description)?.value ?: "catalog"
        val meta = JSONObject()
            .put("id", c.name).put("version", version)
            .put("overrides", JSONArray(overrides.distinct()))
            .put("requires", JSONArray(c.dependencies))
        msi?.let { meta.put("kind", "msi").put("product", it.optString("product")).put("notes", it.optJSONArray("notes") ?: JSONArray()) }
        FileUtils.writeString(File(staging, "component.json"), meta.toString(1))
        val target = File(root, "$STORE/${c.name}")
        FileUtils.delete(target)
        if (!staging.renameTo(target)) return "could not place the files"
        return null
    }

    /**
     * Downloads the component's .msi into the runtime and has droiddeck-msi-install lay it out in
     * [staging]. Returns the installer's result (product, version, counts, notes), or null and why.
     * Everything the installer prints also goes to Download/DroidDeck/tools.
     */
    private fun installMsi(context: Context, c: Component, step: Step, staging: File, onProgress: (String, Int) -> Unit): Pair<JSONObject?, String> {
        val root = LinuxRuntime.rootDir(context)
        val url = step.str("url")
        val name = url.substringBefore('?').substringAfterLast('/')
        val cache = File(root, MSI_CACHE).apply { mkdirs() }
        val file = File(cache, "${c.name}.msi")
        try {
            if (!Downloader.downloadFile(url, file, false) { f -> onProgress("${c.name}: $name", if (f < 0) -1 else Math.round(f * 100f)) }) {
                return null to "download failed: $name"
            }
            step.str("file_checksum").takeIf { it.length == 32 }?.let { md5 ->
                if (!digest(file, "MD5").equals(md5, ignoreCase = true)) return null to "checksum mismatch: $name"
            }
            onProgress("${c.name}: installing", -1)
            var result: JSONObject? = null
            var problem: String? = null
            val status = GuestCommand.run(context, listOf(MSI_INSTALL, "/$MSI_CACHE/${file.name}", "/$STORE/${staging.name}"),
                logName = "wincomponents-${c.name}") { line ->
                when {
                    line.startsWith("progress ") -> onProgress("${c.name}: ${line.removePrefix("progress ")}", -1)
                    line.startsWith("result ") -> result = runCatching { JSONObject(line.removePrefix("result ")) }.getOrNull()
                    line.startsWith("error ") -> problem = line.removePrefix("error ")
                }
            }
            val done = result
            if (status != 0 || done == null) return null to (problem ?: "the installer stopped (status $status)")
            return done to ""
        } catch (e: Exception) {
            Log.e(TAG, "msi ${c.name}", e)
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
