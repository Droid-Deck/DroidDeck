package com.droiddeck.launcher.hud

/** Reader for the frame-times file gamescope patch 0125 writes (hudframes.c). */
internal object HudFrames {
    init {
        System.loadLibrary("droiddeckwayland")
    }

    /** Under the Linux root: the session exports /run/droiddeck-hud/frames to gamescope. */
    const val PATH = "run/droiddeck-hud/frames"
    const val CAPACITY = 1024

    @JvmStatic external fun nativeOpen(path: String): Long
    @JvmStatic external fun nativeClose(handle: Long)
    @JvmStatic external fun nativePoll(handle: Long, frametimes: LongArray, times: LongArray): Int
    @JvmStatic external fun nativeInfo(handle: Long, info: IntArray): String?
}
