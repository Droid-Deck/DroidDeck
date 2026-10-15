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
import com.droiddeck.launcher.session.SessionState

enum class CleanItem { OLD_TOOLS, LEFTOVERS, LEFTOVERS_UNSURE, LOGS, UNUSED_CUSTOM, SHADERS }

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

    var openRequests by mutableIntStateOf(0)
        private set
    var openPending = false

    @Volatile var lowSpaceDismissed = false

    private val main = Handler(Looper.getMainLooper())

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
        CleanItem.SHADERS -> report.installedShaders
    }

    val SAFE = setOf(CleanItem.OLD_TOOLS, CleanItem.LEFTOVERS, CleanItem.LOGS)

    fun safeBytes(report: StorageReport): Long = SAFE.sumOf { bytes(report, it) }

    fun clean(context: Context, items: Set<CleanItem>, onDone: (Long?) -> Unit) {
        val current = report
        if (current == null || cleaning || SessionState.running) { onDone(null); return }
        val app = context.applicationContext
        cleaning = true
        Thread({
            val before = freeBytes(app)
            for (item in items) runCatching { cleanItem(current, item) }.onFailure { Log.w(TAG, "could not clean $item", it) }
            val freed = (freeBytes(app) - before).coerceAtLeast(0)
            Log.i(TAG, "cleaned ${items.joinToString()} and freed $freed bytes")
            val rescanned = runCatching { StorageScan.scan(app) }.getOrNull()
            main.post {
                if (rescanned != null) report = rescanned
                cleaning = false
                lastFreed = freed
                onDone(freed)
            }
        }, "storage-clean").start()
    }

    fun removeTool(context: Context, tool: ToolUsage, onDone: (Long?) -> Unit) {
        if (tool.state != ToolState.UNUSED) { onDone(null); return }
        val current = report ?: run { onDone(null); return }
        if (cleaning || SessionState.running) { onDone(null); return }
        val app = context.applicationContext
        cleaning = true
        Thread({
            val before = freeBytes(app)
            runCatching { deleteTool(tool) }.onFailure { Log.w(TAG, "could not remove ${tool.key}", it) }
            val freed = (freeBytes(app) - before).coerceAtLeast(0)
            Log.i(TAG, "removed ${tool.key} and freed $freed bytes")
            val rescanned = runCatching { StorageScan.scan(app) }.getOrNull()
            main.post {
                report = rescanned ?: current
                cleaning = false
                lastFreed = freed
                onDone(freed)
            }
        }, "storage-remove-tool").start()
    }

    private fun cleanItem(report: StorageReport, item: CleanItem) {
        when (item) {
            CleanItem.OLD_TOOLS -> report.unusedTools.filter { it.reason != ToolReason.CUSTOM }.forEach(::deleteTool)
            CleanItem.UNUSED_CUSTOM -> report.unusedTools.filter { it.reason == ToolReason.CUSTOM }.forEach(::deleteTool)
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
