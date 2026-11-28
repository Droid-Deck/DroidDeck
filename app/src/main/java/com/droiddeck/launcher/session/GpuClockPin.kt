package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File

object GpuClockPin {
    private const val TAG = "GpuClockPin"
    private const val KGSL_NODE = "/dev/kgsl-3d0"

    private const val MARKER = "gpu-clock-pinned"

    @Volatile
    private var pinned = false

    @Volatile
    var failed = false
        private set

    var supported: Boolean = File(KGSL_NODE).exists()
        internal set

    internal var turbo: (Boolean) -> Unit = { WaylandCompositor.nativeSetGpuTurbo(it) }

    private var live = false

    @Volatile
    private var boosted = false

    private fun wanted(context: Context) = boosted || SessionPrefs.gpuClockPin(context)

    fun boost(on: Boolean): Boolean {
        if (boosted == on) return false
        boosted = on
        return true
    }

    @Synchronized
    fun start(context: Context): String {
        live = true
        return hold(context)
    }

    private fun hold(context: Context): String {
        failed = false
        if (!wanted(context)) return "gpu clock: governed"
        if (!supported) return "gpu clock: no $KGSL_NODE, left governed"
        return try {
            File(context.filesDir, MARKER).createNewFile()
            turbo(true)
            pinned = true
            "gpu clock: held at its top"
        } catch (t: Throwable) {
            File(context.filesDir, MARKER).delete()
            Log.w(TAG, "pin", t)
            failed = true
            "gpu clock: pin failed"
        }
    }

    @Synchronized
    fun sync(context: Context): String? {
        if (!live) return null
        if (!wanted(context) && pinned) clear(context)
        return hold(context)
    }

    @Synchronized
    fun stop(context: Context) {
        live = false
        boosted = false
        if (pinned) clear(context)
    }

    @Synchronized
    fun clearLeftover(context: Context) {
        if (File(context.filesDir, MARKER).exists()) clear(context)
    }

    private fun clear(context: Context) {
        if (supported) {
            try {
                turbo(false)
            } catch (t: Throwable) {
                Log.w(TAG, "clear", t)
            }
        }
        pinned = false
        File(context.filesDir, MARKER).delete()
    }
}
