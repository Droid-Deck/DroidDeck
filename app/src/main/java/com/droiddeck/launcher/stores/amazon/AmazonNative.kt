package com.droiddeck.launcher.stores.amazon

import android.util.Log
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.stores.StoresNative
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object AmazonNative {
    private const val TAG = "AmazonNative"
    private const val CANCEL_POLL_MS = 250L

    const val ENGINE_MISSING = "Amazon Games: the store engine is not available"

    @Volatile private var configured = false

    fun ready(): Boolean {
        if (!StoresNative.ensureLoaded()) return false
        if (!configured) synchronized(this) {
            if (!configured) {
                runCatching { nativeConfigure(BuildConfig.VERSION_NAME) }
                    .onFailure { Log.w(TAG, "configure: ${it.javaClass.simpleName}") }
                configured = true
            }
        }
        return true
    }

    fun call(op: String, request: JSONObject = JSONObject()): JSONObject? {
        if (!ready()) return null
        val text = try {
            nativeCall(op, request.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "$op: ${t.javaClass.simpleName}")
            null
        } ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    fun fetch(op: String, request: JSONObject): ByteArray? {
        if (!ready()) return null
        return try {
            nativeFetch(op, request.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "$op: ${t.javaClass.simpleName}")
            null
        }
    }

    fun inspect(manifest: ByteArray, request: JSONObject = JSONObject()): JSONObject? {
        if (!ready()) return null
        val text = try {
            nativeInspect(manifest, request.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "inspect: ${t.javaClass.simpleName}")
            null
        } ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    interface InstallListener {
        fun onStatus(text: String)
        fun onCheck(doneBytes: Long, totalBytes: Long, files: Int, filesTotal: Int)
        fun onSizes(pendingBytes: Long, totalBytes: Long)
        fun onProgress(done: Long, total: Long, files: Int, filesTotal: Int)
        fun onLog(line: String)
        fun beforeReplace(): Boolean
        fun onComplete(success: Boolean, cancelled: Boolean, error: String, result: String)
    }

    class InstallOutcome(val success: Boolean, val cancelled: Boolean, val error: String, val result: JSONObject?)

    fun install(request: JSONObject, events: InstallListener, cancelled: AtomicBoolean): InstallOutcome {
        if (!ready()) return InstallOutcome(false, false, ENGINE_MISSING, null)
        val done = CountDownLatch(1)
        val holder = arrayOfNulls<InstallOutcome>(1)
        val bridge = object : InstallListener by events {
            override fun onComplete(success: Boolean, cancelled: Boolean, error: String, result: String) {
                holder[0] = InstallOutcome(success, cancelled, error, result.takeIf { it.isNotEmpty() }?.let { runCatching { JSONObject(it) }.getOrNull() })
                done.countDown()
            }
        }
        val handle = try {
            nativeInstall(request.toString(), bridge)
        } catch (t: Throwable) {
            return InstallOutcome(false, false, "The Amazon install could not start: ${t.javaClass.simpleName}", null)
        }
        if (handle == 0L) return holder[0] ?: InstallOutcome(false, false, "The Amazon install could not start", null)
        try {
            var forwarded = false
            while (true) {
                val finished = try {
                    done.await(CANCEL_POLL_MS, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    cancelled.set(true)
                    false
                }
                if (finished) break
                if (!forwarded && cancelled.get()) {
                    nativeCancel(handle)
                    forwarded = true
                }
            }
        } finally {
            nativeRelease(handle)
        }
        return holder[0] ?: InstallOutcome(false, false, "The Amazon install ended without a result", null)
    }

    @JvmStatic private external fun nativeConfigure(appVersion: String)
    @JvmStatic private external fun nativeCall(op: String, request: String): String?
    @JvmStatic private external fun nativeFetch(op: String, request: String): ByteArray?
    @JvmStatic private external fun nativeInspect(manifest: ByteArray, request: String): String?
    @JvmStatic private external fun nativeInstall(request: String, listener: InstallListener): Long
    @JvmStatic private external fun nativeCancel(handle: Long)
    @JvmStatic private external fun nativeRelease(handle: Long)
}
