package com.droiddeck.launcher.hud

internal object HudFrames {
    init {
        System.loadLibrary("droiddeckwayland")
    }

    const val PATH = "run/droiddeck-hud/frames"
    const val CAPACITY = 1024

    @JvmStatic external fun nativeOpen(path: String): Long
    @JvmStatic external fun nativeClose(handle: Long)
    @JvmStatic external fun nativePoll(handle: Long, frametimes: LongArray, times: LongArray): Int
    @JvmStatic external fun nativeInfo(handle: Long, info: IntArray): String?
}
