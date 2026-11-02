package com.droiddeck.launcher.session

import android.util.Log

/**
 * GPU clock from the GPU's own cycle counter, for Adreno devices whose kgsl clock nodes are SELinux-blocked for
 * apps (app/src/main/cpp/gpuclock/kgsl_clock.c). Each [mhz] call runs one tiny GPU job, so callers sample only
 * while a HUD is shown.
 */
object KgslClock {
    private const val TAG = "SessionService"

    @JvmStatic private external fun nativeOpen(error: IntArray): Long
    @JvmStatic private external fun nativeSample(handle: Long): Int
    @JvmStatic private external fun nativeClose(handle: Long)

    private val loaded: Boolean by lazy {
        runCatching { System.loadLibrary("gpuclock") }.onFailure { Log.w(TAG, "hud: gpuclock library: $it") }.isSuccess
    }

    class Sampler internal constructor(private var handle: Long) : AutoCloseable {
        /** MHz, or null when this sample was refused or failed its checks. */
        @Synchronized fun mhz(): Int? = if (handle == 0L) null else nativeSample(handle).takeIf { it > 0 }

        @Synchronized override fun close() {
            if (handle != 0L) nativeClose(handle)
            handle = 0L
        }
    }

    /** A sampler that already produced one good reading, or null (the reason goes to the log). */
    fun open(): Sampler? {
        if (!loaded) return null
        val error = IntArray(1)
        val handle = runCatching { nativeOpen(error) }.getOrDefault(0L)
        if (handle == 0L) {
            Log.i(TAG, "hud: no GPU clock from the cycle counter (open error ${error[0]})")
            return null
        }
        val sampler = Sampler(handle)
        val first = (0 until 3).firstNotNullOfOrNull { sampler.mhz() }
        if (first == null) {
            Log.i(TAG, "hud: GPU cycle counter gave no usable reading")
            sampler.close()
            return null
        }
        Log.i(TAG, "hud: GPU clock from the cycle counter ($first MHz now)")
        return sampler
    }
}
