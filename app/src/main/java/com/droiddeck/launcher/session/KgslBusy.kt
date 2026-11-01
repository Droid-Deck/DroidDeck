package com.droiddeck.launcher.session

import android.util.Log

/**
 * Whole-GPU busy share for Adreno devices whose kgsl load nodes are SELinux-blocked for apps
 * (app/src/main/cpp/gpuclock/kgsl_busy.c). It covers every process's GPU work, Android's own composition included.
 */
object KgslBusy {
    private const val TAG = "SessionService"

    @JvmStatic private external fun nativeOpen(error: IntArray): Long
    @JvmStatic private external fun nativeRun(handle: Long, on: Boolean)
    @JvmStatic private external fun nativeRead(handle: Long, out: LongArray): Boolean
    @JvmStatic private external fun nativeClose(handle: Long)

    private val loaded: Boolean by lazy {
        runCatching { System.loadLibrary("gpuclock") }.onFailure { Log.w(TAG, "hud: gpuclock library: $it") }.isSuccess
    }

    class Reader internal constructor(private var handle: Long) : AutoCloseable {
        /** Samples once a millisecond while on. */
        @Synchronized fun run(on: Boolean) {
            if (handle != 0L) nativeRun(handle, on)
        }

        /** Running totals: busy samples, samples, samples in which a ringbuffer started new work. */
        @Synchronized fun counts(): LongArray? {
            if (handle == 0L) return null
            val out = LongArray(3)
            return if (nativeRead(handle, out)) out else null
        }

        @Synchronized override fun close() {
            if (handle != 0L) nativeClose(handle)
            handle = 0L
        }
    }

    fun open(): Reader? {
        if (!loaded) return null
        val error = IntArray(1)
        val handle = runCatching { nativeOpen(error) }.getOrDefault(0L)
        if (handle == 0L) {
            Log.i(TAG, "hud: no GPU load from the kgsl memstore (open error ${error[0]})")
            return null
        }
        return Reader(handle)
    }
}
