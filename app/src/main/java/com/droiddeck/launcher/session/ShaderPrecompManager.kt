package com.droiddeck.launcher.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.gpu.LinuxVulkanDriver
import com.droiddeck.launcher.gpu.SystemVulkanDriver
import com.droiddeck.launcher.runtime.GuestCommand
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object ShaderPrecompManager {
    private const val TAG = "ShaderPrecomp"

    enum class GameStatus { READY, COMPILED, NEEDS_CAPTURE, COMPILING, ERROR }

    data class GameEntry(
        val key: String,
        val appId: Int,
        val name: String,
        val shaderDir: File?,
        val fozFiles: List<File>,
        val cacheBytes: Long,
        val status: GameStatus,
        val lastError: String? = null,
    )

    data class Progress(
        val running: Boolean = false,
        val currentGameKey: String? = null,
        val currentGameName: String? = null,
        val gameIndex: Int = 0,
        val gameTotal: Int = 0,
        val archiveIndex: Int = 0,
        val archiveTotal: Int = 0,
        val pipelinesDone: Int = 0,
        val pipelinesTotal: Int = 0,
        val stage: String? = null,
        val lastError: String? = null,
    )

    data class UiState(
        val scanning: Boolean = false,
        val entries: List<GameEntry> = emptyList(),
        val replayAvailable: Boolean = false,
        val progress: Progress = Progress(),
        val statusMessage: String? = null,
    )

    var uiState by mutableStateOf(UiState())
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var worker: Thread? = null
    private var cancelFlag = AtomicBoolean(false)
    private val lastErrors = HashMap<String, String>()

    internal data class ReplayBinary(val file: File, val isArm64: Boolean)

    internal fun findFossilizeReplay(steamRoots: List<File>, rootfs: File): ReplayBinary? {
        for (root in steamRoots) {
            val arm = File(root, "steamrtarm64/fossilize_replay")
            if (arm.isFile) return ReplayBinary(arm, isArm64 = true)
        }
        val sys = File(rootfs, "usr/bin/fossilize_replay")
        if (sys.isFile) return ReplayBinary(sys, isArm64 = true)
        for (root in steamRoots) {
            for (rel in listOf("steamrt64/fossilize_replay", "ubuntu12_64/fossilize_replay")) {
                val bin = File(root, rel)
                if (bin.isFile) return ReplayBinary(bin, isArm64 = false)
            }
        }
        return null
    }

    internal fun findFozArchives(shaderDir: File): List<File> {
        val fozRoot = File(shaderDir, "fozpipelinesv6")
        if (!fozRoot.isDirectory) return emptyList()
        val found = ArrayList<File>()
        fun walk(dir: File, depth: Int) {
            for (child in dir.listFiles().orEmpty().sortedBy { it.name }) {
                if (child.isFile && child.name.endsWith(".foz") && child.length() > 0L) {
                    found.add(child)
                } else if (child.isDirectory && depth < 2) {
                    walk(child, depth + 1)
                }
            }
        }
        walk(fozRoot, 0)
        return found
    }

    internal fun mesaCacheDirs(shaderDir: File): List<File> =
        shaderDir.listFiles().orEmpty().filter {
            it.isDirectory && (it.name.startsWith("mesa_shader_cache") || it.name == "DXVK_state_cache")
        }

    internal fun dirBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            for (child in cur.listFiles().orEmpty()) {
                if (child.isFile) total += child.length()
                else if (child.isDirectory) stack.add(child)
            }
        }
        return total
    }

    internal fun resolveThreads(configured: Int, cpus: Int = Runtime.getRuntime().availableProcessors()): Int =
        if (configured > 0) configured.coerceIn(1, 16) else (cpus / 2).coerceIn(1, 4)

    internal fun buildReplayCommand(
        guestReplayPath: String,
        guestFozPath: String,
        guestPipelineCachePath: String,
        guestEnv: Map<String, String>,
        threads: Int,
        maxMemoryMb: Int,
        skipCompiled: Boolean,
        idleOnly: Boolean,
        useFex: Boolean = false,
    ): List<String> {
        val envCmd = mutableListOf("/usr/bin/env")
        for ((k, v) in guestEnv.toSortedMap()) {
            envCmd.add("$k=$v")
        }
        if (useFex) {
            envCmd.add(com.droiddeck.launcher.runtime.LinuxFex.TOOL)
        }
        envCmd.add(guestReplayPath)
        envCmd.add("--num-threads")
        envCmd.add(threads.toString())
        envCmd.add("--on-disk-pipeline-cache")
        envCmd.add(guestPipelineCachePath)
        if (maxMemoryMb > 0) {
            envCmd.add("--shader-cache-size")
            envCmd.add(maxMemoryMb.toString())
        }
        if (skipCompiled) {
            envCmd.add("--skip-pipelines-on-compile-required")
        }
        envCmd.add("--timeout-seconds")
        envCmd.add("30")
        envCmd.add("--progress")
        envCmd.add(guestFozPath)
        if (!idleOnly) return envCmd
        return listOf(
            "/bin/sh",
            "-c",
            "if command -v ionice >/dev/null 2>&1; then exec nice -n 19 ionice -c3 \"\$@\"; else exec nice -n 19 \"\$@\"; fi",
            "sh",
        ) + envCmd
    }

    private val PROGRESS_FRACTION = Regex("""(\d+)\s*/\s*(\d+)""")
    private val PROGRESS_PERCENT = Regex("""(\d{1,3})\s*%""")

    internal fun parseProgressLine(line: String): Pair<Int, Int>? {
        PROGRESS_FRACTION.find(line)?.let { m ->
            val done = m.groupValues[1].toIntOrNull() ?: return@let
            val total = m.groupValues[2].toIntOrNull() ?: return@let
            if (total > 0 && done in 0..total) return done to total
        }
        PROGRESS_PERCENT.find(line)?.let { m ->
            val pct = m.groupValues[1].toIntOrNull() ?: return@let
            if (pct in 0..100) return pct to 100
        }
        return null
    }

    internal fun activeDriverSignature(context: Context): String = listOf(
        SessionPrefs.linuxDriver(context),
        if (SessionPrefs.tuSysmem(context)) "sysmem" else "",
        if (VenusComponent.wanted(context)) "venus" else "",
        if (DeviceSupport.vkBridge(context)) "bridge" else "",
    ).joinToString("|")

    private fun steamRoots(context: Context): List<File> = listOf(
        SessionPrefs.steamRoot(context, "arm64"),
        SessionPrefs.steamRoot(context, "x86_64"),
    ).distinctBy { it.path }

    private fun shaderDirsForGame(context: Context, key: String): List<File> {
        val libs = (Library.steamLibraries(context, "arm64").map { it.first } +
            Library.steamLibraries(context, "x86_64").map { it.first } +
            steamRoots(context)).distinctBy { it.path }
        val storeRoots = com.droiddeck.launcher.stores.StoreInstallRoot.externalRoots(context).map { it.first }
        val allDirs = (libs + storeRoots).map { File(it, "steamapps/shadercache/$key") }
        return allDirs.filter { it.isDirectory }.distinctBy {
            runCatching { it.canonicalPath }.getOrDefault(it.path)
        }
    }

    fun scan(context: Context, games: List<Library.SteamGame>, currentProgress: Progress = uiState.progress): UiState {
        val rootfs = LinuxRuntime.rootDir(context)
        val replay = findFossilizeReplay(steamRoots(context), rootfs)
        val deduped = games.filter { !it.soundtrack && it.library != Library.EPIC_LAUNCHER && it.library != Library.BATTLENET_LAUNCHER }
            .distinctBy { it.profileKey }
            .sortedBy { it.name.lowercase() }
        val entries = deduped.map { game ->
            val key = game.profileKey
            val dirs = shaderDirsForGame(context, key)
            val foz = dirs.flatMap { findFozArchives(it) }
            val primaryDir = dirs.firstOrNull { findFozArchives(it).isNotEmpty() } ?: dirs.firstOrNull()
            val cacheBytes = dirs.sumOf { d -> mesaCacheDirs(d).sumOf(::dirBytes) }
            val err = lastErrors[key]
            val status = when {
                currentProgress.running && currentProgress.currentGameKey == key -> GameStatus.COMPILING
                err != null -> GameStatus.ERROR
                foz.isNotEmpty() && cacheBytes > 0L -> GameStatus.COMPILED
                foz.isNotEmpty() -> GameStatus.READY
                else -> GameStatus.NEEDS_CAPTURE
            }
            GameEntry(
                key = key,
                appId = game.appId,
                name = game.name,
                shaderDir = primaryDir,
                fozFiles = foz,
                cacheBytes = cacheBytes,
                status = status,
                lastError = err,
            )
        }
        return UiState(
            scanning = false,
            entries = entries,
            replayAvailable = replay != null,
            progress = currentProgress,
            statusMessage = uiState.statusMessage,
        )
    }

    fun refreshAsync(context: Context, games: List<Library.SteamGame>) {
        val appContext = context.applicationContext
        mainHandler.post { uiState = uiState.copy(scanning = true) }
        Thread({
            val fresh = runCatching { scan(appContext, games) }.getOrNull() ?: return@Thread
            mainHandler.post {
                uiState = fresh.copy(progress = uiState.progress, statusMessage = uiState.statusMessage)
            }
        }, "shader-precomp-scan").start()
    }

    private fun guestPath(
        context: Context,
        rootfs: File,
        hostFile: File,
        fallbackGuestPath: String,
        extraBinds: MutableList<String>,
    ): String {
        val rootPath = runCatching { rootfs.canonicalPath }.getOrDefault(rootfs.path).trimEnd('/')
        val filesPath = runCatching { context.filesDir.canonicalPath }.getOrDefault(context.filesDir.path).trimEnd('/')
        val targetPath = runCatching { hostFile.canonicalPath }.getOrDefault(hostFile.path)
        if (targetPath == rootPath) return "/"
        if (targetPath.startsWith("$rootPath/")) {
            return targetPath.removePrefix(rootPath)
        }
        if (targetPath == filesPath || targetPath.startsWith("$filesPath/")) {
            return targetPath
        }
        extraBinds.add("$targetPath:$fallbackGuestPath")
        return fallbackGuestPath
    }

    private fun buildGuestEnv(
        context: Context,
        rootfs: File,
        guestShaderDir: String,
        maxMemoryMb: Int,
        extraBinds: MutableList<String>,
    ): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        env["MESA_DISK_CACHE_DATABASE"] = "1"
        env["MESA_SHADER_CACHE_DISABLE"] = "false"
        env["MESA_SHADER_CACHE_DIR"] = guestShaderDir
        env["STEAM_COMPAT_SHADER_PATH"] = guestShaderDir
        if (maxMemoryMb > 0) {
            env["MESA_SHADER_CACHE_MAX_SIZE"] = "${maxMemoryMb}M"
        }
        val linuxDriverId = SessionPrefs.linuxDriver(context)
        val venus = VenusComponent.wanted(context)
        val bridge = DeviceSupport.vkBridge(context)
        if (!venus && !bridge) {
            val driverIcd = LinuxVulkanDriver.resolveIcdPath(context, linuxDriverId)?.let(::File)
            val icdFile = driverIcd ?: LinuxRuntime.vulkanIcd(context)
            if (icdFile != null) {
                val guestIcd = guestPath(context, rootfs, icdFile, "/mnt/dd-shader-icd/${icdFile.name}", extraBinds)
                env["VK_ICD_FILENAMES"] = guestIcd
                env["VK_DRIVER_FILES"] = guestIcd
            }
        }
        if (!venus && DeviceSupport.adreno() && SessionPrefs.tuSysmem(context)) {
            env["TU_DEBUG"] = "sysmem"
        }
        return env
    }

    fun startCompile(context: Context, games: List<Library.SteamGame>, selectedKeys: Set<String>) {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (worker?.isAlive == true) return
            cancelFlag = AtomicBoolean(false)
            val flag = cancelFlag
            val thread = Thread({
                runCompile(appContext, games, selectedKeys, flag)
            }, "shader-precomp-run")
            worker = thread
            thread.start()
        }
    }

    fun stop() {
        cancelFlag.set(true)
    }

    private fun runCompile(context: Context, games: List<Library.SteamGame>, selectedKeys: Set<String>, cancelled: AtomicBoolean) {
        val rootfs = LinuxRuntime.rootDir(context)
        val replay = findFossilizeReplay(steamRoots(context), rootfs)
        if (replay == null) {
            mainHandler.post {
                uiState = uiState.copy(
                    replayAvailable = false,
                    progress = Progress(running = false, lastError = "fossilize_replay not found in Steam runtime"),
                )
            }
            return
        }
        if (DeviceSupport.vkBridge(context) && !VenusComponent.wanted(context) && !SessionState.running) {
            mainHandler.post {
                uiState = uiState.copy(
                    progress = Progress(running = false, lastError = "Cannot pre-compile while Mali Vulkan bridge is offline"),
                )
            }
            return
        }
        val idleOnly = SessionPrefs.shaderPrecompIdleOnly(context)
        if (idleOnly && SessionState.running) {
            mainHandler.post {
                uiState = uiState.copy(
                    progress = Progress(running = false, stage = "Paused while a session is running"),
                )
            }
            return
        }

        val currentScan = scan(context, games, Progress(running = true))
        val targets = currentScan.entries.filter { it.key in selectedKeys && it.fozFiles.isNotEmpty() && it.shaderDir != null }
        if (targets.isEmpty()) {
            mainHandler.post {
                uiState = currentScan.copy(
                    progress = Progress(running = false, stage = "No selected games have Fossilize shader archives yet"),
                )
            }
            return
        }

        val threads = resolveThreads(SessionPrefs.shaderPrecompThreads(context))
        val maxMemoryMb = SessionPrefs.shaderPrecompMaxMemoryMb(context)
        val skipCompiled = SessionPrefs.shaderPrecompSkipCompiled(context)
        var anyFailed = false

        targets.forEachIndexed { index, entry ->
            if (cancelled.get() || (idleOnly && SessionState.running)) return@forEachIndexed
            val shaderDir = entry.shaderDir ?: return@forEachIndexed
            lastErrors.remove(entry.key)
            val totalArchives = entry.fozFiles.size

            entry.fozFiles.forEachIndexed archiveLoop@{ archiveIdx, fozFile ->
                if (cancelled.get() || (idleOnly && SessionState.running)) return@archiveLoop
                mainHandler.post {
                    uiState = uiState.copy(
                        progress = Progress(
                            running = true,
                            currentGameKey = entry.key,
                            currentGameName = entry.name,
                            gameIndex = index + 1,
                            gameTotal = targets.size,
                            archiveIndex = archiveIdx + 1,
                            archiveTotal = totalArchives,
                            stage = "${entry.name} (${archiveIdx + 1}/$totalArchives)",
                        ),
                    )
                }

                val extraBinds = ArrayList<String>()
                val guestReplay = guestPath(context, rootfs, replay.file, "/mnt/dd-shader-replay/fossilize_replay", extraBinds)
                val guestShaderDir = guestPath(context, rootfs, shaderDir, "/mnt/dd-shader-cache/${entry.key}", extraBinds)
                val guestFoz = guestPath(context, rootfs, fozFile, "/mnt/dd-shader-foz/${entry.key}/${fozFile.name}", extraBinds)
                val guestPipelineCache = "$guestShaderDir/mesa_shader_cache_sf/dd_pipeline_cache.bin"
                File(shaderDir, "mesa_shader_cache_sf").mkdirs()

                val guestEnv = buildGuestEnv(context, rootfs, guestShaderDir, maxMemoryMb, extraBinds)
                val argv = buildReplayCommand(
                    guestReplayPath = guestReplay,
                    guestFozPath = guestFoz,
                    guestPipelineCachePath = guestPipelineCache,
                    guestEnv = guestEnv,
                    threads = threads,
                    maxMemoryMb = maxMemoryMb,
                    skipCompiled = skipCompiled,
                    idleOnly = idleOnly,
                    useFex = !replay.isArm64,
                )

                var lastLine = ""
                val rc = GuestCommand.run(
                    context = context,
                    argv = argv,
                    logName = "shader-precomp-${entry.key}",
                    extraBinds = extraBinds.takeIf { it.isNotEmpty() },
                    cancelled = cancelled,
                ) { line ->
                    if (line.isNotBlank()) lastLine = line.trim()
                    parseProgressLine(line)?.let { (done, total) ->
                        mainHandler.post {
                            val p = uiState.progress
                            if (p.running && p.currentGameKey == entry.key) {
                                uiState = uiState.copy(progress = p.copy(pipelinesDone = done, pipelinesTotal = total))
                            }
                        }
                    }
                }
                if (rc != 0 && !cancelled.get()) {
                    anyFailed = true
                    val msg = lastLine.ifEmpty { "fossilize_replay exited with code $rc" }
                    lastErrors[entry.key] = msg
                    Log.w(TAG, "${entry.name} (${fozFile.name}) failed ($rc): $msg")
                }
            }
        }

        if (!cancelled.get() && !anyFailed) {
            SessionPrefs.setShaderPrecompLastDriver(context, activeDriverSignature(context))
        }
        val finalScan = scan(context, games, Progress(running = false, stage = if (cancelled.get()) "Stopped" else if (anyFailed) "Completed with errors" else "Completed"))
        mainHandler.post {
            uiState = finalScan
        }
    }

    fun clearCaches(context: Context, games: List<Library.SteamGame>, selectedKeys: Set<String>, onDone: (Int, Long) -> Unit) {
        val appContext = context.applicationContext
        Thread({
            var clearedGames = 0
            var freedBytes = 0L
            for (key in selectedKeys) {
                var removedAny = false
                for (dir in shaderDirsForGame(appContext, key)) {
                    for (cache in mesaCacheDirs(dir)) {
                        val size = dirBytes(cache)
                        if (cache.deleteRecursively()) {
                            freedBytes += size
                            removedAny = true
                        }
                    }
                }
                lastErrors.remove(key)
                if (removedAny) clearedGames++
            }
            val fresh = scan(appContext, games, uiState.progress)
            mainHandler.post {
                uiState = fresh
                onDone(clearedGames, freedBytes)
            }
        }, "shader-precomp-clear").start()
    }

    fun maybeAutoRebuildOnDriverChange(context: Context, games: List<Library.SteamGame>) {
        if (!SessionPrefs.shaderPrecompEnabled(context) || !SessionPrefs.shaderPrecompAutoDriverRebuild(context)) return
        if (SessionPrefs.shaderPrecompIdleOnly(context) && SessionState.running) return
        val selected = SessionPrefs.shaderPrecompGames(context)
        if (selected.isEmpty()) return
        val current = activeDriverSignature(context)
        val previous = SessionPrefs.shaderPrecompLastDriver(context)
        if (previous.isEmpty()) {
            SessionPrefs.setShaderPrecompLastDriver(context, current)
            return
        }
        if (previous != current) {
            startCompile(context, games, selected)
        }
    }
}
