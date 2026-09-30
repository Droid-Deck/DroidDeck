package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File

/**
 * The GPU held at its top clock for a session, without root: KGSL's power-control property turned
 * off on /dev/kgsl-3d0, the node the driver already opens (adrenotools' "turbo"). Left to itself the
 * governor lets the GPU sit low between bursts, and a frame that lands on a low clock is a hitch;
 * Bannerlator measured the clock going from 231 to 1000 MHz with this set. It costs power and heat,
 * so it is opt-in, and the kernel's thermal limits still apply on top of it.
 *
 * The property belongs to the device, not to this process, and a killed app cannot unwind it: it
 * is cleared when a session stops, and at the next app start when a marker file says this app set
 * it and never cleared it - only then, so another app's own setting is not undone.
 * (Bannerlator, PerfGpuTurbo.)
 */
object GpuClockPin {
    private const val TAG = "GpuClockPin"
    private const val KGSL_NODE = "/dev/kgsl-3d0"

    private const val MARKER = "gpu-clock-pinned"

    @Volatile
    private var pinned = false

    private val supported: Boolean by lazy { File(KGSL_NODE).exists() }

    /** At a session's start: pins the clock when the user asked for it. The line is for the log. */
    @Synchronized
    fun start(context: Context): String {
        if (!SessionPrefs.gpuClockPin(context)) return "gpu clock: governed"
        if (!supported) return "gpu clock: no $KGSL_NODE, left governed"
        return try {
            File(context.filesDir, MARKER).createNewFile()
            WaylandCompositor.nativeSetGpuTurbo(true)
            pinned = true
            "gpu clock: held at its top"
        } catch (t: Throwable) {
            Log.w(TAG, "pin", t)
            "gpu clock: pin failed"
        }
    }

    /** At a session's end. */
    @Synchronized
    fun stop(context: Context) {
        if (pinned) clear(context)
    }

    /** At the app's start: a process killed with the clock pinned left only the marker behind. */
    @Synchronized
    fun clearLeftover(context: Context) {
        if (File(context.filesDir, MARKER).exists()) clear(context)
    }

    private fun clear(context: Context) {
        if (supported) {
            try {
                WaylandCompositor.nativeSetGpuTurbo(false)
            } catch (t: Throwable) {
                Log.w(TAG, "clear", t)
            }
        }
        pinned = false
        File(context.filesDir, MARKER).delete()
    }
}
