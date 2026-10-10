// JNI symbols depend on this package path and class name (app/src/main/rust/stores/JNI.md).
package com.droiddeck.launcher.stores.epic

import android.util.Log
import com.droiddeck.launcher.stores.StoresNative
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The native half of an Epic install in `libdroiddeckstores.so`: [run] fills the chunk cache with
 * verified, decompressed chunks for the files the manager says are pending, [assemble] writes those
 * files from the cache. Everything around them (manifest, install tags, delta pass, post-install)
 * stays in the manager.
 *
 * The cache is `<installDir>/.chunks` when `chunkCacheDir` is `""`, else the scratch folder given -
 * the app's own cache when the game installs to a card, where the assembler drops each chunk right
 * after its last use and the folder at the end. Both calls take the same value for one install.
 *
 * Both block like the Java loops they replace and poll [AtomicBoolean] `cancel` every 250 ms: on
 * cancel they flip the native flag, wait up to 5 s for the run to wind down, then return.
 */
object EpicNative {
    private const val TAG = "EpicNative"

    /** Callbacks from a native run; every method is called on a native thread. One interface serves both runs. */
    interface Listener {
        /** Fetch only, once before any fetch: the plan the engine derived (the manager cross-checks it). */
        fun onPlan(chunksTotal: Int, bytesTotal: Long, chunkDir: String)
        /** Fetch: per accounted chunk (cached-skip or fetched), done/total = chunks. Assembly: per file written, done/total = files. */
        fun onProgress(bytesDone: Long, bytesTotal: Long, done: Int, total: Int)
        /** Engine log line (already in logcat under `EpicNative`). */
        fun onLog(line: String)
        /** Terminal. Fetch: bytes credited; assembly: bytes written. */
        fun onComplete(success: Boolean, error: String, bytes: Long)
    }

    /** Outcome of a run. `started == false`: nothing happened on disk, the caller runs its own loop. */
    class Result(
        @JvmField val started: Boolean, @JvmField val success: Boolean, @JvmField val cancelled: Boolean,
        @JvmField val error: String, @JvmField val bytes: Long, @JvmField val done: Int, @JvmField val total: Int,
    )

    private class Completion(val success: Boolean, val error: String, val bytes: Long)

    /** The fetch: chunks for [pendingFileIdx] into the cache. */
    @JvmStatic
    fun run(
        manifest: ByteArray, installDir: String, chunkCacheDir: String, cdnPrefixes: Array<String>, pendingFileIdx: IntArray, expectedChunks: Int, expectedBytes: Long,
        caBundlePath: String, maxWorkers: Int, processWorkers: Int, cancel: AtomicBoolean?, listener: Listener,
    ): Result = drive("nativeStart", cancel, listener) { inner ->
        nativeStart(manifest, installDir, chunkCacheDir, cdnPrefixes, pendingFileIdx, expectedChunks, expectedBytes, caBundlePath, maxWorkers, processWorkers, inner)
    }

    /** The assembly: the files [fileIdx] written from the cache, the cache removed on success. */
    @JvmStatic
    fun assemble(manifest: ByteArray, installDir: String, chunkCacheDir: String, fileIdx: IntArray, cancel: AtomicBoolean?, listener: Listener): Result =
        drive("nativeAssemble", cancel, listener) { inner -> nativeAssemble(manifest, installDir, chunkCacheDir, fileIdx, inner) }

    private fun drive(what: String, cancel: AtomicBoolean?, listener: Listener, start: (Listener) -> Long): Result {
        if (!StoresNative.ensureLoaded()) return Result(false, false, false, "engine not built", 0L, 0, 0)
        val latch = CountDownLatch(1)
        val completion = AtomicReference<Completion?>(null)
        val doneRef = AtomicReference(0)
        val totalRef = AtomicReference(0)
        val inner = object : Listener {
            override fun onPlan(chunksTotal: Int, bytesTotal: Long, chunkDir: String) { totalRef.set(chunksTotal); listener.onPlan(chunksTotal, bytesTotal, chunkDir) }
            override fun onProgress(bytesDone: Long, bytesTotal: Long, done: Int, total: Int) { doneRef.set(done); totalRef.set(total); listener.onProgress(bytesDone, bytesTotal, done, total) }
            override fun onLog(line: String) = listener.onLog(line)
            override fun onComplete(success: Boolean, error: String, bytes: Long) {
                completion.set(Completion(success, error, bytes))
                latch.countDown()
                runCatching { listener.onComplete(success, error, bytes) }
            }
        }
        val handle: Long = try {
            start(inner)
        } catch (t: Throwable) {
            Log.w(TAG, "engine unavailable: ${t.javaClass.simpleName}: ${t.message}")
            return Result(false, false, false, "$what: ${t.javaClass.simpleName}", 0L, 0, 0)
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
        manifest: ByteArray, installDir: String, chunkCacheDir: String, cdnPrefixes: Array<String>, pendingFileIdx: IntArray, expectedChunks: Int, expectedBytes: Long,
        caBundlePath: String, maxWorkers: Int, processWorkers: Int, listener: Listener,
    ): Long

    @JvmStatic
    private external fun nativeAssemble(manifest: ByteArray, installDir: String, chunkCacheDir: String, fileIdx: IntArray, listener: Listener): Long

    @JvmStatic
    private external fun nativeCancel(handle: Long)

    @JvmStatic
    private external fun nativeRelease(handle: Long)
}
