package com.droiddeck.launcher.gpu

import android.util.Log
import java.io.File

/**
 * The bridge server inside the app's own process (libvkbridge_jni.so, server_main.c).
 *
 * Android chooses the GPU driver - Samsung's updatable one included - while the app starts up, and
 * only for the app's process: the server run as a separate executable got the plain /vendor driver,
 * which on the Tab S10+ lacked VK_EXT_robustness2 and more that the app itself sees. Started by the
 * first bridge session and kept for the app's life; later sessions only point its log at their
 * own folder. VkBridgeComponent falls back to the executable when this cannot start.
 */
object VkBridgeNative {
    private const val TAG = "VkBridge"

    private val loaded: Boolean by lazy {
        try {
            System.loadLibrary("vkbridge_jni")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "in-app server unavailable: $e")
            false
        }
    }

    /** Whether the in-app server is serving (its socket must then be left alone). */
    @Volatile var serving = false
        private set

    /** Starts the server, or points its log at [log] when it already runs; false when it cannot serve. */
    fun start(socket: File, log: File, cacheDir: File): Boolean {
        if (!loaded) return false
        val ok = try {
            nativeStart(socket.absolutePath, log.absolutePath, cacheDir.absolutePath) == 1
        } catch (e: Throwable) {
            Log.w(TAG, "in-app server failed to start: $e")
            false
        }
        if (ok) serving = true
        return ok
    }

    @JvmStatic private external fun nativeStart(socket: String, log: String, cacheDir: String): Int
}
