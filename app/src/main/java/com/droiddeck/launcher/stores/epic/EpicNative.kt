package com.droiddeck.launcher.stores.epic

import android.util.Log
import com.droiddeck.launcher.stores.StoresNative
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object EpicNative {
    private const val TAG = "EpicNative"

    interface Listener {
        fun onPlan(chunksTotal: Int, bytesTotal: Long, chunkDir: String)
        fun onProgress(bytesDone: Long, bytesTotal: Long, done: Int, total: Int)
        fun onLog(line: String)
        fun onComplete(success: Boolean, error: String, bytes: Long)
    }

    class Result(
        @JvmField val started: Boolean, @JvmField val success: Boolean, @JvmField val cancelled: Boolean,
        @JvmField val error: String, @JvmField val bytes: Long, @JvmField val done: Int, @JvmField val total: Int,
    )

    private class Completion(val success: Boolean, val error: String, val bytes: Long)

    @JvmStatic
    fun run(
        manifest: ByteArray, installDir: String, chunkCacheDir: String, cdnPrefixes: Array<String>, pendingFileIdx: IntArray, expectedChunks: Int, expectedBytes: Long,
        caBundlePath: String, maxWorkers: Int, processWorkers: Int, cancel: AtomicBoolean?, listener: Listener,
    ): Result = drive("nativeStart", cancel, listener) { inner ->
        nativeStart(manifest, installDir, chunkCacheDir, cdnPrefixes, pendingFileIdx, expectedChunks, expectedBytes, caBundlePath, maxWorkers, processWorkers, inner)
    }

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
