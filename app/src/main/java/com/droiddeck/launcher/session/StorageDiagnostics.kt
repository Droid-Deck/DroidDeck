package com.droiddeck.launcher.session

import android.os.StatFs
import android.system.Os
import android.util.Log
import java.io.File

/** The opt-in snapshot written beside a session's other logs. */
object StorageDiagnostics {
    private const val TAG = "StorageDiagnostics"

    /**
     * Records facts about the library actually selected for this session and returns its device
     * number for the preload shim. The path and volume ID stay out of the shared log.
     */
    fun writeSnapshot(
        sessionDir: File,
        library: File,
        selectedLibrary: String,
        removable: Boolean,
    ): Long? {
        val device = runCatching { Os.stat(library.absolutePath).st_dev }.getOrNull()
        val text = buildString {
            appendLine("DroidDeck storage diagnostics v1")
            appendLine("selected_library=$selectedLibrary")
            appendLine("volume_removable=$removable")
            appendLine("filesystem=${filesystemType(library) ?: "unknown"}")
            runCatching { StatFs(library.path) }.onSuccess { fs ->
                appendLine("block_size_bytes=${fs.blockSizeLong}")
                appendLine("total_bytes=${fs.totalBytes}")
                appendLine("available_bytes=${fs.availableBytes}")
            }.onFailure { appendLine("capacity_error=${it.javaClass.simpleName}") }
            appendLine("native_observation=${if (device != null) "enabled" else "unavailable"}")
            appendLine("public_symbol_interposition=${if (device != null) "enabled" else "unavailable"}")
            appendLine("direct_syscalls_and_libc_internal_calls=not_observed")
            appendLine("pwrite_timing=sampled_1_in_32")
            appendLine("per_process_allocation_detail_limit=64")
            appendLine("storage_log_limit_bytes=524288")
        }
        return try {
            File(sessionDir, "storage.log").writeText(text)
            device
        } catch (e: Exception) {
            Log.w(TAG, "could not write storage diagnostic snapshot", e)
            null
        }
    }

    /** The longest matching mount entry determines the filesystem for nested bindable folders. */
    private fun filesystemType(path: File): String? = runCatching {
        val canonical = path.canonicalPath
        File("/proc/mounts").useLines { lines ->
            lines.mapNotNull { line ->
                val fields = line.split(Regex("\\s+"))
                if (fields.size < 3) null else {
                    val mount = unescapeMount(fields[1]).trimEnd('/').ifEmpty { "/" }
                    val matches = canonical == mount ||
                        (mount == "/" && canonical.startsWith('/')) || canonical.startsWith("$mount/")
                    if (matches) mount.length to fields[2] else null
                }
            }.maxByOrNull { it.first }?.second?.takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) }
        }
    }.getOrNull()

    private fun unescapeMount(value: String): String = value
        .replace("\\040", " ")
        .replace("\\011", "\t")
        .replace("\\134", "\\")
}
