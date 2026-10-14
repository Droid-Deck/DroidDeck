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

    interface InstallListener : Listener {
        /** Synchronous approval before the first verified atomic replacement. False aborts. */
        fun beforeSwap(): Boolean
    }

    /** One native budgeted fetch/assembly run. A loaded-engine failure is never a Java retry. */
    @JvmStatic
    fun installAdaptive(request: String, cancel: AtomicBoolean, listener: InstallListener): Result =
        drive("nativeInstallAdaptive", cancel, listener, strict = true) { inner ->
            nativeInstallAdaptive(request, inner)
        }

    /** Callbacks from a [stream] run; every method is called on a native thread. */
    interface StreamListener {
        /** File bytes written so far (staging included), and the files verified and swapped in so far. */
        fun onStreamProgress(bytesWritten: Long, bytesTotal: Long, filesDone: Int, filesTotal: Int, filesBytes: Long)
        /** Engine log line (already in logcat under `EpicNative`). */
        fun onLog(line: String)
        /** Once, before the first file replaces its destination; false (or a throw) fails the run with nothing replaced. */
        fun onBeforeReplace(): Boolean
        /** Terminal; `bytes` = file bytes written. */
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

    /**
     * The adaptive install of the files [pendingFileIdx] (indices into the manifest's file list),
     * with at most [cacheBudget] bytes of shared chunks cached and at most [overlapBudget] planned
     * bytes of replaced files staged at once (`-1` = no limit), staged in [stagingDir] (the
     * manager's owned staging directory). `started == false` means nothing was fetched or written.
     */
    @JvmStatic
    fun stream(
        manifest: ByteArray, installDir: String, chunkCacheDir: String, stagingDir: String, cdnPrefixes: Array<String>, pendingFileIdx: IntArray, expectedBytes: Long,
        cacheBudget: Long, overlapBudget: Long, caBundlePath: String, maxWorkers: Int, processWorkers: Int, cancel: AtomicBoolean?, listener: StreamListener,
    ): Result {
        if (!StoresNative.ensureLoaded()) return Result(false, false, false, "engine not built", 0L, 0, 0)
        val latch = CountDownLatch(1)
        val completion = AtomicReference<Completion?>(null)
        val doneRef = AtomicReference(0)
        val totalRef = AtomicReference(pendingFileIdx.size)
        val inner = object : StreamListener {
            override fun onStreamProgress(bytesWritten: Long, bytesTotal: Long, filesDone: Int, filesTotal: Int, filesBytes: Long) {
                doneRef.set(filesDone); totalRef.set(filesTotal)
                listener.onStreamProgress(bytesWritten, bytesTotal, filesDone, filesTotal, filesBytes)
            }
            override fun onLog(line: String) = listener.onLog(line)
            override fun onBeforeReplace(): Boolean = runCatching { listener.onBeforeReplace() }.getOrDefault(false)
            override fun onComplete(success: Boolean, error: String, bytes: Long) {
                completion.set(Completion(success, error, bytes))
                latch.countDown()
                runCatching { listener.onComplete(success, error, bytes) }
            }
        }
        val handle: Long = try {
            nativeStream(manifest, installDir, chunkCacheDir, stagingDir, cdnPrefixes, pendingFileIdx, expectedBytes, cacheBudget, overlapBudget, caBundlePath, maxWorkers, processWorkers, inner)
        } catch (t: Throwable) {
            Log.w(TAG, "engine unavailable: ${t.javaClass.simpleName}: ${t.message}")
            return Result(false, false, false, "nativeStream: ${t.javaClass.simpleName}: ${t.message}", 0L, 0, 0)
        }
        if (handle == 0L) return Result(false, false, false, completion.get()?.error ?: "not started", 0L, 0, totalRef.get())
        var cancelSent = false
        try {
            while (!latch.await(250, TimeUnit.MILLISECONDS)) {
                if (!cancelSent && cancel != null && cancel.get()) {
                    nativeCancel(handle)
                    cancelSent = true
                }
            }
        } finally {
            if (completion.get() == null) nativeCancel(handle)
            nativeRelease(handle)
        }
        val c = completion.get()
        val cancelled = cancelSent || (c != null && !c.success && c.error == "cancelled")
        val success = !cancelled && c != null && c.success
        val error = when { cancelled -> "cancelled"; c == null -> "no completion"; else -> c.error }
        return Result(true, success, cancelled, error, c?.bytes ?: 0L, doneRef.get(), totalRef.get())
    }

    private fun drive(what: String, cancel: AtomicBoolean?, listener: Listener, strict: Boolean = false, start: (Listener) -> Long): Result {
        if (!StoresNative.ensureLoaded()) return Result(false, false, false, "engine not built", 0L, 0, 0)
        val latch = CountDownLatch(1)
        val completion = AtomicReference<Completion?>(null)
        val doneRef = AtomicReference(0)
        val totalRef = AtomicReference(0)
        val inner = object : InstallListener {
            override fun beforeSwap(): Boolean = (listener as? InstallListener)?.beforeSwap() ?: false
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
            Log.w(TAG, "${if (strict) "engine start failed (no fallback)" else "engine unavailable"}: ${t.javaClass.simpleName}: ${t.message}")
            return Result(strict, false, false, "$what: ${t.javaClass.simpleName}: ${t.message}", 0L, 0, 0)
        }
        if (handle == 0L) return Result(strict, false, false, completion.get()?.error ?: "not started", 0L, 0, totalRef.get())
        var cancelSent = false
        try {
            while (!latch.await(250, TimeUnit.MILLISECONDS)) {
                if (cancel != null && cancel.get()) {
                    nativeCancel(handle)
                    cancelSent = true
                    if (strict) {
                        while (!latch.await(250, TimeUnit.MILLISECONDS)) { }
                    } else {
                        latch.await(5, TimeUnit.SECONDS)
                    }
                    break
                }
            }
        } finally {
            if (completion.get() == null) nativeCancel(handle)
            nativeRelease(handle)
        }
        val c = completion.get()
        val success = !cancelSent && c != null && c.success
        val error = when { cancelSent -> "cancelled"; c == null -> "no completion"; else -> c.error }
        return Result(true, success, cancelSent, error, c?.bytes ?: 0L, doneRef.get(), totalRef.get())
    }

    @JvmStatic
    private external fun nativeInstallAdaptive(request: String, listener: Listener): Long

    @JvmStatic
    private external fun nativeStart(
        manifest: ByteArray, installDir: String, chunkCacheDir: String, cdnPrefixes: Array<String>, pendingFileIdx: IntArray, expectedChunks: Int, expectedBytes: Long,
        caBundlePath: String, maxWorkers: Int, processWorkers: Int, listener: Listener,
    ): Long

    @JvmStatic
    private external fun nativeAssemble(manifest: ByteArray, installDir: String, chunkCacheDir: String, fileIdx: IntArray, listener: Listener): Long

    @JvmStatic
    private external fun nativeStream(
        manifest: ByteArray, installDir: String, chunkCacheDir: String, stagingDir: String, cdnPrefixes: Array<String>, pendingFileIdx: IntArray, expectedBytes: Long,
        cacheBudget: Long, overlapBudget: Long, caBundlePath: String, maxWorkers: Int, processWorkers: Int, listener: StreamListener,
    ): Long

    @JvmStatic
    private external fun nativeCancel(handle: Long)

    @JvmStatic
    private external fun nativeRelease(handle: Long)
}
