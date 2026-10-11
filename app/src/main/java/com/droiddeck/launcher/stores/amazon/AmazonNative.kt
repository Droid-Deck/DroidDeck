// JNI symbols depend on this package path and class name (app/src/main/rust/stores/JNI.md).
package com.droiddeck.launcher.stores.amazon

import android.util.Log
import com.droiddeck.launcher.stores.StoresNative
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The native Amazon file fetcher in `libdroiddeckstores.so`: one streamed GET per manifest file,
 * SHA-256 checked, written as `<dest>.tmp` and renamed. The manager keeps manifest, auth, markers
 * and post-install; [runBlocking] mirrors the Java loop's shape and polls the cancel probe.
 */
object AmazonNative {
    const val TAG = "AmazonNative"
    private const val CANCEL_POLL_MS = 100L

    /** Callbacks from the engine; every method is called on a native thread. */
    interface Listener {
        /** Once up front with the resume-skipped credit, then after each committed file; [bytesDone] includes skipped files. */
        fun onProgress(bytesDone: Long, bytesTotal: Long, filesDone: Long, filesTotal: Long)
        /** One diagnostic line; the engine itself does not write to logcat. */
        fun onLog(line: String)
        /** Exactly once, when the run succeeds, fails or is cancelled. */
        fun onComplete(success: Boolean, error: String, bytesWritten: Long)
    }

    fun interface CancelCheck { fun isCancelled(): Boolean }

    class RunResult(@JvmField val success: Boolean, @JvmField val cancelled: Boolean, @JvmField val error: String, @JvmField val bytesWritten: Long)

    @JvmStatic
    fun isAvailable(): Boolean = StoresNative.available

    /**
     * Runs one download to completion. [planJson]: `[{relPath, url, size, sha256hex}]`, every
     * manifest file (the engine skips complete ones itself). [maxWorkers] <= 0 = the engine's default.
     */
    @JvmStatic
    fun runBlocking(planJson: String, installDir: String, caBundlePath: String, maxWorkers: Int, processWorkers: Int, isCancelled: CancelCheck?, listener: Listener): RunResult {
        if (!StoresNative.ensureLoaded()) return RunResult(false, false, "engine not built", 0L)
        val latch = CountDownLatch(1)
        var outcome: RunResult? = null
        val bridge = object : Listener {
            override fun onProgress(bytesDone: Long, bytesTotal: Long, filesDone: Long, filesTotal: Long) = listener.onProgress(bytesDone, bytesTotal, filesDone, filesTotal)
            override fun onLog(line: String) = listener.onLog(line)
            override fun onComplete(success: Boolean, error: String, bytesWritten: Long) {
                outcome = RunResult(success, false, error, bytesWritten)
                listener.onComplete(success, error, bytesWritten)
                latch.countDown()
            }
        }
        val handle = try {
            nativeStart(planJson, installDir, caBundlePath, maxWorkers, processWorkers, bridge)
        } catch (t: Throwable) {
            Log.w(TAG, "nativeStart threw: ${t.javaClass.simpleName}: ${t.message}")
            return RunResult(false, false, "nativeStart: ${t.javaClass.simpleName}", 0L)
        }
        if (handle == 0L) return outcome ?: RunResult(false, false, "native start failed", 0L)
        var cancelSent = false
        try {
            while (!latch.await(CANCEL_POLL_MS, TimeUnit.MILLISECONDS)) {
                if (!cancelSent && isCancelled?.isCancelled() == true) { nativeCancel(handle); cancelSent = true }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!cancelSent) { nativeCancel(handle); cancelSent = true }
            try { latch.await(30, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        } finally {
            nativeRelease(handle)
        }
        val r = outcome ?: RunResult(false, cancelSent, "no completion", 0L)
        return RunResult(r.success && !cancelSent, cancelSent, r.error, r.bytesWritten)
    }

    @JvmStatic
    private external fun nativeStart(planJson: String, installDir: String, caBundlePath: String, maxWorkers: Int, processWorkers: Int, listener: Listener): Long

    @JvmStatic
    private external fun nativeCancel(handle: Long)

    @JvmStatic
    private external fun nativeRelease(handle: Long)
}
