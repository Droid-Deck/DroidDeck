package com.droiddeck.launcher.stores.gog

import android.util.Log
import com.droiddeck.launcher.stores.StoresNative

object GogNative {
    private const val TAG = "GogNative"

    const val KIND_GEN2_CHUNKS = 0

    const val KIND_GEN1_RANGES = 1

    interface Listener {
        fun onProgress(bytesDone: Long, bytesTotal: Long, filesDone: Int, filesTotal: Int, file: String, fileBytes: Long, verified: Boolean)

        fun onBytes(bytesDone: Long, bytesTotal: Long)

        fun onLog(line: String)

        fun onComplete(success: Boolean, cancelled: Boolean, linkExpiry: Boolean, error: String, bytesWritten: Long, filesDone: Int)
    }

    @Volatile private var available: Boolean? = null

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

    @JvmStatic
    fun start(
        kind: Int, depotManifests: Array<String>, cdnBases: Array<String>, installDir: String, skipPaths: Array<String>,
        caBundlePath: String, maxWorkers: Int, processWorkers: Int, sortLargestFirst: Boolean, label: String, listener: Listener,
    ): Long {
        if (!isAvailable()) return 0L
        return try {
            nativeStart(kind, depotManifests, cdnBases, installDir, skipPaths, caBundlePath, maxWorkers, processWorkers, sortLargestFirst, label, listener)
        } catch (t: Throwable) {
            Log.e(TAG, "nativeStart threw: ${t.javaClass.simpleName}: ${t.message}")
            0L
        }
    }

    @JvmStatic
    fun cancel(handle: Long) {
        if (handle == 0L) return
        runCatching { nativeCancel(handle) }
    }

    @JvmStatic
    fun release(handle: Long) {
        if (handle == 0L) return
        runCatching { nativeRelease(handle) }
    }

    @JvmStatic
    private external fun nativeProbe(): Int

    @JvmStatic
    private external fun nativeStart(
        kind: Int, depotManifests: Array<String>, cdnBases: Array<String>, installDir: String, skipPaths: Array<String>,
        caBundlePath: String, maxWorkers: Int, processWorkers: Int, sortLargestFirst: Boolean, label: String, listener: Listener,
    ): Long

    @JvmStatic
    private external fun nativeCancel(handle: Long)

    @JvmStatic
    private external fun nativeRelease(handle: Long)
}
