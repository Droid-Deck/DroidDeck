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

internal interface FrameRing {
    fun open(path: String): Long
    fun poll(handle: Long, frametimes: LongArray, times: LongArray): Int
    fun info(handle: Long, info: IntArray): String?
    fun close(handle: Long)
}

internal object NativeFrameRing : FrameRing {
    override fun open(path: String) = HudFrames.nativeOpen(path)
    override fun poll(handle: Long, frametimes: LongArray, times: LongArray) = HudFrames.nativePoll(handle, frametimes, times)
    override fun info(handle: Long, info: IntArray) = HudFrames.nativeInfo(handle, info)
    override fun close(handle: Long) = HudFrames.nativeClose(handle)
}
