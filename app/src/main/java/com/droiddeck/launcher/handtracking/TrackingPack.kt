package com.droiddeck.launcher.handtracking

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.TarZst
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Hand-tracking models and the OpenCV/MediaPipe native libraries, downloaded on first use. */
internal object TrackingPack {
    sealed interface State {
        data object Missing : State
        data class Downloading(val progress: Float) : State
        data object Failed : State
        data object Ready : State
    }

    const val HAND = "hand_landmarker.task"
    const val HAND_LITE = "hand_landmarker_lite.task"
    const val POSE = "pose_landmarker_lite.task"
    private const val OPENCV = "libopencv_java4.so"
    private const val MEDIAPIPE = "libmediapipe_tasks_jni.so"
    private val FILES = listOf(HAND, HAND_LITE, POSE, OPENCV, MEDIAPIPE)
    private const val COMPLETE = ".complete"
    private const val TAG = "TrackingPack"

    val size: Long get() = BuildConfig.TRACKING_PACK_SIZE

    private val mutableState = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile private var nativeLoaded = false

    private fun root(context: Context) = File(context.filesDir, "addons")
    private fun dir(context: Context) = File(root(context), BuildConfig.TRACKING_PACK_TAG)

    fun installed(context: Context): Boolean = File(dir(context), COMPLETE).isFile

    fun refresh(context: Context): State {
        val now = mutableState.value
        if (now is State.Downloading) return now
        return (if (installed(context)) State.Ready else if (now == State.Failed) State.Failed else State.Missing)
            .also { mutableState.value = it }
    }

    fun file(context: Context, name: String): File {
        require(name in FILES)
        return File(dir(context), name)
    }

    fun model(context: Context, name: String): ByteBuffer =
        FileInputStream(file(context, name)).use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.channel.size()) }

    /** Starts the download in the background unless the pack is already present or on its way. */
    fun ensure(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (installed(app)) { mutableState.value = State.Ready; return }
            if (mutableState.value is State.Downloading) return
            mutableState.value = State.Downloading(0f)
        }
        scope.launch {
            val ok = runCatching { install(app) }.onFailure { Log.w(TAG, "install failed", it) }.getOrDefault(false)
            mutableState.value = if (ok) State.Ready else State.Failed
            if (ok) HandTracking.load(app)
        }
    }

    private fun install(context: Context): Boolean {
        val root = root(context).apply { mkdirs() }
        val archive = File(root, "${BuildConfig.TRACKING_PACK_TAG}.tar.zst")
        if (!Downloader.downloadFile(BuildConfig.TRACKING_PACK_URL, archive, true, BuildConfig.TRACKING_PACK_SHA256) { p ->
                mutableState.value = State.Downloading(p.coerceIn(0f, 1f))
            }) return false
        val staging = File(root, "${BuildConfig.TRACKING_PACK_TAG}.partial")
        staging.deleteRecursively()
        try {
            if (!TarZst.extract(FileInputStream(archive), staging)) return false
            check(FILES.all { File(staging, it).isFile }) { "tracking pack is incomplete" }
            File(staging, COMPLETE).writeText(BuildConfig.TRACKING_PACK_SHA256)
            val target = dir(context)
            target.deleteRecursively()
            check(staging.renameTo(target)) { "could not move tracking pack into place" }
        } finally {
            staging.deleteRecursively()
            archive.delete()
        }
        root.listFiles().orEmpty().filter { it.name != BuildConfig.TRACKING_PACK_TAG && it.name.startsWith("tracking-pack-") }
            .forEach { it.deleteRecursively() }
        return true
    }

    /**
     * Loads OpenCV and MediaPipe from the pack. MediaPipe's own System.loadLibrary then resolves to the
     * already-loaded library by soname, so this must run before any MediaPipe task class initializes.
     */
    fun loadNative(context: Context): Boolean {
        if (nativeLoaded) return true
        synchronized(lock) {
            if (nativeLoaded) return true
            if (!installed(context)) return false
            return runCatching {
                System.load(file(context, OPENCV).absolutePath)
                System.load(file(context, MEDIAPIPE).absolutePath)
                nativeLoaded = true
                true
            }.onFailure { Log.e(TAG, "native libraries failed to load", it) }.getOrDefault(false)
        }
    }

    /** Blocks the calling worker thread until the pack is usable, starting the download if needed. */
    fun awaitNative(context: Context, running: () -> Boolean, onState: (State) -> Unit): Boolean {
        if (loadNative(context)) return true
        ensure(context)
        while (running()) {
            val now = state.value
            onState(now)
            when (now) {
                State.Ready -> return loadNative(context)
                State.Failed -> return false
                else -> try { Thread.sleep(250) } catch (e: InterruptedException) { return false }
            }
        }
        return false
    }
}
