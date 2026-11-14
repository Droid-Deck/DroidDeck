package com.droiddeck.launcher.stores.ea

import android.util.Log
import com.droiddeck.launcher.stores.StoresNative
import com.droiddeck.launcher.stores.amazon.AmazonNative
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object EaNative {
    private const val TAG = "EaNative"
    const val ENGINE_MISSING = "EA: the store engine is not available"

    fun ready(): Boolean = StoresNative.ensureLoaded()

    fun call(op: String, request: JSONObject): JSONObject? {
        if (!ready()) return null
        val text = try { nativeCall(op, request.toString()) } catch (t: Throwable) {
            Log.w(TAG, "$op: ${t.javaClass.simpleName}")
            null
        } ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    fun install(request: JSONObject, events: AmazonNative.InstallListener, cancelled: AtomicBoolean): AmazonNative.InstallOutcome {
        if (!ready()) return AmazonNative.InstallOutcome(false, false, ENGINE_MISSING, null)
        val done = CountDownLatch(1)
        val holder = arrayOfNulls<AmazonNative.InstallOutcome>(1)
        val bridge = object : AmazonNative.InstallListener by events {
            override fun onComplete(success: Boolean, cancelled: Boolean, error: String, result: String) {
                holder[0] = AmazonNative.InstallOutcome(success, cancelled, error, result.takeIf { it.isNotEmpty() }?.let { runCatching { JSONObject(it) }.getOrNull() })
                done.countDown()
            }
        }
        val handle = try { nativeInstall(request.toString(), bridge) } catch (t: Throwable) {
            return AmazonNative.InstallOutcome(false, false, "The EA install could not start: ${t.javaClass.simpleName}", null)
        }
        if (handle == 0L) return holder[0] ?: AmazonNative.InstallOutcome(false, false, "The EA install could not start", null)
        try {
            var forwarded = false
            while (true) {
                val finished = try { done.await(250, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { cancelled.set(true); false }
                if (finished) break
                if (!forwarded && cancelled.get()) { nativeCancel(handle); forwarded = true }
            }
        } finally {
            nativeRelease(handle)
        }
        return holder[0] ?: AmazonNative.InstallOutcome(false, false, "The EA install ended without a result", null)
    }

    @JvmStatic private external fun nativeCall(op: String, request: String): String?
    @JvmStatic private external fun nativeInstall(request: String, listener: AmazonNative.InstallListener): Long
    @JvmStatic private external fun nativeCancel(handle: Long)
    @JvmStatic private external fun nativeRelease(handle: Long)
}
