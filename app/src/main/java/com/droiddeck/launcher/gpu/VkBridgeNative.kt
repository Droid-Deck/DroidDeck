package com.droiddeck.launcher.gpu

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.File

object VkBridgeNative {
    private const val TAG = "VkBridge"

    @Volatile private var loaded: Boolean? = null

    private fun load(context: Context): Boolean {
        loaded?.let { return it }
        val ok = try {
            val dev = File(context.filesDir, "vkbridge/dev/libvkbridge_jni.so")
            val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            if (debuggable && dev.isFile) {
                Log.w(TAG, "in-app server from the development override $dev")
                System.load(dev.absolutePath)
            } else {
                System.loadLibrary("vkbridge_jni")
            }
            true
        } catch (e: Throwable) {
            Log.w(TAG, "in-app server unavailable: $e")
            false
        }
        loaded = ok
        return ok
    }

    @Volatile var serving = false
        private set

    fun start(context: Context, socket: File, log: File, cacheDir: File): Boolean {
        if (!load(context)) return false
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
