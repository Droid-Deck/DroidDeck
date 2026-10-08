// JNI symbols depend on this package path and class name (app/src/main/rust/stores/JNI.md).
package com.droiddeck.launcher.stores.gog

import android.util.Log
import com.droiddeck.launcher.stores.StoresNative

/**
 * The native GOG download engine in `libdroiddeckstores.so`: the gen2 chunk loop (depot manifests
 * → chunk fetch + inflate + MD5 → assembled files) and the gen1 range loop. One [start] is one
 * download loop; the manager owns everything before and after it. Method names and signatures
 * are Bannerlator's `BlGogDownload`, only the package and class changed.
 */
object GogNative {
    private const val TAG = "GogNative"

    /** gen2: depot manifests → chunk fetch + inflate + MD5 (base install, DLC, dependencies). */
    const val KIND_GEN2_CHUNKS = 0

    /** gen1: build manifest → per-file HTTP Range GET streamed to disk. */
    const val KIND_GEN1_RANGES = 1

    /** Callbacks from the engine; every method runs on a native thread. */
    interface Listener {
        /**
         * One file reached its final state. [verified] = resume-skip (existing file passed size+MD5;
         * no bytes credited); otherwise a freshly assembled, verified and renamed file ([fileBytes]
         * = its decompressed size). [filesDone] counts both; [bytesDone] counts assembled bytes only.
         */
        fun onProgress(bytesDone: Long, bytesTotal: Long, filesDone: Int, filesTotal: Int, file: String, fileBytes: Long, verified: Boolean)

        /** Engine diagnostics (already in logcat under `GogNative`). */
        fun onLog(line: String)

        /**
         * Exactly once per [start] that returned a non-zero handle. [linkExpiry] = the run died on an
         * HTTP 401/403/404/500, the codes an expired secure link returns: refresh it and run again.
         */
        fun onComplete(success: Boolean, cancelled: Boolean, linkExpiry: Boolean, error: String, bytesWritten: Long, filesDone: Int)
    }

    @Volatile private var available: Boolean? = null

    /** True when the library loads and the GOG exports bind. Cached after the first call. */
    @JvmStatic
    fun isAvailable(): Boolean {
        available?.let { return it }
        val ok = try {
            StoresNative.ensureLoaded() && nativeProbe() == 1
        } catch (t: Throwable) {
            Log.w(TAG, "native GOG engine unavailable: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        available = ok
        return ok
    }

    /**
     * Starts one download loop on a native thread; returns its handle (0 = not started, no
     * callbacks). See JNI.md for the parameters; they are what the manager already holds.
     */
    @JvmStatic
    fun start(
        kind: Int, depotManifests: Array<String>, cdnBase: String, installDir: String, skipPaths: Array<String>,
        caBundlePath: String, maxWorkers: Int, processWorkers: Int, sortLargestFirst: Boolean, label: String, listener: Listener,
    ): Long {
        if (!isAvailable()) return 0L
        return try {
            nativeStart(kind, depotManifests, cdnBase, installDir, skipPaths, caBundlePath, maxWorkers, processWorkers, sortLargestFirst, label, listener)
        } catch (t: Throwable) {
            Log.e(TAG, "nativeStart threw: ${t.javaClass.simpleName}: ${t.message}")
            0L
        }
    }

    /** Requests cancellation; `onComplete(cancelled = true)` follows. Idempotent; 0 is a no-op. */
    @JvmStatic
    fun cancel(handle: Long) {
        if (handle == 0L) return
        runCatching { nativeCancel(handle) }
    }

    /** Releases the handle, once, after `onComplete`. */
    @JvmStatic
    fun release(handle: Long) {
        if (handle == 0L) return
        runCatching { nativeRelease(handle) }
    }

    @JvmStatic
    private external fun nativeProbe(): Int

    @JvmStatic
    private external fun nativeStart(
        kind: Int, depotManifests: Array<String>, cdnBase: String, installDir: String, skipPaths: Array<String>,
        caBundlePath: String, maxWorkers: Int, processWorkers: Int, sortLargestFirst: Boolean, label: String, listener: Listener,
    ): Long

    @JvmStatic
    private external fun nativeCancel(handle: Long)

    @JvmStatic
    private external fun nativeRelease(handle: Long)
}
