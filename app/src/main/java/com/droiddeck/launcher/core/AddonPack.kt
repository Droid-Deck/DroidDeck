package com.droiddeck.launcher.core

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream

/**
 * A pack built by CI alongside this APK (tools/packs). Main and release builds publish it to
 * DroidDeck-Components under a content-addressed tag; a PR test build attaches packs that are not there
 * yet to its pr-N release on DroidDeck-CI. The APK pins the pack's SHA-256, so only the bytes this build
 * was made with install, whichever host serves them.
 */
internal class AddonPack(
    val name: String, private val sha256: String, val size: Long, private val required: List<String>,
    private val reuseDeviceCopy: Boolean = sha256.isEmpty() && !BuildConfig.CI_BUILD,
) {
    sealed interface State {
        data object Missing : State
        data class Downloading(val progress: Float) : State
        data object Failed : State
        data object Ready : State
    }

    private val mutableState = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = mutableState.asStateFlow()
    private val lock = Any()

    val available: Boolean get() = sha256.isNotEmpty()
    private val tag get() = "pack-$name-${sha256.take(16)}"
    private val urls get() = listOfNotNull(
        "https://github.com/$REPO/releases/download/$tag/$name.tar.zst",
        BuildConfig.PR_NUMBER.takeIf { it > 0 }?.let { "https://github.com/$PR_REPO/releases/download/pr-$it/$tag.tar.zst" },
    )

    private fun root(context: Context) = File(context.filesDir, "addons/$name")
    /** App-private and written only after the archive matched the pinned digest. */
    fun dir(context: Context) = File(root(context), version(context))
    fun file(context: Context, relative: String) = File(dir(context), relative)

    fun installed(context: Context): Boolean = version(context).isNotEmpty() && File(dir(context), COMPLETE).isFile

    // A build made outside CI pins no packs, so it runs the newest one a CI build already verified and installed.
    private fun version(context: Context): String = if (!reuseDeviceCopy) sha256 else deviceCopy(context).orEmpty()

    private fun deviceCopy(context: Context): String? = root(context).listFiles().orEmpty()
        .filter { dir ->
            dir.isDirectory && runCatching { File(dir, COMPLETE).readText().trim() }.getOrNull() == dir.name &&
                required.all { File(dir, it).isFile }
        }
        .maxByOrNull { it.lastModified() }?.name

    fun refresh(context: Context): State {
        val now = mutableState.value
        if (now is State.Downloading) return now
        return (if (installed(context)) State.Ready else if (now == State.Failed) State.Failed else State.Missing)
            .also { mutableState.value = it }
    }

    /** Starts the download in the background unless the pack is already present or on its way. */
    fun ensure(context: Context, onReady: (Context) -> Unit = {}) {
        val app = context.applicationContext
        synchronized(lock) {
            if (installed(app)) { mutableState.value = State.Ready; return }
            if (mutableState.value is State.Downloading) return
            mutableState.value = State.Downloading(0f)
        }
        scope.launch {
            val ok = runCatching { install(app) }.onFailure { Log.w(TAG, "$name: install failed", it) }.getOrDefault(false)
            mutableState.value = if (ok) State.Ready else State.Failed
            if (ok) onReady(app)
        }
    }

    /** Blocks a worker thread until the pack is installed, starting the download if needed. */
    @JvmOverloads
    fun await(context: Context, running: () -> Boolean = { true }, onState: (State) -> Unit = {}): Boolean {
        if (installed(context)) return true
        ensure(context)
        while (running()) {
            val now = state.value
            onState(now)
            when (now) {
                State.Ready -> return installed(context)
                State.Failed -> return false
                else -> try { Thread.sleep(250) } catch (e: InterruptedException) { return false }
            }
        }
        return false
    }

    private fun install(context: Context): Boolean {
        if (!available) {
            Log.w(TAG, if (reuseDeviceCopy) "$name: this build has no pack and none is installed" else "$name: this build has no pack")
            return false
        }
        val root = root(context).apply { mkdirs() }
        val archive = File(root, "$sha256.tar.zst")
        val downloaded = urls.any { url ->
            Downloader.downloadFile(url, archive, true, sha256) { p -> mutableState.value = State.Downloading(p.coerceIn(0f, 1f)) }
                .also { if (!it) archive.delete() }
        }
        if (!downloaded) return false
        val staging = File(root, "$sha256.partial")
        staging.deleteRecursively()
        try {
            if (!FileInputStream(archive).use { TarZst.extract(it, staging) }) return false
            check(required.all { File(staging, it).isFile }) { "$name pack is incomplete" }
            File(staging, COMPLETE).writeText(sha256)
            val target = dir(context)
            target.deleteRecursively()
            check(staging.renameTo(target)) { "could not move the $name pack into place" }
        } finally {
            staging.deleteRecursively()
            archive.delete()
        }
        root.listFiles().orEmpty().filter { it.name != sha256 }.forEach { it.deleteRecursively() }
        return true
    }

    companion object {
        private const val TAG = "AddonPack"
        private const val REPO = "Droid-Deck/DroidDeck-Components"
        private const val PR_REPO = "Droid-Deck/DroidDeck-CI"
        const val COMPLETE = ".complete"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @JvmField
        val PROOT = AddonPack("proot", BuildConfig.PACK_PROOT_SHA256, BuildConfig.PACK_PROOT_SIZE,
            listOf("libproot.so", "libproot-loader.so"))
        @JvmField
        val DIRECTAUDIO = AddonPack("directaudio", BuildConfig.PACK_DIRECTAUDIO_SHA256, BuildConfig.PACK_DIRECTAUDIO_SIZE,
            listOf("linux-wine11/version.txt", "linux-wine11-systhread/version.txt"))
        val TRACKING = AddonPack("tracking", BuildConfig.PACK_TRACKING_SHA256, BuildConfig.PACK_TRACKING_SIZE, listOf(
            "hand_landmarker.task", "hand_landmarker_lite.task", "pose_landmarker_lite.task",
            "libopencv_java4.so", "libmediapipe_tasks_jni.so"))
        val XBOX = AddonPack("xbox", BuildConfig.PACK_XBOX_SHA256, BuildConfig.PACK_XBOX_SIZE,
            listOf("version", "xodus-cli", "xodus-service"))
        val COMET = AddonPack("comet", BuildConfig.PACK_COMET_SHA256, BuildConfig.PACK_COMET_SIZE,
            listOf("version", "comet"))
    }
}
