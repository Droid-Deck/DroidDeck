package com.droiddeck.launcher.storage

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.files.FileOps
import com.droiddeck.launcher.runtime.FlatpakManager
import com.droiddeck.launcher.session.ComponentsManager
import com.droiddeck.launcher.session.SessionState

enum class CleanItem { OLD_TOOLS, LEFTOVERS, LEFTOVERS_UNSURE, LOGS, UNUSED_CUSTOM, UNUSED_COMPONENTS, SHADERS }

object StorageState {
    private const val TAG = "StorageState"
    const val LOW_SPACE_BYTES = 10L * 1024 * 1024 * 1024
    private const val FRESH_MS = 5L * 60 * 1000

    var report by mutableStateOf<StorageReport?>(null)
        private set
    var scanning by mutableStateOf(false)
        private set
    var cleaning by mutableStateOf(false)
        private set
    var lastFreed by mutableStateOf<Long?>(null)
    var progress by mutableStateOf<String?>(null)
        private set
    var lastError by mutableStateOf<String?>(null)

    var openRequests by mutableIntStateOf(0)
        private set
    var openPending = false

    @Volatile var lowSpaceDismissed = false

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var appContext: Context? = null

    fun requestOpen() {
        openPending = true
        openRequests++
    }

    fun refresh(context: Context, force: Boolean = false) {
        val current = report
        if (scanning || cleaning) return
        if (!force && current != null && System.currentTimeMillis() - current.at < FRESH_MS) return
        val app = context.applicationContext
        scanning = true
        Thread({
            val result = runCatching { StorageScan.scan(app) }
                .onFailure { Log.w(TAG, "storage scan failed", it) }.getOrNull()
            main.post {
                if (result != null) report = result
                scanning = false
            }
        }, "storage-scan").start()
    }

    fun freeBytes(context: Context): Long = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    fun lowOnSpace(context: Context): Boolean = freeBytes(context) < LOW_SPACE_BYTES

    fun bytes(report: StorageReport, item: CleanItem): Long = when (item) {
        CleanItem.OLD_TOOLS -> report.unusedTools.filter { it.reason != ToolReason.CUSTOM }.sumOf { it.bytes }
        CleanItem.LEFTOVERS -> report.leftovers.filter { it.safe }.sumOf { it.bytes }
        CleanItem.LEFTOVERS_UNSURE -> report.leftovers.filter { !it.safe }.sumOf { it.bytes }
        CleanItem.LOGS -> report.logBytes
        CleanItem.UNUSED_CUSTOM -> report.unusedTools.filter { it.reason == ToolReason.CUSTOM }.sumOf { it.bytes }
        CleanItem.UNUSED_COMPONENTS -> report.unusedComponents.sumOf { it.bytes }
        CleanItem.SHADERS -> report.installedShaders
    }

    val SAFE = setOf(CleanItem.OLD_TOOLS, CleanItem.LEFTOVERS, CleanItem.LOGS)

    fun safeBytes(report: StorageReport): Long = SAFE.sumOf { bytes(report, it) }

    fun clean(context: Context, items: Set<CleanItem>, onDone: (Long?) -> Unit) {
        if (cleaning || SessionState.running) { onDone(null); return }
        val app = context.applicationContext
        appContext = app
        cleaning = true
        Thread({
            val before = freeBytes(app)
            val fresh = runCatching { StorageScan.scan(app) }.onFailure { Log.w(TAG, "storage scan before cleanup failed", it) }.getOrNull()
            if (fresh != null && !SessionState.running) {
                for (item in items) runCatching { cleanItem(fresh, item) }.onFailure { Log.w(TAG, "could not clean $item", it) }
            }
            val freed = if (fresh == null) null else (freeBytes(app) - before).coerceAtLeast(0)
            Log.i(TAG, "cleaned ${items.joinToString()} and freed $freed bytes")
            finish(app, freed, onDone)
        }, "storage-clean").start()
    }

    fun removeTool(context: Context, tool: ToolUsage, onDone: (Long?) -> Unit) {
        if (tool.state != ToolState.UNUSED || cleaning || SessionState.running) { onDone(null); return }
        val app = context.applicationContext
        cleaning = true
        Thread({
            val before = freeBytes(app)
            val current = runCatching { StorageScan.scan(app) }.getOrNull()?.tools?.firstOrNull { it.key == tool.key }
            val freed = if (current?.state == ToolState.UNUSED && !SessionState.running) {
                runCatching { deleteTool(current) }.onFailure { Log.w(TAG, "could not remove ${tool.key}", it) }
                (freeBytes(app) - before).coerceAtLeast(0)
            } else {
                Log.w(TAG, "kept ${tool.key}: it is in use now or could not be checked")
                null
            }
            finish(app, freed, onDone)
        }, "storage-remove-tool").start()
    }

    // Every action works from a fresh scan so nothing is deleted on the strength of an older one.
    private fun act(context: Context, name: String, action: (Context, StorageReport) -> Boolean, onDone: (Long?) -> Unit = {}) {
        if (cleaning || SessionState.running) { onDone(null); return }
        val app = context.applicationContext
        appContext = app
        cleaning = true
        lastError = null
        Thread({
            val before = freeBytes(app)
            val fresh = runCatching { StorageScan.scan(app) }.getOrNull()
            val done = fresh != null && !SessionState.running &&
                runCatching { action(app, fresh) }.onFailure { Log.w(TAG, "$name failed", it); main.post { lastError = it.message } }.getOrDefault(false)
            val freed = if (done) (freeBytes(app) - before).coerceAtLeast(0) else null
            Log.i(TAG, "$name: freed $freed bytes")
            main.post { progress = null }
            finish(app, freed, onDone)
        }, "storage-$name").start()
    }

    // A game can be installed in both Steam clients, and an id can be left over in several libraries.
    fun clearShaders(context: Context, appId: Int, x64: Boolean = false) = act(context, "shaders-$appId", { _, fresh ->
        val dir = fresh.games.firstOrNull { it.appId == appId && it.x64 == x64 }?.shaderDir ?: return@act false
        FileOps.delete(dir)
    })

    fun resetPrefix(context: Context, game: GameUsage) = act(context, "prefix-${game.appId}", { _, fresh ->
        val dir = fresh.games.firstOrNull { it.appId == game.appId && it.x64 == game.x64 && it.prefixDir?.path == game.prefixDir?.path }
            ?.prefixDir ?: error("prefix not found")
        check(FileOps.delete(dir)) { "cannot delete $dir" }
        true
    })

    fun removeLeftover(context: Context, l: Leftover) = act(context, "leftover-${l.appId}", { _, fresh ->
        val leftover = fresh.leftovers.firstOrNull { it.appId == l.appId && it.prefix?.path == l.prefix?.path && it.shaders?.path == l.shaders?.path }
            ?: return@act false
        deleteLeftover(leftover)
        true
    })

    fun removeComponent(context: Context, usage: ComponentUsage) = act(context, "component", { app, fresh ->
        val current = fresh.components.firstOrNull { it.packageFile == usage.packageFile && it.protonId == usage.protonId &&
            it.comp == usage.comp && it.version == usage.version } ?: return@act false
        !current.inUse && deleteComponent(app, current)
    })

    fun uninstallFlatpak(context: Context, id: String) = act(context, "flatpak-$id", { app, fresh ->
        if (fresh.flatpakApps.none { it.id == id }) return@act false
        val error = FlatpakManager.uninstall(app, id) { step, _ -> main.post { progress = step } }
        if (error != null) main.post { lastError = error }
        error == null
    })

    fun pruneFlatpak(context: Context) = act(context, "flatpak-prune", { app, _ ->
        val error = FlatpakManager.prune(app) { step, _ -> main.post { progress = step } }
        if (error != null) main.post { lastError = error }
        error == null
    })

    private fun deleteComponent(app: Context, usage: ComponentUsage): Boolean {
        if (usage.inUse) return false
        usage.packageFile?.let { file ->
            return runCatching { ComponentsManager.deletePackage(app, file) }.onFailure { Log.w(TAG, "kept $file", it) }.isSuccess
        }
        val proton = usage.protonId ?: return false
        return ComponentsManager.deleteUnusedOriginal(app, proton, usage.comp, usage.version)
    }

    private fun finish(app: Context, freed: Long?, onDone: (Long?) -> Unit) {
        val rescanned = runCatching { StorageScan.scan(app) }.getOrNull()
        main.post {
            if (rescanned != null) report = rescanned
            cleaning = false
            lastFreed = freed
            onDone(freed)
        }
    }

    private fun cleanItem(report: StorageReport, item: CleanItem) {
        when (item) {
            CleanItem.OLD_TOOLS -> report.unusedTools.filter { it.reason != ToolReason.CUSTOM }.forEach(::deleteTool)
            CleanItem.UNUSED_CUSTOM -> report.unusedTools.filter { it.reason == ToolReason.CUSTOM }.forEach(::deleteTool)
            CleanItem.UNUSED_COMPONENTS -> report.unusedComponents.forEach { deleteComponent(appContext!!, it) }
            CleanItem.LEFTOVERS -> report.leftovers.filter { it.safe }.forEach(::deleteLeftover)
            CleanItem.LEFTOVERS_UNSURE -> report.leftovers.filter { !it.safe }.forEach(::deleteLeftover)
            CleanItem.LOGS -> report.logs.forEach { FileOps.delete(it) }
            CleanItem.SHADERS -> report.games.mapNotNull { it.shaderDir }.forEach { FileOps.delete(it) }
        }
    }

    // Steam treats an app as installed while its manifest exists, so the manifest goes first.
    private fun deleteTool(tool: ToolUsage) {
        tool.manifests.forEach { check(!it.exists() || it.delete()) { "cannot delete $it" } }
        tool.dirs.forEach { FileOps.delete(it) }
    }

    private fun deleteLeftover(leftover: Leftover) {
        leftover.prefix?.let { FileOps.delete(it) }
        leftover.shaders?.let { FileOps.delete(it) }
    }

    fun format(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024 * 1024)
        return when {
            gb >= 100 -> String.format(java.util.Locale.US, "%.0f GB", gb)
            gb >= 10 -> String.format(java.util.Locale.US, "%.1f GB", gb)
            gb >= 0.995 -> String.format(java.util.Locale.US, "%.2f GB", gb)
            bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.0f MB", bytes / (1024.0 * 1024))
            else -> "0 MB"
        }
    }
}
