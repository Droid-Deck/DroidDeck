package com.droiddeck.launcher.handtracking

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.AddonPack
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Hand-tracking models and the OpenCV/MediaPipe native libraries, downloaded on first use. */
internal object TrackingPack {
    const val HAND = "hand_landmarker.task"
    const val HAND_LITE = "hand_landmarker_lite.task"
    const val POSE = "pose_landmarker_lite.task"
    private const val OPENCV = "libopencv_java4.so"
    private const val MEDIAPIPE = "libmediapipe_tasks_jni.so"
    private const val TAG = "TrackingPack"

    private val pack = AddonPack.TRACKING
    val state get() = pack.state
    val size get() = pack.size
    private val lock = Any()
    @Volatile private var nativeLoaded = false

    fun installed(context: Context) = pack.installed(context)
    fun refresh(context: Context) = pack.refresh(context)
    fun ensure(context: Context) = pack.ensure(context) { HandTracking.load(it) }

    fun file(context: Context, name: String): File = pack.file(context, name)

    fun model(context: Context, name: String): ByteBuffer =
        FileInputStream(file(context, name)).use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.channel.size()) }

    /**
     * Loads OpenCV and MediaPipe from the pack. MediaPipe's own System.loadLibrary then resolves to the
     * already-loaded library by soname, so this must run before any MediaPipe task class initializes.
     */
    fun loadNative(context: Context): Boolean {
        if (nativeLoaded) return true
        synchronized(lock) {
            if (nativeLoaded) return true
            if (!pack.installed(context)) return false
            return runCatching {
                System.load(file(context, OPENCV).absolutePath)
                System.load(file(context, MEDIAPIPE).absolutePath)
                nativeLoaded = true
                true
            }.onFailure { Log.e(TAG, "native libraries failed to load", it) }.getOrDefault(false)
        }
    }

    /** Blocks the calling worker thread until the pack is usable, starting the download if needed. */
    fun awaitNative(context: Context, running: () -> Boolean, onState: (AddonPack.State) -> Unit): Boolean =
        pack.await(context, running, onState) && loadNative(context)
}
