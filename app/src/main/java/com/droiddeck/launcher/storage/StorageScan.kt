package com.droiddeck.launcher.storage

import android.content.Context
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.runtime.LinuxRuntime
import android.util.Log
import com.droiddeck.launcher.runtime.FlatpakManager
import com.droiddeck.launcher.session.ComponentsManager
import com.droiddeck.launcher.session.SecondaryLibrary
import com.droiddeck.launcher.session.SessionPaths
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File

enum class Bucket { GAMES, GAME_DATA, TOOLS, COMPONENTS, STORES, DESKTOP, SYSTEM, DROIDDECK }

class GameUsage(
    val appId: Int, val name: String, val art: File?, val library: String, val internal: Boolean, val lastPlayed: Long,
    val game: Long, val prefix: Long, val shaders: Long, val workshop: Long, val shaderDir: File?,
    val x64: Boolean = false, val prefixDir: File? = null,
) {
    val data: Long get() = prefix + shaders + workshop
    val total: Long get() = game + data
}

enum class ToolState { IN_USE, REQUIRED, UNUSED }
enum class ToolReason { DEFAULT_PROTON, PROTON, FEX_RUNTIME, ARM_RUNTIME, REDISTRIBUTABLES, FEX, STEAM_TOOL, OLD_RUNTIME, X86_PROTON, CUSTOM, CUSTOM_UNKNOWN, X64_CLIENT }

class ToolUsage(
    val key: String, val name: String, val bytes: Long, val state: ToolState, val reason: ToolReason,
    val games: Int, val isDefault: Boolean, val manifests: List<File>, val dirs: List<File>,
)

class Leftover(val appId: Int, val prefix: File?, val shaders: File?, val bytes: Long, val safe: Boolean)

class ComponentUsage(
    val comp: String, val version: String, val bytes: Long, val inUse: Boolean,
    val packageFile: String? = null, val protonId: String? = null, val protonName: String? = null,
)

class FlatpakUsage(val id: String, val name: String, val bytes: Long)

class StorageReport(
    val at: Long,
    val total: Long,
    val free: Long,
    val buckets: Map<Bucket, Long>,
    val prefixes: Long,
    val shaders: Long,
    val workshop: Long,
    val flatpak: Long,
    val games: List<GameUsage>,
    val tools: List<ToolUsage>,
    val leftovers: List<Leftover>,
    val logs: List<File>,
    val logBytes: Long,
    val components: List<ComponentUsage>,
    val flatpakApps: List<FlatpakUsage>,
    val flatpakRuntimes: Long,
    val otherPrefixes: Long,
    val leftoversChecked: Boolean,
) {
    val droiddeck: Long get() = buckets.values.sum()
    val installedShaders: Long get() = games.sumOf { it.shaders }
    val unusedTools: List<ToolUsage> get() = tools.filter { it.state == ToolState.UNUSED }
    val gamesOnSd: Int get() = games.count { !it.internal }
    val unusedComponents: List<ComponentUsage> get() = components.filter { !it.inUse }
    val flatpakCache: Long get() = (flatpak - flatpakApps.sumOf { it.bytes } - flatpakRuntimes).coerceAtLeast(0)
}

object StorageScan {
    private class Walker {
        private val seen = HashSet<Long>()
        val known = HashMap<String, Long>()

        fun size(file: File): Long {
            known[file.path]?.let { return it }
            val st = try { Os.lstat(file.path) } catch (e: Exception) { return 0 }
            if (st.st_nlink > 1 && !OsConstants.S_ISDIR(st.st_mode)) {
                if (!seen.add(st.st_ino xor (st.st_dev shl 40))) return 0
            }
            var total = st.st_blocks * 512
            if (OsConstants.S_ISDIR(st.st_mode)) file.list()?.forEach { total += size(File(file, it)) }
            return total
        }

        fun measure(file: File): Long = size(file).also { known[file.path] = it }
    }

    // Proton tools' files live only in the arm64 client's root; the x86-64 client has wrappers of them.
    fun steamRoot(context: Context) = SessionPrefs.steamRoot(context, "arm64")

    fun scan(context: Context): StorageReport {
        val walker = Walker()
        val files = context.filesDir
        val rootfs = LinuxRuntime.rootDir(context)
        val steamRoot = steamRoot(context)
        val x64Root = SessionPrefs.steamRoot(context, "x86_64").takeIf { SteamStorage.x64ClientPresent(it) }
        val clients = listOfNotNull("arm64", "x86_64".takeIf { x64Root != null })
        fun canonical(f: File) = runCatching { f.canonicalPath }.getOrDefault(f.path)
        val art = clients.associate { arch ->
            (arch == "x86_64") to runCatching { Library.steamGames(context, arch = arch) }.getOrDefault(emptyList()).associateBy { it.appId }
        }

        class Lib(val root: File, val label: String, val internal: Boolean, val data: File, val listing: SteamStorage.Listing?, val x64: Boolean) {
            val manifests: List<SteamStorage.Manifest>? get() = listing?.manifests
        }
        val libs = clients.flatMap { arch ->
            val x64 = arch == "x86_64"
            val primary = canonical(SessionPrefs.steamRoot(context, arch))
            Library.steamLibraries(context, arch).map { (root, label) ->
                val internal = canonical(root) == primary
                val data = if (internal) root else SecondaryLibrary.privateRoot(files, root)
                // The x86-64 card folder and its steamapps appear only with that client's first session and library write.
                val listing = SteamStorage.manifests(root)
                    ?: SteamStorage.Listing(emptyList(), emptySet(), true).takeIf {
                        x64 && (if (internal) !File(root, "steamapps").exists() else !root.exists() && root.parentFile?.isDirectory == true)
                    }
                Lib(root, label, internal, data, listing, x64)
            }
        }
        val everyLibraryRead = libs.all { it.listing?.complete == true }
        val clientIds = libs.groupBy { it.x64 }.mapValues { (_, own) -> own.flatMap { it.listing?.ids.orEmpty() }.toSet() }
        fun installedGames(x64: Boolean) = libs.filter { it.x64 == x64 }.flatMap { it.manifests.orEmpty() }
            .filter { !SteamStorage.isTool(it) && it.installed }.map { it.appId.toString() }.toSet()
        val armUse = runCatching { SteamStorage.compatMappings(File(steamRoot, "config/config.vdf").readText()) }.getOrNull()
            ?.let { SteamStorage.ToolUse(it, installedGames(false)) }
        // An x86-64 client without a config.vdf has never mapped a game to anything.
        val x64Vdf = x64Root?.let { File(it, "config/config.vdf") }
        val x64Use = if (x64Vdf == null || !x64Vdf.exists()) SteamStorage.ToolUse(emptyMap(), emptySet(), x64 = true)
            else runCatching { SteamStorage.ToolUse(SteamStorage.compatMappings(x64Vdf.readText()), installedGames(true), x64 = true) }.getOrNull()
        val mappingsRead = armUse != null && x64Use != null
        val uses = listOfNotNull(armUse, x64Use)
        fun gamesOn(tool: String) = uses.sumOf { it.games(tool) }

        val tools = LinkedHashMap<String, ToolUsage>()
        val games = ArrayList<GameUsage>()
        for (lib in libs) {
            for (m in lib.manifests.orEmpty()) {
                val dir = m.installDir
                if (SteamStorage.isTool(m)) {
                    val dirs = listOfNotNull(
                        dir?.let { File(lib.root, "steamapps/common/$it") },
                        dir?.let { File(lib.data, "steamapps/common/$it") }.takeIf { !lib.internal },
                    ).filter { it.exists() }
                    val bytes = dirs.sumOf { walker.measure(it) }
                    val kind = SteamStorage.kind(m)
                    val (state, reason) = if (lib.x64) ToolState.REQUIRED to ToolReason.X64_CLIENT else when (kind) {
                        SteamStorage.Kind.OLD_RUNTIME -> ToolState.UNUSED to ToolReason.OLD_RUNTIME
                        SteamStorage.Kind.X86_PROTON -> ToolState.UNUSED to ToolReason.X86_PROTON
                        SteamStorage.Kind.ARM_PROTON -> ToolState.IN_USE to (if (m.appId == SteamStorage.PROTON_ARM) ToolReason.DEFAULT_PROTON else ToolReason.PROTON)
                        SteamStorage.Kind.REQUIRED -> ToolState.REQUIRED to when (m.appId) {
                            SteamStorage.SNIPER -> ToolReason.FEX_RUNTIME
                            SteamStorage.ARM_RUNTIME -> ToolReason.ARM_RUNTIME
                            SteamStorage.REDISTRIBUTABLES -> ToolReason.REDISTRIBUTABLES
                            SteamStorage.FEX -> ToolReason.FEX
                            else -> ToolReason.STEAM_TOOL
                        }
                    }
                    val count = when {
                        lib.x64 -> 0
                        m.appId == SteamStorage.PROTON_ARM -> gamesOn(SteamStorage.ARM_TOOL)
                        m.appId == SteamStorage.PROTON_11_ARM -> gamesOn(SteamStorage.ARM_11_TOOL)
                        else -> 0
                    }
                    val key = if (lib.x64) "x64:app:${m.appId}" else "app:${m.appId}"
                    val prior = tools[key]
                    tools[key] = ToolUsage(
                        key, m.name.ifEmpty { dir ?: m.appId.toString() }, bytes + (prior?.bytes ?: 0), state, reason, count,
                        false, (prior?.manifests.orEmpty()) + m.file, (prior?.dirs.orEmpty()) + dirs,
                    )
                    continue
                }
                if (!m.installed) continue
                val gameBytes = dir?.let { File(lib.root, "steamapps/common/$it") }?.takeIf { it.isDirectory }?.let { walker.measure(it) } ?: 0
                val compatRoot = File(lib.data, "steamapps/compatdata").takeIf { it.isDirectory } ?: File(lib.root, "steamapps/compatdata")
                val prefixDir = File(compatRoot, m.appId.toString()).takeIf { it.isDirectory }
                val prefix = prefixDir?.let { walker.measure(it) } ?: 0
                val shaderDir = File(lib.data, "steamapps/shadercache/${m.appId}").takeIf { it.isDirectory }
                val shaders = shaderDir?.let { walker.measure(it) } ?: 0
                val workshop = File(lib.root, "steamapps/workshop/content/${m.appId}").takeIf { it.isDirectory }?.let { walker.measure(it) } ?: 0
                val known = art[lib.x64]?.get(m.appId)
                games.add(GameUsage(
                    m.appId, known?.name ?: m.name, known?.art, lib.label, lib.internal, m.lastPlayed,
                    gameBytes, prefix, shaders, workshop, shaderDir, lib.x64, prefixDir,
                ))
            }
        }

        File(steamRoot, "compatibilitytools.d").listFiles()?.sortedBy { it.name }?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val bytes = walker.measure(dir)
            if (dir.name.startsWith(".") || dir.name.startsWith("droiddeck-") || dir.name.startsWith("bannerlator-") ||
                !File(dir, "toolmanifest.vdf").isFile) return@forEach
            val name = runCatching { SteamStorage.compatToolName(File(dir, "compatibilitytool.vdf").readText()) }.getOrNull() ?: dir.name
            val count = gamesOn(name)
            val isDefault = uses.any { it.isDefault(name) }
            tools["dir:${dir.name}"] = ToolUsage(
                "dir:${dir.name}", dir.name, bytes,
                when {
                    !mappingsRead -> ToolState.REQUIRED
                    count > 0 || isDefault -> ToolState.IN_USE
                    else -> ToolState.UNUSED
                },
                if (!mappingsRead) ToolReason.CUSTOM_UNKNOWN else ToolReason.CUSTOM, count, isDefault, emptyList(), listOf(dir),
            )
        }

        val leftovers = ArrayList<Leftover>()
        for (lib in libs) {
            if (!everyLibraryRead) break
            val everyId = clientIds[lib.x64].orEmpty()
            val prefixes = File(lib.data, "steamapps/compatdata").listFiles().orEmpty()
                .mapNotNull { f -> SteamStorage.leftoverId(f.name)?.takeIf { it !in everyId && f.isDirectory }?.let { it to f } }.toMap()
            val caches = File(lib.data, "steamapps/shadercache").listFiles().orEmpty()
                .mapNotNull { f -> SteamStorage.leftoverId(f.name)?.takeIf { it !in everyId && f.isDirectory }?.let { it to f } }.toMap()
            for (id in (prefixes.keys + caches.keys).sorted()) {
                val prefix = prefixes[id]
                val cache = caches[id]
                val bytes = (prefix?.let { walker.measure(it) } ?: 0) + (cache?.let { walker.measure(it) } ?: 0)
                val safe = prefix == null || SteamStorage.isStubPrefix(prefix)
                leftovers.add(Leftover(id, prefix, cache, bytes, safe))
            }
        }

        val logs = SessionPaths.sessionFolders(context).filter { it != SessionPaths.current() } +
            listOf(File(LinuxRuntime.logDir(context), SessionPaths.TOOLS_DIR)).filter { it.exists() }
        val logBytes = logs.sumOf { walker.measure(it) }

        val flatpakDir = File(rootfs, "root/.local/share/flatpak")
        val flatpakNames = runCatching { FlatpakManager.installedApps(context) }.getOrDefault(emptyList()).associate { it.id to it.name }
        val flatpakApps = File(flatpakDir, "app").listFiles().orEmpty().filter { it.isDirectory }.map { dir ->
            FlatpakUsage(dir.name, flatpakNames[dir.name] ?: dir.name.substringAfterLast('.'), walker.measure(dir))
        }.sortedByDescending { it.bytes }
        val flatpakRuntimes = File(flatpakDir, "runtime").takeIf { it.isDirectory }?.let { walker.measure(it) } ?: 0

        val componentsDir = File(files, "components")
        val stored = runCatching { ComponentsManager.stored(context) }.onFailure { Log.w("StorageScan", "components", it) }.getOrNull()
        val components = stored?.let { st ->
            st.packages.map { ComponentUsage(it.pkg.comp, it.pkg.version, it.pkg.size, it.inUse, packageFile = it.pkg.file) } +
                st.originals.map { ComponentUsage(it.comp, it.protonVersion, it.size, it.kept, protonId = it.protonId, protonName = it.protonName) }
        }.orEmpty()

        val otherPrefixes = libs.flatMap { lib ->
            File(lib.data, "steamapps/compatdata").listFiles().orEmpty().filter { it.isDirectory && SteamStorage.leftoverId(it.name) == null }
        }.sumOf { walker.measure(it) }

        val clientRoots = listOfNotNull(steamRoot, x64Root)
        val apps = clientRoots.map { File(it, "steamapps") }
        val privateRoots = File(files, "steam-libraries").listFiles().orEmpty().filter { it.isDirectory }
        fun m(file: File): Long = if (file.exists()) walker.measure(file) else 0
        fun inApps(name: String): Long = apps.sumOf { m(File(it, name)) }
        val toolDirs = tools.values.flatMap { it.dirs }.distinctBy { it.path }
        val toolsInApps = toolDirs.filter { d -> apps.any { d.path.startsWith(it.path + "/") } }.sumOf { walker.known[it.path] ?: 0 }
        val toolsPrivate = toolDirs.filter { d -> privateRoots.any { d.path.startsWith(it.path + "/") } }.sumOf { walker.known[it.path] ?: 0 }
        val compatTools = clientRoots.sumOf { m(File(it, "compatibilitytools.d")) }
        val installs = inApps("common") + inApps("downloading") + inApps("temp")
        val privatePrefixes = privateRoots.sumOf { m(File(it, "steamapps/compatdata")) }
        val privateShaders = privateRoots.sumOf { m(File(it, "steamapps/shadercache")) }
        val prefixes = inApps("compatdata") + privatePrefixes
        val shaders = inApps("shadercache") + privateShaders
        val workshop = inApps("workshop")
        val stores = m(File(rootfs, "root/Games"))
        val flatpak = m(File(rootfs, "root/.local/share/flatpak"))
        val rootfsBytes = m(rootfs)
        val privateBytes = privateRoots.sumOf { m(it) }
        val system = rootfsBytes - installs - inApps("compatdata") - inApps("shadercache") - workshop -
            compatTools - stores - flatpak
        val componentBytes = m(componentsDir)
        val droiddeck = m(files) + m(context.cacheDir) - rootfsBytes - privateBytes - componentBytes +
            (privateBytes - toolsPrivate - privatePrefixes - privateShaders)
        val stat = StatFs(files.path)
        return StorageReport(
            System.currentTimeMillis(), stat.totalBytes, stat.availableBytes,
            mapOf(
                Bucket.GAMES to (installs - toolsInApps).coerceAtLeast(0),
                Bucket.GAME_DATA to (prefixes + shaders + workshop),
                Bucket.TOOLS to toolsInApps + toolsPrivate + compatTools,
                Bucket.COMPONENTS to componentBytes,
                Bucket.STORES to stores,
                Bucket.DESKTOP to flatpak,
                Bucket.SYSTEM to system.coerceAtLeast(0),
                Bucket.DROIDDECK to droiddeck.coerceAtLeast(0),
            ),
            prefixes, shaders, workshop, flatpak,
            games.sortedByDescending { it.total }, tools.values.toList(), leftovers, logs, logBytes,
            components, flatpakApps, flatpakRuntimes, otherPrefixes, everyLibraryRead,
        )
    }
}
