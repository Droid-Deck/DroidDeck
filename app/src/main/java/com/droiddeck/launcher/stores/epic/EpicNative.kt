// JNI symbols depend on this package path and class name (app/src/main/rust/stores/JNI.md).
package com.droiddeck.launcher.stores.epic

import android.util.Log
import com.droiddeck.launcher.stores.StoresNative
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The native Epic chunk fetcher in `libdroiddeckstores.so`: it fills `<installDir>/.chunks/<GUID>`
 * with verified, decompressed chunks for the files the manager says are pending. Everything
 * around it (manifest, install tags, delta pass, assembly, post-install) stays in the manager.
 *
 * [run] blocks like the Java pool it replaces and polls [AtomicBoolean] `cancel` every 250 ms: on
 * cancel it flips the native flag, waits up to 5 s for the run to wind down, then returns.
 */
object EpicNative {
    private const val TAG = "EpicNative"

    /** Callbacks from the native run; every method is called on a native thread. */
    interface Listener {
        /** Once, before any fetch: the plan the engine derived (the manager cross-checks it). */
        fun onPlan(chunksTotal: Int, bytesTotal: Long, chunkDir: String)
        /** Per accounted chunk (cached-skip or fetched). */
        fun onProgress(bytesDone: Long, bytesTotal: Long, chunksDone: Int, chunksTotal: Int)
        /** Engine log line (already in logcat under `EpicNative`). */
        fun onLog(line: String)
        /** Terminal. */
        fun onComplete(success: Boolean, error: String, bytesCredited: Long)
    }

    /** Outcome of [run]. `started == false`: nothing was fetched, the caller runs its own pool. */
    class Result(
        @JvmField val started: Boolean, @JvmField val success: Boolean, @JvmField val cancelled: Boolean,
        @JvmField val error: String, @JvmField val bytesCredited: Long, @JvmField val chunksDone: Int, @JvmField val chunksTotal: Int,
    )

    private class Completion(val success: Boolean, val error: String, val bytes: Long)

    @JvmStatic
    fun run(
        manifest: ByteArray, installDir: String, cdnPrefixes: Array<String>, pendingFileIdx: IntArray, expectedChunks: Int, expectedBytes: Long,
        caBundlePath: String, maxWorkers: Int, processWorkers: Int, cancel: AtomicBoolean?, listener: Listener,
    ): Result {
        if (!StoresNative.ensureLoaded()) return Result(false, false, false, "engine not built", 0L, 0, 0)
        val latch = CountDownLatch(1)
        val completion = AtomicReference<Completion?>(null)
        val doneRef = AtomicReference(0)
        val totalRef = AtomicReference(0)
        val inner = object : Listener {
            override fun onPlan(chunksTotal: Int, bytesTotal: Long, chunkDir: String) { totalRef.set(chunksTotal); listener.onPlan(chunksTotal, bytesTotal, chunkDir) }
            override fun onProgress(bytesDone: Long, bytesTotal: Long, chunksDone: Int, chunksTotal: Int) { doneRef.set(chunksDone); listener.onProgress(bytesDone, bytesTotal, chunksDone, chunksTotal) }
            override fun onLog(line: String) = listener.onLog(line)
            override fun onComplete(success: Boolean, error: String, bytesCredited: Long) {
                completion.set(Completion(success, error, bytesCredited))
                latch.countDown()
                runCatching { listener.onComplete(success, error, bytesCredited) }
            }
        }
        val handle: Long = try {
            nativeStart(manifest, installDir, cdnPrefixes, pendingFileIdx, expectedChunks, expectedBytes, caBundlePath, maxWorkers, processWorkers, inner)
        } catch (t: Throwable) {
            Log.w(TAG, "engine unavailable: ${t.javaClass.simpleName}: ${t.message}")
            return Result(false, false, false, "nativeStart: ${t.javaClass.simpleName}", 0L, 0, 0)
        }
        if (handle == 0L) return Result(false, false, false, completion.get()?.error ?: "not started", 0L, 0, totalRef.get())
        var cancelSent = false
        try {
            while (!latch.await(250, TimeUnit.MILLISECONDS)) {
                if (cancel != null && cancel.get()) {
                    nativeCancel(handle)
                    cancelSent = true
                    latch.await(5, TimeUnit.SECONDS)
                    break
                }
            }
        } finally {
            nativeRelease(handle)
        }
        val c = completion.get()
        val success = !cancelSent && c != null && c.success
        val error = when { cancelSent -> "cancelled"; c == null -> "no completion"; else -> c.error }
        return Result(true, success, cancelSent, error, c?.bytes ?: 0L, doneRef.get(), totalRef.get())
    }

    @JvmStatic
    private external fun nativeStart(
        manifest: ByteArray, installDir: String, cdnPrefixes: Array<String>, pendingFileIdx: IntArray, expectedChunks: Int, expectedBytes: Long,
        caBundlePath: String, maxWorkers: Int, processWorkers: Int, listener: Listener,
    ): Long

    @JvmStatic
    private external fun nativeCancel(handle: Long)

    @JvmStatic
    private external fun nativeRelease(handle: Long)
}
