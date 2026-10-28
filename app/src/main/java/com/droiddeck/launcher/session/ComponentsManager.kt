package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.Hashes
import com.droiddeck.launcher.R
import android.content.Context
import android.util.Log
import android.util.AtomicFile
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.artifact.GitHubArtifacts
import com.droiddeck.launcher.artifact.RepositorySources
import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.gpu.SystemVulkanDriver
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

object ComponentsManager {
    private const val TAG = "Components"
    private const val NIGHTLIES = "the412banner/nightlies"
    private const val DD_COMPONENTS = "droid-deck/droiddeck-components"
    internal const val DD_NIGHTLY = "linux-nightly"
    private val RELEASES = linkedMapOf(
        "FexCore-Linux" to "fex",
        "Dxvk-Linux" to "dxvk",
        "Dxvk-arm64ec-Linux" to "dxvk",
        "Dxvk-gplasync-Linux" to "dxvk",
        "Vkd3d-proton-Linux" to "vkd3d",
    )
    // DD_NIGHTLY holds every component in one release; the file name says which.
    private val NIGHTLY_PREFIXES = linkedMapOf("DD-dxvk-" to "dxvk", "DD-vkd3d-proton-" to "vkd3d", "DD-FEXCore-" to "fex")
    private val SOURCES = RELEASES.keys.map { NIGHTLIES to it } + (DD_COMPONENTS to DD_NIGHTLY)
    private val DOWNLOAD_REPOS = listOf(NIGHTLIES, DD_COMPONENTS)
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
    private val PE_ARCHES = listOf("aarch64-windows", "i386-windows")
    private val COMP_DLLS = mapOf(
        "dxvk" to setOf("d3d8.dll", "d3d9.dll", "d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll"),
        "vkd3d" to setOf("d3d12.dll", "d3d12core.dll"),
    )
    private const val PLUGIN_DATA = "root/homebrew/data/decky-components-manager"

    data class Proton(val id: String, val name: String, val dir: File, val guestPath: String, val version: String, val valve: Boolean)
    data class Component(
        val detected: String,
        val detail: String,
        val inUse: String,
        val selected: String,
        val activeFile: String?,
        val queued: String?,
    )
    data class Original(val comp: String, val protonVersion: String, val label: String, val size: Long)
    data class Package(
        val file: String, val comp: String, val version: String, val description: String, val size: Long,
        val source: String? = null,
    )
    data class CatalogItem(
        val file: String,
        val comp: String,
        val release: String,
        val url: String,
        val size: Long,
        val digest: String,
        val sourceId: String = "",
        val repository: String = NIGHTLIES,
        val repositoryId: Long = 0,
        val releaseId: Long = 0,
        val assetId: Long = 0,
        val localFile: String = file,
    ) {
        val id: String get() = if (sourceId.isEmpty()) file else "$comp:$sourceId:$assetId:${digest.substringAfter(':')}"
    }
    data class ProtonView(
        val proton: Proton, val components: Map<String, Component>, val originals: List<Original>, val inUseByGame: Boolean,
        val reappliedAt: Long = 0,
        val sync: EsyncPacks.ToolState? = null,
    )
    data class Snapshot(val protons: List<ProtonView>, val packages: List<Package>)
    data class Catalog(
        val items: List<CatalogItem>, val fetchedAt: Long, val failed: List<String> = emptyList(),
        val rateLimited: Boolean = false,
    )

    private val lock = Any()
    private const val MAX_WCP_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_WCP_ENTRIES = 8192
    private const val MAX_WCP_FILE_BYTES = 512L * 1024 * 1024
    private const val MAX_WCP_EXPANDED_BYTES = 4L * 1024 * 1024 * 1024
    private const val MAX_PROFILE_BYTES = 256 * 1024

    @JvmStatic fun sourceScope(comp: String): String {
        require(comp in COMPONENTS) { "Unknown component source scope" }
        return "wcp/$comp"
    }

    @JvmStatic fun sourceBuiltIns(comp: String): List<String> {
        require(comp in COMPONENTS) { "Unknown component source scope" }
        return listOf(NIGHTLIES, DD_COMPONENTS)
    }

    @JvmStatic fun sources(context: Context, comp: String): List<RepositorySources.Source> =
        RepositorySources.list(context, sourceScope(comp), sourceBuiltIns(comp))

    @JvmStatic fun saveSources(
        context: Context,
        comp: String,
        rows: List<RepositorySources.Source>,
    ): List<RepositorySources.Source> = RepositorySources.save(context, sourceScope(comp), sourceBuiltIns(comp), rows)


    private fun root(context: Context) = LinuxRuntime.rootDir(context)
    private fun steam(context: Context) = File(root(context), "root/.local/share/Steam")
    private fun host(context: Context, guest: String) = File(root(context), guest.removePrefix("/"))
    private fun dataDir(context: Context) = File(context.filesDir, "components").apply { mkdirs() }
    private fun packagesDir(context: Context) = File(dataDir(context), "packages").apply { mkdirs() }
    private fun provenanceFile(packageFile: File) = File(packageFile.parentFile, packageFile.name + ".source.json")
    private fun originalsDir(context: Context) = File(dataDir(context), "originals").apply { mkdirs() }
    private fun stateFile(context: Context) = File(dataDir(context), "state.json")
    private fun legacyCatalogFile(context: Context) = File(dataDir(context), "catalog.json")
    private const val LAUNCH_DIR = "root/.local/share/droiddeck-components"
    private const val LEGACY_LAUNCH_DIR = "root/.local/share/bannerlator-components"
    private fun launchDir(context: Context): File {
        migrateLaunchDir(context)
        return File(root(context), LAUNCH_DIR)
    }

    fun migrateLaunchDir(context: Context) = synchronized(lock) {
        val legacy = File(root(context), LEGACY_LAUNCH_DIR)
        val dir = File(root(context), LAUNCH_DIR)
        if (!legacy.isDirectory || dir.exists() || !legacy.renameTo(dir)) return@synchronized
        val desired = File(dir, "desired.tsv")
        val text = FileUtils.readString(desired) ?: return@synchronized
        val staged = File(dir, "desired.tsv.staged")
        if (!FileUtils.writeString(staged, text.replace("/$LEGACY_LAUNCH_DIR/", "/$LAUNCH_DIR/")) || !staged.renameTo(desired)) staged.delete()
    }

    fun safeName(s: String): String = s.replace(Regex("[^A-Za-z0-9._+-]+"), "_").trim('_').ifEmpty { "unnamed" }


    private fun pendingStateFile(context: Context) = File(dataDir(context), "state.pending.json")

    private fun loadState(context: Context): JSONObject {
        recoverState(context)
        return runCatching { JSONObject(stateFile(context).readText()) }.getOrElse { JSONObject() }.apply {
            if (!has("active")) put("active", JSONObject())
            if (!has("queued")) put("queued", JSONObject())
            if (!has("profiles")) put("profiles", JSONObject())
        }
    }

    private fun writeStateFile(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            writeSynced(tmp, text)
            check(tmp.renameTo(file)) { "Could not save component selections: ${file.name}" }
        } finally {
            tmp.delete()
        }
    }

    private fun writeSynced(file: File, text: String) {
        FileOutputStream(file).use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
    }

    private fun recoverState(context: Context) {
        val pending = pendingStateFile(context)
        if (!pending.exists()) return
        val transaction = JSONObject(pending.readText())
        val state = transaction.getJSONObject("state")
        val expected = transaction.getString("launchSha256")
        val published = File(launchDir(context), "desired.tsv")
        val staged = File(launchDir(context), "desired.tsv.tmp")
        if (!staged.exists() && published.isFile && Hashes.sha256(published) == expected) {
            writeStateFile(stateFile(context), state.toString(2))
            Log.i(TAG, "recovered published component selections")
        } else {
            Log.i(TAG, "discarded unpublished component selections")
        }
        check(pending.delete()) { "Could not finish component selection recovery" }
        if (staged.exists()) check(staged.delete()) { "Could not remove unpublished component selections" }
    }

    private fun saveState(context: Context, state: JSONObject, prepared: PreparedLaunchState = prepareLaunchState(context, state)) {
        val dir = launchDir(context)
        val tmp = File(dir, "desired.tsv.tmp")
        try {
            writeSynced(tmp, prepared.text)
            writeStateFile(pendingStateFile(context), JSONObject()
                .put("state", state)
                .put("launchSha256", Hashes.sha256(tmp))
                .toString())
            check(tmp.renameTo(File(dir, "desired.tsv"))) { "Could not publish component selections" }
            writeStateFile(stateFile(context), state.toString(2))
            check(pendingStateFile(context).delete()) { "Could not finish saving component selections" }
        } finally {
            if (!pendingStateFile(context).exists()) tmp.delete()
        }
        File(dir, "store").listFiles()?.filter { it.isDirectory && it.name !in prepared.keep }
            ?.forEach { it.deleteRecursively() }
    }

    fun republish(context: Context): Boolean = synchronized(lock) {
        recoverState(context)
        val saved = stateFile(context)
        if (saved.exists() && runCatching { JSONObject(saved.readText()) }.isFailure) return false
        val state = loadState(context)
        val prepared = prepareLaunchState(context, state)
        if ((FileUtils.readString(File(launchDir(context), "desired.tsv")) ?: "") == prepared.text) return false
        saveState(context, state, prepared)
        true
    }

    private fun JSONObject.sub(key: String): JSONObject = optJSONObject(key) ?: JSONObject().also { put(key, it) }

    private fun rememberProfiledComponent(state: JSONObject, comp: String) {
        val profiled = state.optJSONArray("profiledComponents")?.let { values ->
            (0 until values.length()).mapNotNull { values.optString(it).takeIf(COMPONENTS::contains) }
        }.orEmpty().toMutableSet()
        if (profiled.add(comp)) state.put("profiledComponents", JSONArray(profiled.sorted()))
    }

    fun gameComponents(context: Context, scope: String): Map<String, String> = synchronized(lock) {
        val profile = loadState(context).sub("profiles").optJSONObject(scope) ?: return emptyMap()
        COMPONENTS.mapNotNull { comp -> profile.optString(comp).takeIf(String::isNotEmpty)?.let { comp to it } }.toMap()
    }

    fun setGameComponent(context: Context, scope: String, comp: String, file: String?) =
        setGameComponent(context, scope, comp, file) { runtimeManaged(context, it) }

    internal fun setGameComponent(context: Context, scope: String, comp: String, file: String?, managed: (String) -> Boolean) = synchronized(lock) {
        require(scope.matches(Regex("[1-9][0-9]{0,9}")) && scope.toLong() <= 4294967295L)
        require(comp in COMPONENTS)
        if (file != null) requireSelectable(context, comp, managed)
        val state = loadState(context)
        val profiles = state.sub("profiles")
        val profile = profiles.optJSONObject(scope) ?: JSONObject().also { profiles.put(scope, it) }
        if (file == null) {
            if (profile.optString(comp).isNotEmpty()) rememberProfiledComponent(state, comp)
            profile.remove(comp)
            if (profile.length() == 0) profiles.remove(scope)
        } else {
            val name = safeName(file)
            val wcp = File(packagesDir(context), name)
            require(packageInfo(context, wcp).comp == comp)
            for (proton in protons(context)) ensureOriginal(context, proton, comp, state)
            rememberProfiledComponent(state, comp)
            profile.put(comp, name)
        }
        saveState(context, state)
    }

    fun clearGameComponents(context: Context, scope: String) = synchronized(lock) {
        require(GameEnvironment.validScope(scope))
        val state = loadState(context)
        val profiles = state.sub("profiles")
        profiles.optJSONObject(scope)?.let { profile ->
            COMPONENTS.filter { profile.optString(it).isNotEmpty() }
                .forEach { rememberProfiledComponent(state, it) }
        }
        profiles.remove(scope)
        saveState(context, state)
    }

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

    fun protons(context: Context): List<Proton> {
        val found = LinkedHashMap<String, Proton>()
        val bases = listOf(File(steam(context), "compatibilitytools.d") to false) +
            libraryRoots(context).map { File(it, "steamapps/common") to true }
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

    fun inUse(context: Context, proton: Proton): Boolean =
        anyProcess(context, proton) { paths, text -> processUsesProton(paths, text, gameOnly = false) }

    fun gameRunning(context: Context, proton: Proton): Boolean =
        anyProcess(context, proton) { paths, text -> processUsesProton(paths, text, gameOnly = true) }

    internal fun processUsesProton(paths: List<String>, text: String, gameOnly: Boolean): Boolean =
        paths.any { path ->
            val needle = path.trimEnd('/') + "/"
            if (gameOnly) text.contains(needle + "proton waitforexitandrun")
            else text.contains(needle + "proton ") || text.contains(needle + "files/bin")
        }

    private fun anyProcess(context: Context, proton: Proton, match: (List<String>, String) -> Boolean): Boolean {
        val guestPaths = listOf(proton.guestPath) + EsyncPacks.distPathsFor(root(context), proton.guestPath)
        val paths = (guestPaths + guestPaths.map { host(context, it).absolutePath } + proton.dir.absolutePath).distinct()
        val procs = File("/proc").listFiles() ?: return false
        for (p in procs) {
            if (!p.name.all(Char::isDigit)) continue
            val cmd = runCatching { File(p, "cmdline").readBytes() }.getOrNull() ?: continue
            if (cmd.isEmpty()) continue
            val text = String(cmd).replace('\u0000', ' ')
            if (match(paths, text)) return true
        }
        return false
    }


    private fun readVersionFile(f: File): String = runCatching {
        val text = f.readText().trim()
        Regex("\\(([^)]+)\\)").find(text)?.groupValues?.get(1) ?: text.split(Regex("\\s+")).last()
    }.getOrDefault("")

    private fun fexVersion(dir: File, unnamed: String = "FEX (build without a version name)"): String {
        val dll = File(dir, FEX_FILES[0])
        if (!dll.isFile) return ""
        val text = runCatching { String(dll.readBytes(), Charsets.ISO_8859_1) }.getOrDefault("")
        return Regex("FEX-\\d{4}(?:\\.\\d+)?(?:-\\d+-g[0-9a-f]{6,})?").findAll(text).map { it.value }.maxByOrNull { it.length }
            ?: unnamed
    }

    private fun compFiles(dir: File, comp: String): List<String> {
        if (comp == "fex") return FEX_FILES.filter { File(dir, it).isFile }
        val base = File(dir, COMP_DIR.getValue(comp))
        return base.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.sorted().toList()
    }

    private fun owned(comp: String, rel: String) = comp == "fex" || PE_ARCHES.any { "/$it/" in rel }

    private fun fingerprint(dir: File, comp: String): Map<String, String> =
        compFiles(dir, comp).filter { owned(comp, it) }.associateWith { Hashes.sha256(File(dir, it)) }


    private inline fun <T> readWcp(f: File, block: (TarArchiveInputStream) -> T): T =
        f.let {
            require(it.isFile && it.length() in 1..MAX_WCP_BYTES) { "Component archive has an invalid size" }
            TarArchiveInputStream(ZstdInputStream(BufferedInputStream(FileInputStream(it), 1 shl 16))).use(block)
        }

    internal fun safeArchivePath(name: String, isDirectory: Boolean = false): String {
        require(name.isNotEmpty() && !name.startsWith('/') && '\\' !in name && name.none(Char::isISOControl)) {
            "unsafe archive path"
        }
        if (isDirectory && (name == "." || name == "./")) return ""
        val rel = name.removePrefix("./").let { if (isDirectory) it.removeSuffix("/") else it }
        require(rel.isNotEmpty() && rel.length <= 1024 && rel.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "unsafe archive path $name"
        }
        return rel
    }

    private fun safeArchivePath(entry: TarArchiveEntry): String {
        require(entry.linkFlag == TarConstants.LF_NORMAL || entry.linkFlag == TarConstants.LF_OLDNORM ||
            entry.linkFlag == TarConstants.LF_DIR) { "unsupported archive entry ${entry.name}" }
        return safeArchivePath(entry.name, entry.isDirectory)
    }

    private fun readEntry(input: TarArchiveInputStream, size: Long, limit: Long): ByteArray {
        require(size in 0..limit && size <= Int.MAX_VALUE) { "archive entry is too large" }
        val bytes = ByteArray(size.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) throw IOException("archive entry is truncated")
            offset += count
        }
        return bytes
    }

    private fun readProfile(context: Context, f: File): JSONObject = readWcp(f) { tar ->
        var entries = 0
        var expanded = 0L
        val seen = HashSet<String>()
        while (true) {
            val e = tar.nextTarEntry ?: break
            require(++entries <= MAX_WCP_ENTRIES) { "Component archive has too many entries" }
            val rel = safeArchivePath(e)
            require(seen.add(rel)) { "duplicate archive path $rel" }
            if (e.isFile) {
                require(e.size in 0..MAX_WCP_FILE_BYTES) { "archive entry is too large" }
                expanded += e.size
                require(expanded <= MAX_WCP_EXPANDED_BYTES) { "Component archive expands beyond its limit" }
            }
            if (e.isFile && rel == "profile.json") {
                return@readWcp JSONObject(readEntry(tar, e.size, MAX_PROFILE_BYTES.toLong()).decodeToString())
            }
        }
        throw IllegalArgumentException(context.getString(R.string.cmgr_no_profile, f.name))
    }

    private fun packageInfo(context: Context, f: File): Package {
        val prof = readProfile(context, f)
        val comp = TYPES[prof.optString("type")]
            ?: throw IllegalArgumentException(context.getString(R.string.cmgr_not_component_package, f.name, prof.optString("type")))
        val origin = runCatching { JSONObject(FileUtils.readString(provenanceFile(f)) ?: "{}").optString("repository").ifEmpty { null } }
            .getOrNull()
        return Package(f.name, comp, prof.optString("versionName", f.nameWithoutExtension), prof.optString("description"), f.length(), origin)
    }

    fun packages(context: Context): List<Package> = packagesDir(context).listFiles()
        ?.filter { it.isFile && it.name.endsWith(".wcp") }
        ?.mapNotNull { f -> runCatching { packageInfo(context, f) }.onFailure { Log.w(TAG, "skipping ${f.name}", it) }.getOrNull() }
        ?.sortedWith(newestFirst { it.version.ifEmpty { it.file } })
        ?: emptyList()

    private fun installFiles(context: Context, dir: File, comp: String, wcp: File): Map<String, String> {
        val staged = File(dataDir(context), "staging-${System.nanoTime()}")
        try {
            readWcp(wcp) { tar ->
                var entries = 0
                var expanded = 0L
                val seen = HashSet<String>()
                while (true) {
                    val e = tar.nextTarEntry ?: break
                    require(++entries <= MAX_WCP_ENTRIES) { "Component archive has too many entries" }
                    val rel = safeArchivePath(e)
                    require(seen.add(rel)) { "duplicate archive path $rel" }
                    if (e.isDirectory || !rel.startsWith("files/")) continue
                    require(e.size in 0..MAX_WCP_FILE_BYTES) { "archive entry is too large" }
                    expanded += e.size
                    require(expanded <= MAX_WCP_EXPANDED_BYTES) { "Component archive expands beyond its limit" }
                    val out = File(staged, rel)
                    require(out.canonicalPath.startsWith(staged.canonicalPath + File.separator)) { "unsafe path $rel" }
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { output ->
                        var remaining = e.size
                        val buffer = ByteArray(1 shl 16)
                        while (remaining > 0) {
                            val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (count < 0) throw IOException("archive entry is truncated")
                            output.write(buffer, 0, count)
                            remaining -= count
                        }
                    }
                }
            }
            require(File(staged, "files").isDirectory) { "${wcp.name} has no files/" }
            if (comp == "fex") FEX_FILES.filter { it.endsWith(".so") && !File(staged, it).isFile }.forEach { File(dir, it).delete() }
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
        check(tmp.renameTo(dest)) { "Could not save original bundle ${dest.name}" }
    }


    private fun originalFile(context: Context, p: Proton, comp: String) =
        File(originalsDir(context), "${p.id}/${safeName(p.version)}/$comp.wcp").apply { parentFile?.mkdirs() }

    fun originals(context: Context, protonId: String): List<Original> {
        val base = File(originalsDir(context), protonId)
        val versions = base.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() } ?: return emptyList()
        return versions.flatMap { v ->
            COMPONENTS.mapNotNull { comp ->
                val f = File(v, "$comp.wcp")
                if (!f.isFile) null else Original(comp, v.name, runCatching { readProfile(context, f).optString("description") }.getOrDefault(""), f.length())
            }
        }
    }

    private fun ensureOriginal(context: Context, p: Proton, comp: String, state: JSONObject) {
        val dest = originalFile(context, p, comp)
        if (dest.isFile) return
        val active = state.sub("active").optJSONObject(p.id)?.optJSONObject(comp)
        if (active != null && active.optString("protonVersion") == p.version) return
        val published = File(launchDir(context), "desired.tsv")
        if (published.isFile) {
            val mayHaveOverride = published.useLines { rows ->
                rows.any { row ->
                    val fields = row.split('\t')
                    fields.size == 7 && fields[0] != "*" && fields[1] == p.dir.name &&
                        fields[2] == p.version && fields[3] == comp
                }
            }
            check(!mayHaveOverride) { context.getString(R.string.cmgr_original_missing) }
        }
        val rels = compFiles(p.dir, comp)
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

    fun snapshotAll(context: Context) = synchronized(lock) {
        migratePlugin(context)
        val state = loadState(context)
        for (p in protons(context)) for (comp in COMPONENTS) {
            runCatching { ensureOriginal(context, p, comp, state) }.onFailure { Log.w(TAG, "original $comp of ${p.name}", it) }
        }
        runCatching { saveState(context, state) }.onFailure { Log.w(TAG, "launch state", it) }
    }


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
        val syncPacks = EsyncPacks.status(root(context))
        val views = protons(context).map { p ->
            val comps = COMPONENTS.associateWith { comp ->
                val detected = if (comp == "fex") fexVersion(p.dir, context.getString(R.string.cmgr_fex_unnamed)) else readVersionFile(File(p.dir, "${COMP_DIR.getValue(comp)}/version"))
                val detail = if (comp == "fex") context.getString(if (File(p.dir, FEX_FILES[2]).isFile) R.string.cmgr_fex_linux_helpers else R.string.cmgr_fex_dlls_only) else ""
                val active = state.sub("active").optJSONObject(p.id)?.optJSONObject(comp)
                val inUseLabel = when {
                    active == null -> context.getString(R.string.comp_tag_original)
                    active.optString("protonVersion") != p.version -> context.getString(R.string.cmgr_original_proton_updated)
                    else -> {
                        val want = active.optJSONObject("files") ?: JSONObject()
                        val fp = fingerprint(p.dir, comp)
                        if (want.keys().asSequence().all { fp[it] == want.optString(it) }) originalLabel(context, active) ?: active.optString("label", active.optString("file"))
                        else context.getString(R.string.cmgr_changed_outside)
                    }
                }
                val selectedLabel = when {
                    active == null || active.optString("protonVersion") != p.version ->
                        context.getString(R.string.comp_tag_original)
                    else -> originalLabel(context, active) ?: active.optString("label", active.optString("file"))
                }
                val q = state.sub("queued").optJSONObject(p.id)?.optJSONObject(comp)
                Component(
                    detected.ifEmpty { context.getString(R.string.cmgr_not_present) }, detail, inUseLabel, selectedLabel,
                    active?.optString("file")?.takeIf { active.optString("protonVersion") == p.version },
                    q?.let { originalLabel(context, it) ?: it.optString("label") },
                )
            }
            ProtonView(p, comps, originals(context, p.id), gameRunning(context, p), reapplied[p.dir.name] ?: 0, syncPacks.tool(p.guestPath))
        }
        Snapshot(views, packages(context))
    }

    private fun originalLabel(context: Context, entry: JSONObject): String? = when {
        entry.optString("kind") == "original" -> context.getString(R.string.cmgr_original_version, entry.optString("protonVersion"))
        entry.optString("file").startsWith("original ") -> context.getString(R.string.cmgr_original_from, entry.optString("file").removePrefix("original "))
        else -> null
    }


    private data class PreparedLaunchState(val text: String, val keep: Set<String>)

    private fun prepareLaunchState(context: Context, state: JSONObject): PreparedLaunchState {
        val dir = launchDir(context)
        val store = File(dir, "store").apply { mkdirs() }
        val all = protons(context)
        val lines = StringBuilder()
        val keep = HashSet<String>()

        fun unpack(key: String, wcp: File, comp: String): File {
            check(wcp.isFile) { context.getString(R.string.cmgr_not_stored) }
            val unpacked = File(store, key)
            if (!File(unpacked, ".complete").isFile) {
                check(packageInfo(context, wcp).comp == comp) { "Wrong component in ${wcp.name}" }
                unpacked.deleteRecursively()
                check(unpacked.mkdirs()) { "Could not stage component bundle ${wcp.name}" }
                readWcp(wcp) { tar ->
                    var entries = 0
                    var expanded = 0L
                    val seen = HashSet<String>()
                    while (true) {
                        val e = tar.nextTarEntry ?: break
                        require(++entries <= MAX_WCP_ENTRIES) { "Component archive has too many entries" }
                        val rel = safeArchivePath(e)
                        require(seen.add(rel)) { "duplicate archive path $rel" }
                        if (e.isDirectory || !rel.startsWith("files/")) continue
                        require(e.size in 0..MAX_WCP_FILE_BYTES) { "archive entry is too large" }
                        expanded += e.size
                        require(expanded <= MAX_WCP_EXPANDED_BYTES) { "Component archive expands beyond its limit" }
                        val out = File(unpacked, rel)
                        require(out.canonicalPath.startsWith(unpacked.canonicalPath + File.separator)) { "unsafe path $rel" }
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { output ->
                            var remaining = e.size
                            val buffer = ByteArray(1 shl 16)
                            while (remaining > 0) {
                                val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (count < 0) throw IOException("archive entry is truncated")
                                output.write(buffer, 0, count)
                                remaining -= count
                            }
                        }
                    }
                }
                File(unpacked, ".complete").writeText("1\n")
            }
            keep += key
            return unpacked
        }

        fun managed(comp: String): Set<String> = if (comp == "fex") FEX_FILES.toSet() else buildSet {
            for (arch in PE_ARCHES) for (dll in COMP_DLLS.getValue(comp)) add("${COMP_DIR.getValue(comp)}/$arch/$dll")
        }

        fun append(scope: String, p: Proton, comp: String, key: String, wcp: File) {
            val unpacked = unpack(key, wcp, comp)
            val files = unpacked.let { base ->
                File(base, "files").walkTopDown().filter { it.isFile }.map { it.relativeTo(base).invariantSeparatorsPath }
                    .filter { owned(comp, it) }.associateWith { File(base, it) }
            }
            for (rel in managed(comp) - files.keys) {
                lines.append(scope).append('\t').append(p.dir.name).append('\t').append(p.version).append('\t').append(comp)
                    .append('\t').append(rel).append("\t-\t-\n")
            }
            for ((rel, src) in files) {
                val guestSrc = "/" + src.relativeTo(root(context)).invariantSeparatorsPath
                lines.append(scope).append('\t').append(p.dir.name).append('\t').append(p.version).append('\t').append(comp)
                    .append('\t').append(rel).append('\t').append(Hashes.sha256(src)).append('\t').append(guestSrc).append('\n')
            }
        }

        fun choice(p: Proton, comp: String, entry: JSONObject): Pair<String, File>? {
            val file = entry.optString("file")
            return if (file.startsWith("original ")) {
                val version = safeName(file.removePrefix("original "))
                "original-${p.id}-$version-$comp" to File(originalsDir(context), "${p.id}/$version/$comp.wcp")
            } else {
                safeName(file.removeSuffix(".wcp")) to File(packagesDir(context), file)
            }.takeIf { it.second.isFile }
        }

        val profiles = state.sub("profiles")
        val profiledComponents = buildSet {
            state.optJSONArray("profiledComponents")?.let { stored ->
                for (index in 0 until stored.length()) {
                    stored.optString(index).takeIf(COMPONENTS::contains)?.let(::add)
                }
            }
            for (scope in profiles.keys()) {
                val profile = profiles.optJSONObject(scope) ?: continue
                COMPONENTS.filterTo(this) { profile.optString(it).isNotEmpty() }
            }
        }
        val active = state.sub("active")
        for (p in all) for (comp in COMPONENTS) {
            val entry = active.optJSONObject(p.id)?.optJSONObject(comp)
            val selected = entry?.takeIf { it.optString("protonVersion") == p.version }?.let { choice(p, comp, it) }
            if (selected != null) append("*", p, comp, selected.first, selected.second)
            else if (comp in profiledComponents) {
                ensureOriginal(context, p, comp, state)
                val original = originalFile(context, p, comp)
                check(original.isFile) { context.getString(R.string.cmgr_original_missing) }
                append("*", p, comp, "original-${p.id}-${safeName(p.version)}-$comp", original)
            }
        }
        for (scope in profiles.keys()) {
            val profile = profiles.optJSONObject(scope) ?: continue
            for (comp in COMPONENTS) {
                val file = profile.optString(comp)
                if (file.isEmpty()) continue
                val wcp = File(packagesDir(context), file)
                if (!wcp.isFile) continue
                val key = safeName(file.removeSuffix(".wcp"))
                for (p in all) append(scope, p, comp, key, wcp)
            }
        }
        return PreparedLaunchState(lines.toString(), keep)
    }


    internal fun runtimeManaged(comp: String, runtimeManagedDxvk: Boolean): Boolean = comp == "dxvk" && runtimeManagedDxvk

    private fun runtimeManaged(context: Context, comp: String): Boolean =
        runtimeManaged(comp, SystemVulkanDriver.managesDxvk(SessionPrefs.linuxDriver(context)))

    private fun requireSelectable(context: Context, comp: String, managed: (String) -> Boolean = { runtimeManaged(context, it) }) {
        check(!managed(comp)) { context.getString(R.string.cmgr_system_dxvk_managed, SystemVulkanDriver.gpuName()) }
    }

    fun swap(context: Context, protonId: String, file: String): String = synchronized(lock) {
        val p = proton(context, protonId)
        val wcp = File(packagesDir(context), safeName(file))
        val info = packageInfo(context, wcp)
        requireSelectable(context, info.comp)
        val state = loadState(context)
        if (inUse(context, p)) {
            state.sub("queued").sub(p.id).put(info.comp, JSONObject().put("kind", "package").put("file", wcp.name).put("label", info.version))
            saveState(context, state)
            return context.getString(R.string.cmgr_swap_queued, LABEL[info.comp], info.version, p.name)
        }
        applyPackage(context, p, info, wcp, state)
        state.sub("queued").optJSONObject(p.id)?.remove(info.comp)
        saveState(context, state)
        context.getString(R.string.cmgr_swap_done, LABEL[info.comp], info.version, p.name)
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
        requireSelectable(context, comp)
        val p = proton(context, protonId)
        val wcp = File(originalsDir(context), "$protonId/${safeName(protonVersion)}/$comp.wcp")
        check(wcp.isFile) { context.getString(R.string.cmgr_original_missing) }
        val state = loadState(context)
        if (inUse(context, p)) {
            state.sub("queued").sub(p.id).put(comp, JSONObject().put("kind", "original").put("protonVersion", protonVersion).put("label", "Original $protonVersion"))
            saveState(context, state)
            return context.getString(R.string.cmgr_restore_queued, LABEL[comp], p.name)
        }
        applyOriginal(context, p, comp, protonVersion, wcp, state)
        state.sub("queued").optJSONObject(p.id)?.remove(comp)
        saveState(context, state)
        context.getString(R.string.cmgr_restore_done, LABEL[comp], p.name, protonVersion)
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

    fun applyQueued(context: Context): List<String> = applyQueued(context) { runtimeManaged(context, it) }

    internal fun applyQueued(context: Context, managed: (String) -> Boolean): List<String> = synchronized(lock) {
        val state = loadState(context)
        val queued = state.sub("queued")
        if (queued.length() == 0) return emptyList()
        val done = mutableListOf<String>()
        val all = protons(context)
        for (pid in queued.keys().asSequence().toList()) {
            val p = all.firstOrNull { it.id == pid } ?: run { queued.remove(pid); null } ?: continue
            if (inUse(context, p)) continue
            val comps = queued.optJSONObject(pid) ?: continue
            for (comp in comps.keys().asSequence().toList()) {
                val q = comps.optJSONObject(comp) ?: continue
                if (managed(comp)) continue
                runCatching {
                    if (q.optString("kind") == "original") {
                        val v = q.optString("protonVersion")
                        applyOriginal(context, p, comp, v, File(originalsDir(context), "$pid/${safeName(v)}/$comp.wcp"), state)
                    } else {
                        val wcp = File(packagesDir(context), q.optString("file"))
                        applyPackage(context, p, packageInfo(context, wcp), wcp, state)
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

    class StoredPackage(val pkg: Package, val inUse: Boolean)
    class StoredOriginal(val protonId: String, val protonName: String?, val comp: String, val protonVersion: String, val size: Long, val kept: Boolean)
    class Stored(val packages: List<StoredPackage>, val originals: List<StoredOriginal>)

    internal fun liveSelection(key: String, entry: JSONObject, proton: Proton?): Boolean =
        proton != null && (key == "queued" || entry.optString("protonVersion") == proton.version)

    private fun packagesInUse(state: JSONObject, installed: Map<String, Proton>): Set<String> {
        val names = HashSet<String>()
        for (key in listOf("active", "queued")) {
            val byProton = state.optJSONObject(key) ?: continue
            for (pid in byProton.keys()) {
                val comps = byProton.optJSONObject(pid) ?: continue
                for (comp in comps.keys()) {
                    val entry = comps.optJSONObject(comp) ?: continue
                    if (liveSelection(key, entry, installed[pid])) entry.optString("file").takeIf { it.isNotEmpty() }?.let(names::add)
                }
            }
        }
        val profiles = state.optJSONObject("profiles")
        for (scope in profiles?.keys() ?: emptyList<String>().iterator()) {
            val profile = profiles?.optJSONObject(scope) ?: continue
            COMPONENTS.forEach { comp -> profile.optString(comp).takeIf { it.isNotEmpty() }?.let(names::add) }
        }
        return names
    }

    private fun originalKept(state: JSONObject, installed: Map<String, Proton>, protonId: String, comp: String, version: String): Boolean {
        val current = installed[protonId]?.let { safeName(it.version) == version } == true
        val queued = state.optJSONObject("queued")?.optJSONObject(protonId)?.optJSONObject(comp)
            ?.takeIf { it.optString("kind") == "original" }?.let { safeName(it.optString("protonVersion")) == version } == true
        return current || queued
    }

    fun stored(context: Context): Stored = synchronized(lock) {
        val state = loadState(context)
        val installed = protons(context).distinctBy { it.id }.associateBy { it.id }
        val used = packagesInUse(state, installed)
        val originals = originalsDir(context).listFiles()?.filter { it.isDirectory }?.flatMap { proton ->
            proton.listFiles()?.filter { it.isDirectory }?.flatMap { version ->
                COMPONENTS.mapNotNull { comp ->
                    val f = File(version, "$comp.wcp")
                    if (!f.isFile) null else StoredOriginal(
                        proton.name, installed[proton.name]?.name, comp, version.name, f.length(),
                        originalKept(state, installed, proton.name, comp, version.name),
                    )
                }
            }.orEmpty()
        }.orEmpty()
        Stored(packages(context).map { StoredPackage(it, it.file in used) }, originals)
    }

    fun deleteUnusedOriginal(context: Context, protonId: String, comp: String, protonVersion: String): Boolean = synchronized(lock) {
        val state = loadState(context)
        if (originalKept(state, protons(context).associateBy { it.id }, protonId, comp, protonVersion)) return false
        if (comp !in COMPONENTS || listOf(protonId, protonVersion).any { it.isEmpty() || '/' in it || it == "." || it == ".." }) return false
        val f = File(originalsDir(context), "$protonId/$protonVersion/$comp.wcp")
        if (!f.isFile || !f.delete()) return false
        f.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
        f.parentFile?.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
        true
    }

    private fun checkPackageUnused(context: Context, state: JSONObject, name: String) {
        val installed = protons(context).distinctBy { it.id }.associateBy { it.id }
        for (key in listOf("queued", "active")) {
            val byProton = state.sub(key)
            for (pid in byProton.keys()) {
                val comps = byProton.optJSONObject(pid) ?: continue
                for (comp in comps.keys()) {
                    val entry = comps.optJSONObject(comp) ?: continue
                    check(entry.optString("file") != name || !liveSelection(key, entry, installed[pid])) {
                        context.getString(R.string.cmgr_package_in_use, name, installed[pid]?.name ?: pid)
                    }
                }
            }
        }
        val profiles = state.sub("profiles")
        for (scope in profiles.keys()) {
            val profile = profiles.optJSONObject(scope) ?: continue
            check(COMPONENTS.none { profile.optString(it) == name }) { context.getString(R.string.cmgr_package_in_use, name, scope) }
        }
    }

    fun carryOver(context: Context, fromDir: String, toDir: String) {
        synchronized(lock) {
            val target = protons(context).firstOrNull { it.id == safeName(toDir) } ?: return
            val state = loadState(context)
            val wanted = carried(state, safeName(fromDir)) { File(packagesDir(context), it).isFile }
            val active = state.sub("active").optJSONObject(target.id)
            val queued = state.sub("queued").sub(target.id)
            var changed = false
            wanted.forEach { (comp, entry) ->
                if (active?.has(comp) != true && !queued.has(comp)) {
                    queued.put(comp, entry)
                    changed = true
                }
            }
            if (queued.length() == 0) state.sub("queued").remove(target.id)
            if (changed) saveState(context, state)
        }
    }

    internal fun carried(state: JSONObject, from: String, present: (String) -> Boolean): Map<String, JSONObject> = buildMap {
        state.optJSONObject("active")?.optJSONObject(from)?.let { active ->
            active.keys().forEach { comp ->
                val entry = active.optJSONObject(comp) ?: return@forEach
                val file = entry.optString("file")
                if (comp in COMPONENTS && file.endsWith(".wcp") && present(file)) {
                    put(comp, JSONObject().put("kind", "package").put("file", file).put("label", entry.optString("label")))
                }
            }
        }
        state.optJSONObject("queued")?.optJSONObject(from)?.let { queued ->
            queued.keys().forEach { comp ->
                val entry = queued.optJSONObject(comp) ?: return@forEach
                if (comp in COMPONENTS && entry.optString("kind") == "package" && present(entry.optString("file"))) put(comp, entry)
            }
        }
    }

    fun forgetProton(context: Context, dirName: String) {
        synchronized(lock) {
            val id = safeName(dirName)
            if (protons(context).any { it.id == id }) return
            val state = loadState(context)
            val selected = listOf("active", "queued").map { state.sub(it).remove(id) != null }.any { it }
            if (selected && runCatching { saveState(context, state) }.onFailure { Log.w(TAG, "forget $id", it) }.isFailure) return
            File(originalsDir(context), id).deleteRecursively()
        }
    }

    private fun storePackage(context: Context, part: File, dest: File) {
        try {
            if (dest.isFile && runCatching { Hashes.sha256(dest) }.getOrNull()?.equals(Hashes.sha256(part), true) == true) return
            if (dest.exists()) checkPackageUnused(context, loadState(context), dest.name)
            val store = File(launchDir(context), "store").canonicalFile
            val cacheKey = unpackedCacheKey(dest.name)
            val unpacked = File(store, cacheKey).canonicalFile
            check(unpacked.parentFile == store) { "Invalid unpacked component cache path for ${dest.name}" }
            val complete = File(unpacked, ".complete")
            if (complete.exists()) check(complete.delete()) { "Could not invalidate unpacked component ${dest.name}" }
            if (!part.renameTo(dest)) {
                dest.delete()
                check(part.renameTo(dest)) { "Could not store ${dest.name}" }
            }
            if (!unpacked.deleteRecursively() && unpacked.exists()) {
                check(!complete.exists()) { "Could not invalidate unpacked component ${dest.name}" }
                Log.w(TAG, "Could not remove stale unpacked files for ${dest.name}; cache marker is invalidated")
            }
        } finally {
            part.delete()
        }
    }

    internal fun unpackedCacheKey(filename: String): String = safeName(filename.removeSuffix(".wcp")).also {
        require(it != "." && it != "..") { "Invalid component cache name" }
    }

    fun deletePackage(context: Context, file: String): String = synchronized(lock) {
        val name = safeName(file)
        val state = loadState(context)
        val installed = protons(context).distinctBy { it.id }.associateBy { it.id }
        var changed = false
        for (key in listOf("queued", "active")) {
            val byProton = state.sub(key)
            for (pid in byProton.keys().asSequence().toList()) {
                val comps = byProton.optJSONObject(pid) ?: continue
                for (comp in comps.keys().asSequence().toList()) {
                    val entry = comps.optJSONObject(comp) ?: continue
                    if (entry.optString("file") != name) continue
                    check(!liveSelection(key, entry, installed[pid])) { context.getString(R.string.cmgr_package_in_use, name, installed[pid]?.name ?: pid) }
                    comps.remove(comp)
                    changed = true
                }
                if (comps.length() == 0) byProton.remove(pid)
            }
        }
        val f = File(packagesDir(context), name)
        check(f.isFile) { context.getString(R.string.cmgr_package_not_stored, name) }
        val profiles = state.sub("profiles")
        for (scope in profiles.keys().asSequence().toList()) {
            val profile = profiles.optJSONObject(scope) ?: continue
            for (comp in COMPONENTS.filter { profile.optString(it) == name }) {
                rememberProfiledComponent(state, comp)
                profile.remove(comp)
                changed = true
            }
            if (profile.length() == 0) profiles.remove(scope)
        }
        check(f.delete()) { name }
        provenanceFile(f).delete()
        if (changed) saveState(context, state)
        context.getString(R.string.cmgr_deleted, name)
    }

    fun deleteOriginal(context: Context, protonId: String, comp: String, protonVersion: String): String = synchronized(lock) {
        val p = proton(context, protonId)
        check(safeName(p.version) != protonVersion) { context.getString(R.string.cmgr_original_kept) }
        val f = File(originalsDir(context), "$protonId/${safeName(protonVersion)}/$comp.wcp")
        check(f.isFile) { context.getString(R.string.cmgr_not_stored) }
        f.delete()
        f.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
        context.getString(R.string.cmgr_deleted_original, protonVersion, LABEL[comp])
    }

    fun importPackage(context: Context, source: File, name: String): Package = synchronized(lock) {
        val info = packageInfo(context, source)
        val dest = File(packagesDir(context), safeName(name).let { if (it.endsWith(".wcp")) it else "$it.wcp" })
        storePackage(context, source.copyTo(File(dest.parentFile, dest.name + ".part"), overwrite = true), dest)
        provenanceFile(dest).delete()
        info.copy(file = dest.name)
    }

    internal var catalogRefresher: (Context, String, RepositorySources.Source) -> List<CatalogItem> =
        ::refreshCatalogSource
    internal var packageDownloader: (String, File, String, (Int) -> Unit) -> Boolean = { url, file, digest, progress ->
        Downloader.downloadFile(url, file, true, digest) { value ->
            progress(if (value < 0) -1 else (value * 100).toInt().coerceIn(0, 100))
        }
    }

    fun catalog(context: Context, force: Boolean): Catalog {
        val items = ArrayList<CatalogItem>()
        var fetchedAt = 0L
        val failed = ArrayList<String>()
        var rateLimited = false
        val legacy by lazy { readLegacyCatalog(context) }
        for (comp in COMPONENTS) {
            val scope = sourceScope(comp)
            val builtIns = sourceBuiltIns(comp)
            for (configured in RepositorySources.visible(context, scope, builtIns)) {
                var cached: Catalog? = null
                if (force) {
                    val generation = RepositorySources.generation(context, scope, builtIns)
                    runCatching {
                        val refreshed = catalogRefresher(context, comp, configured)
                        val bound = RepositorySources.visible(context, scope, builtIns)
                            .singleOrNull { it.id == configured.id }
                            ?: throw IOException("Component source changed while checking")
                        val checkedAt = System.currentTimeMillis() / 1000
                        writeSourceCatalog(context, comp, bound, refreshed, checkedAt, generation)
                        Catalog(refreshed.map { resolveLegacyLocalFile(context, it) }, checkedAt)
                    }.onSuccess {
                        cached = it
                    }.onFailure {
                        rateLimited = rateLimited || GitHubArtifacts.isRateLimited(it)
                        failed.add("$comp:${configured.repository}")
                        Log.w(TAG, "catalog ${configured.repository} for $comp", it)
                    }
                }
                if (cached == null) cached = readSourceCatalog(context, comp, configured)
                    ?: legacyCatalogFor(context, comp, configured, legacy)
                cached?.let {
                    items.addAll(it.items)
                    fetchedAt = maxOf(fetchedAt, it.fetchedAt)
                }
            }
        }
        return Catalog(
            items.distinctBy { it.id }.sortedWith(CATALOG_ORDER),
            fetchedAt,
            failed,
            rateLimited,
        )
    }

    private fun refreshCatalogSource(
        context: Context,
        comp: String,
        source: RepositorySources.Source,
    ): List<CatalogItem> {
        val releases = GitHubArtifacts.refreshReleases(context, sourceScope(comp), sourceBuiltIns(comp), source)
        val bound = RepositorySources.visible(context, sourceScope(comp), sourceBuiltIns(comp))
            .singleOrNull { it.id == source.id } ?: throw IOException("Component source changed while checking")
        return catalogItems(context, comp, bound, releases)
    }

    private fun sourceCatalogFile(context: Context, comp: String, source: RepositorySources.Source) =
        File(RepositorySources.cacheDirectory(context, sourceScope(comp), source), "components.json")

    private fun writeSourceCatalog(
        context: Context,
        comp: String,
        source: RepositorySources.Source,
        items: List<CatalogItem>,
        checkedAt: Long,
        generation: Long,
    ) {
        val repositoryId = source.repositoryId ?: throw IOException("Component source identity is not bound")
        require(items.size <= 2000 && items.all {
            it.comp == comp && it.sourceId == source.id && it.repository == source.repository &&
                it.repositoryId == repositoryId
        }) { "Component catalog provenance does not match its source" }
        val rows = JSONArray()
        items.forEach { rows.put(encodeCatalogItem(it)) }
        val json = JSONObject().put("schema", 1).put("scope", sourceScope(comp))
            .put("sourceId", source.id).put("repository", source.repository).put("repositoryId", repositoryId)
            .put("checkedAt", checkedAt).put("items", rows)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 8 * 1024 * 1024) { "Component catalog cache is too large" }
        RepositorySources.whileCurrent(context, sourceScope(comp), sourceBuiltIns(comp), source.id, generation) {
            RepositorySources.atomicWrite(sourceCatalogFile(context, comp, source), bytes)
        }
    }

    private fun readSourceCatalog(
        context: Context,
        comp: String,
        source: RepositorySources.Source,
    ): Catalog? {
        val current = RepositorySources.visible(context, sourceScope(comp), sourceBuiltIns(comp))
            .singleOrNull { it.id == source.id && it.repository == source.repository } ?: return null
        val repositoryId = current.repositoryId ?: return null
        return runCatching {
            val json = AtomicFile(sourceCatalogFile(context, comp, current)).openRead().use {
                JSONObject(RepositorySources.readBounded(it, 8 * 1024 * 1024).toString(Charsets.UTF_8))
            }
            require(json.getInt("schema") == 1 && json.getString("scope") == sourceScope(comp) &&
                json.getString("sourceId") == current.id && json.getString("repository") == current.repository &&
                json.getLong("repositoryId") == repositoryId)
            val rows = json.getJSONArray("items")
            require(rows.length() <= 2000)
            val items = (0 until rows.length()).map { decodeCatalogItem(rows.getJSONObject(it)) }
            require(items.all {
                it.comp == comp && it.sourceId == current.id && it.repository == current.repository &&
                    it.repositoryId == repositoryId
            })
            Catalog(items.map { resolveLegacyLocalFile(context, it) }, json.getLong("checkedAt"))
        }.getOrNull()
    }

    private fun encodeCatalogItem(item: CatalogItem): JSONObject = JSONObject()
        .put("file", item.file).put("comp", item.comp).put("release", item.release).put("url", item.url)
        .put("size", item.size).put("digest", item.digest).put("sourceId", item.sourceId)
        .put("repository", item.repository).put("repositoryId", item.repositoryId)
        .put("releaseId", item.releaseId).put("assetId", item.assetId).put("localFile", item.localFile)

    private fun decodeCatalogItem(row: JSONObject): CatalogItem {
        val item = CatalogItem(
            row.getString("file"), row.getString("comp"), row.getString("release"), row.getString("url"),
            row.getLong("size"), row.getString("digest"), row.getString("sourceId"),
            row.getString("repository"), row.getLong("repositoryId"), row.getLong("releaseId"),
            row.getLong("assetId"), row.getString("localFile"),
        )
        require(item.comp in COMPONENTS && Hashes.isGithubSha256(item.digest) && item.size > 0 &&
            item.repositoryId > 0 && item.releaseId > 0 && item.assetId > 0 &&
            item.localFile == safeName(item.localFile) && item.localFile.endsWith(".wcp") &&
            item.sourceId == RepositorySources.source(item.repository).id && releaseUrlMatches(
                item.repository, item.release, item.file, item.url,
            )) { "Invalid component catalog cache" }
        return item
    }

    private fun readLegacyCatalog(context: Context): Catalog? {
        val file = legacyCatalogFile(context)
        return runCatching {
            require(file.isFile && file.length() <= 12L * 1024 * 1024)
            parseLegacyCatalog(JSONObject(file.readText()))
        }.getOrNull()
    }

    private fun legacyCatalogFor(
        context: Context,
        comp: String,
        source: RepositorySources.Source,
        legacy: Catalog?,
    ): Catalog? {
        if (source.repository != NIGHTLIES || source.id != RepositorySources.source(NIGHTLIES).id) return null
        val rows = legacy?.items.orEmpty().filter { it.comp == comp }.map { old ->
            val sha = old.digest.substringAfter(':')
            val forced = FORCED_PACKAGES.any { it.comp == comp && it.file == old.file && it.release == old.release }
            val local = if (forced) safeName(old.file) else "$sha-${source.id}.wcp"
            old.copy(
                sourceId = source.id,
                repository = source.repository,
                repositoryId = source.repositoryId ?: 0,
                localFile = local,
            ).let { resolveLegacyLocalFile(context, it) }
        }
        return legacy?.let { Catalog(rows, it.fetchedAt) }
    }

    private fun catalogItems(
        context: Context,
        comp: String,
        source: RepositorySources.Source,
        releases: List<GitHubArtifacts.Release>,
    ): List<CatalogItem> {
        val items = ArrayList<CatalogItem>()
        for (release in releases) {
            if (RELEASES[release.tag] == comp) {
                release.assets.filter { it.name.endsWith(".wcp", ignoreCase = true) }.forEach { asset ->
                    items.add(catalogItem(comp, source, release, asset))
                }
                continue
            }
            if (release.tag == DD_NIGHTLY) {
                release.assets.filter { it.name.endsWith(".wcp", ignoreCase = true) && assetComp(release.tag, it.name) == comp }
                    .forEach { asset -> items.add(catalogItem(comp, source, release, asset)) }
                continue
            }
            val sidecars = release.assets.filter {
                it.name == "droiddeck-component.json" || it.name == "droiddeck-component-$comp.json"
            }
            for (sidecar in sidecars) {
                val bytes = GitHubArtifacts.readReleaseAsset(
                    context, sourceScope(comp), sourceBuiltIns(comp), source, release.tag, sidecar, 256 * 1024,
                )
                val start = items.size
                try {
                    val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
                    if (manifest.optString("component") != comp) continue
                    require(manifest.getInt("schema") == 1) { "Component manifest has an invalid schema" }
                    val rows = manifest.optJSONArray("artifacts") ?: JSONArray().apply {
                        manifest.optJSONObject("artifact")?.let { put(it) }
                    }
                    require(rows.length() in 1..64) { "Component manifest has an invalid artifact count" }
                    for (index in 0 until rows.length()) {
                        val row = rows.getJSONObject(index)
                        val name = row.getString("name")
                        require(name.endsWith(".wcp", ignoreCase = true)) { "Component artifact is not a WCP" }
                        val asset = release.assets.singleOrNull { it.name == name }
                            ?: throw IllegalArgumentException("Component artifact is missing or ambiguous")
                        val declaredSha = row.getString("sha256")
                        require(Regex("[0-9a-f]{64}").matches(declaredSha) && declaredSha == asset.sha256) {
                            "Component artifact digest does not match"
                        }
                        require(row.getLong("size") == asset.size) { "Component artifact size does not match" }
                        items.add(catalogItem(comp, source, release, asset))
                    }
                } catch (error: Exception) {
                    items.subList(start, items.size).clear()
                    Log.w(TAG, "sidecar ${sidecar.name} in ${release.tag}", error)
                }
            }
        }
        return items
    }

    private fun catalogItem(
        comp: String,
        source: RepositorySources.Source,
        release: GitHubArtifacts.Release,
        asset: GitHubArtifacts.Asset,
    ): CatalogItem {
        val ref = GitHubArtifacts.artifactRef(sourceScope(comp), source, release, asset)
        val forced = source.repository == NIGHTLIES && FORCED_PACKAGES.any {
            it.comp == comp && it.file == asset.name && it.release == release.tag
        }
        val local = if (forced) safeName(asset.name) else "${ref.sha256}-${source.id}.wcp"
        return CatalogItem(asset.name, comp, release.tag, asset.url, asset.size, "sha256:${asset.sha256}",
            source.id, source.repository, ref.repositoryId, ref.releaseId, ref.assetId, local)
    }

    private fun parseLegacyCatalog(o: JSONObject): Catalog {
        val arr = o.optJSONArray("items") ?: JSONArray()
        require(arr.length() <= 2000) { "Component catalog has too many items" }
        val items = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            CatalogItem(it.optString("file"), it.optString("comp"), it.optString("release"), it.optString("url"), it.optLong("size"), it.optString("digest"))
        }.sortedWith(CATALOG_ORDER)
        return Catalog(items, o.optLong("fetchedAt"))
    }

    internal fun assetComp(release: String, name: String): String? =
        RELEASES[release] ?: if (release == DD_NIGHTLY) NIGHTLY_PREFIXES.entries.firstOrNull { name.startsWith(it.key) }?.value else null

    internal val CATALOG_ORDER: Comparator<CatalogItem> =
        compareBy<CatalogItem> { it.comp }.thenBy { it.release == DD_NIGHTLY }.then(newestFirst { it.file })

    private fun pairingSourceMatches(stable: CatalogItem, nightly: CatalogItem): Boolean {
        if (stable.sourceId.isEmpty() || nightly.sourceId.isEmpty()) {
            return stable.sourceId.isEmpty() && nightly.sourceId.isEmpty() &&
                stable.repository == NIGHTLIES && nightly.repository == NIGHTLIES
        }
        if (stable.sourceId == nightly.sourceId) return stable.repository == nightly.repository
        return stable.repository == NIGHTLIES && stable.sourceId == RepositorySources.source(NIGHTLIES).id &&
            nightly.repository == DD_COMPONENTS && nightly.sourceId == RepositorySources.source(DD_COMPONENTS).id
    }

    internal fun nightlyPairs(items: List<CatalogItem>): Map<CatalogItem, CatalogItem> {
        val stable = items.filter { it.release != DD_NIGHTLY }
        return items.filter { it.release == DD_NIGHTLY }.mapNotNull { n ->
            val base = n.file.removePrefix("DD-").removeSuffix("-nightly.wcp").substringBefore('+')
            val rev = Regex(Regex.escape(base) + "(-\\d+)?-linux\\.wcp")
            stable.filter { it.comp == n.comp && pairingSourceMatches(it, n) && rev.matches(it.file) }
                .maxWithOrNull { a, b -> ProtonExtras.compareVersions(a.file, b.file) }?.let { it to n }
        }.groupBy({ it.first }, { it.second })
            .mapValues { (_, ns) -> ns.maxWith { a, b -> ProtonExtras.compareVersions(a.file, b.file) } }
    }

    internal fun allowedDownload(url: String) = DOWNLOAD_REPOS.any {
        url.startsWith("https://github.com/$it/releases/download/", ignoreCase = true)
    }

    private fun resolveLegacyLocalFile(context: Context, item: CatalogItem): CatalogItem {
        val preferred = File(packagesDir(context), safeName(item.localFile))
        if (preferred.isFile) {
            if (receiptMatches(preferred, item)) return item
            if (matchesCatalogPackage(context, preferred, item)) {
                writeProvenance(preferred, item)
                return item
            }
            return item.copy(localFile = "${item.digest.substringAfter(':')}-${item.sourceId}-remote.wcp")
        }
        if (item.repository != NIGHTLIES || item.sourceId != RepositorySources.source(NIGHTLIES).id) return item
        val legacy = File(packagesDir(context), safeName(item.file))
        if (!legacy.isFile || legacy == preferred || legacy.length() != item.size) return item
        if (!receiptMatches(legacy, item) && !matchesCatalogPackage(context, legacy, item)) return item
        writeProvenance(legacy, item)
        return item.copy(localFile = legacy.name)
    }

    private fun writeProvenance(file: File, item: CatalogItem) {
        runCatching {
            RepositorySources.atomicWrite(
                provenanceFile(file), provenance(item).toString().toByteArray(Charsets.UTF_8),
            )
        }.onFailure { Log.w(TAG, "component provenance ${file.name}", it) }
    }

    private fun matchesCatalogPackage(context: Context, file: File, item: CatalogItem): Boolean = runCatching {
        file.length() == item.size && Hashes.sha256(file).equals(item.digest.substringAfter(':'), true) &&
            packageInfo(context, file).comp == item.comp
    }.getOrDefault(false)

    private fun receiptMatches(file: File, item: CatalogItem): Boolean = runCatching {
        if (file.length() != item.size) return@runCatching false
        val receiptFile = provenanceFile(file)
        if (!receiptFile.isFile || receiptFile.length() !in 1..64L * 1024) return@runCatching false
        val receipt = AtomicFile(receiptFile).openRead().use {
            JSONObject(RepositorySources.readBounded(it, 64 * 1024).toString(Charsets.UTF_8))
        }
        receipt.getInt("schema") == 1 && receipt.getString("scope") == sourceScope(item.comp) &&
            receipt.getString("sourceId") == item.sourceId && receipt.getString("repository") == item.repository &&
            receipt.getLong("repositoryId") == item.repositoryId && receipt.getLong("releaseId") == item.releaseId &&
            receipt.getString("tag") == item.release && receipt.getLong("assetId") == item.assetId &&
            receipt.getString("name") == item.file && receipt.getLong("size") == item.size &&
            receipt.getString("sha256") == item.digest.substringAfter(':')
    }.getOrDefault(false)

    private fun provenance(item: CatalogItem): JSONObject = JSONObject().put("schema", 1)
        .put("scope", sourceScope(item.comp)).put("sourceId", item.sourceId)
        .put("repository", item.repository).put("repositoryId", item.repositoryId)
        .put("releaseId", item.releaseId).put("tag", item.release).put("assetId", item.assetId)
        .put("name", item.file).put("size", item.size).put("sha256", item.digest.substringAfter(':'))

    private fun <T> newestFirst(of: (T) -> String): Comparator<T> =
        Comparator { a, b -> ProtonExtras.compareVersions(of(b), of(a)) }

    internal data class ForcedPackage(
        val families: Set<GpuInfo.Family>,
        val comp: String,
        val file: String,
        val release: String,
        val models: Set<Int> = emptySet(),
    ) {
        fun matches(gpu: GpuInfo) = gpu.family in families && (models.isEmpty() || gpu.model in models)
    }

    internal val FORCED_PACKAGES = listOf(
        ForcedPackage(setOf(GpuInfo.Family.A6XX), "dxvk", "dxvk-2.7.1-linux.wcp", "Dxvk-Linux"),
    )

    internal fun forcedPackageWanted(activeFile: String?, protonVersionMatches: Boolean): Boolean =
        activeFile.isNullOrEmpty() || !protonVersionMatches

    internal fun catalogMatch(items: List<CatalogItem>, file: String, release: String): CatalogItem? =
        items.firstOrNull { it.file == file && it.release == release && it.repository == NIGHTLIES }

    fun ensureForcedPackages(context: Context): String? = ensureForcedPackages(
        context, GpuInfo.detect(),
        { file, release -> catalogItem(context, file, release) },
        { download(context, it) {} },
        { id, file -> swap(context, id, file) },
    )

    internal fun ensureForcedPackages(
        context: Context,
        gpu: GpuInfo,
        lookup: (file: String, release: String) -> CatalogItem?,
        fetch: (CatalogItem) -> Unit,
        apply: (protonId: String, file: String) -> String,
    ): String? {
        val rules = FORCED_PACKAGES.filter { it.matches(gpu) }
        if (rules.isEmpty()) return null
        val installed = protons(context)
        val lines = mutableListOf<String>()
        for (rule in rules) {
            val state = loadState(context)
            val targets = installed.filter { p ->
                val active = state.optJSONObject("active")?.optJSONObject(p.id)?.optJSONObject(rule.comp)
                forcedPackageWanted(active?.optString("file"), active?.optString("protonVersion") == p.version)
            }
            if (targets.isEmpty()) continue
            val missing = missingPackage(context, rule, lookup, fetch)
            if (missing != null) {
                lines.add(missing)
                continue
            }
            lines.add(targets.joinToString("; ") { apply(it.id, rule.file) })
        }
        return lines.joinToString("; ").ifEmpty { null }
    }

    private fun missingPackage(
        context: Context,
        rule: ForcedPackage,
        lookup: (file: String, release: String) -> CatalogItem?,
        fetch: (CatalogItem) -> Unit,
    ): String? {
        val wcp = File(packagesDir(context), safeName(rule.file))
        if (wcp.isFile && runCatching { packageInfo(context, wcp) }.isSuccess) return null
        wcp.delete()
        provenanceFile(wcp).delete()
        val item = lookup(rule.file, rule.release)
            ?: return "${rule.file} is not in the Nightlies listing; ${LABEL.getValue(rule.comp)} stays on the Proton's copy"
        fetch(item)
        return null
    }

    internal fun catalogItem(context: Context, file: String, release: String): CatalogItem? =
        resolveCatalogItem(file, release, { catalog(context, false).items }, { catalog(context, true).items })

    internal fun resolveCatalogItem(
        file: String,
        release: String,
        cached: () -> List<CatalogItem>,
        refresh: () -> List<CatalogItem>,
    ): CatalogItem? = catalogMatch(cached(), file, release) ?: catalogMatch(refresh(), file, release)

    internal fun commitDownloadedPackage(
        context: Context,
        item: CatalogItem,
        generation: Long?,
        commit: () -> Unit,
    ): Unit = synchronized(lock) {
        if (generation == null) {
            require(item.sourceId.isEmpty()) { "Qualified component download has no source generation" }
            commit()
        } else RepositorySources.whileCurrent(
            context, sourceScope(item.comp), sourceBuiltIns(item.comp), item.sourceId, generation,
        ) { commit() }
    }

    fun download(context: Context, item: CatalogItem, progress: (Int) -> Unit): Package {
        val repository = RepositorySources.canonicalRepository(item.repository)
        val legacyUnbound = repository == NIGHTLIES &&
            item.sourceId == RepositorySources.source(NIGHTLIES).id && item.repositoryId == 0L
        val generation = if (item.sourceId.isEmpty()) null else {
            val scope = sourceScope(item.comp)
            val builtIns = sourceBuiltIns(item.comp)
            val value = RepositorySources.generation(context, scope, builtIns)
            require(RepositorySources.visible(context, scope, builtIns).any {
                it.id == item.sourceId && it.repository == repository &&
                    (it.repositoryId == item.repositoryId || (legacyUnbound && it.repositoryId == null))
            } && RepositorySources.isCurrent(context, scope, builtIns, item.sourceId, value)) {
                "Component source is hidden, unavailable, or does not match its catalog"
            }
            value
        }
        require(releaseUrlMatches(repository, item.release, item.file, item.url)) {
            context.getString(R.string.cmgr_nightlies_only)
        }
        require((item.sourceId.isNotEmpty() && !legacyUnbound) || allowedDownload(item.url)) {
            context.getString(R.string.cmgr_nightlies_only)
        }
        require(Hashes.isGithubSha256(item.digest)) { context.getString(R.string.cmgr_list_predates_checksums) }
        require(item.size in 1..(2L * 1024 * 1024 * 1024)) { "Component archive has an invalid size" }
        val dest = File(packagesDir(context), safeName(item.localFile))
        val part = File(dest.parentFile, dest.name + ".part")
        check(packageDownloader(item.url, part, item.digest.substringAfter(':'), progress)) {
            context.getString(R.string.user_apps_download_failed)
        }
        if (part.length() != item.size) { part.delete(); error("Component archive size does not match") }
        if (!Hashes.sha256(part).equals(item.digest.substringAfter(':'), true)) { part.delete(); error(context.getString(R.string.cmgr_checksum_mismatch)) }
        if (generation != null && !RepositorySources.isCurrent(
                context, sourceScope(item.comp), sourceBuiltIns(item.comp), item.sourceId, generation,
            )) {
            part.delete()
            error("Component source changed while downloading")
        }
        val info = runCatching { packageInfo(context, part) }.getOrElse { part.delete(); throw it }
        require(info.comp == item.comp) { part.delete(); "Component archive type does not match its catalog" }
        try {
            commitDownloadedPackage(context, item, generation) {
                storePackage(context, part, dest)
                if (item.sourceId.isNotEmpty()) {
                    try {
                        RepositorySources.atomicWrite(
                            provenanceFile(dest), provenance(item).toString().toByteArray(Charsets.UTF_8),
                        )
                    } catch (error: Throwable) {
                        dest.delete()
                        throw error
                    }
                }
            }
        } catch (error: IOException) {
            part.delete()
            throw error
        }
        return info.copy(file = dest.name, source = item.repository)
    }

    private fun releaseUrlMatches(repository: String, tag: String, name: String, url: String): Boolean =
        GitHubArtifacts.releaseAssetUrlMatches(repository, tag, name, url)
}
