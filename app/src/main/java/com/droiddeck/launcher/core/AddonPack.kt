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
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream

/**
 * A pack built alongside the APK (tools/packs). Builds without a guaranteed content-addressed release
 * carry the exact archive as an APK asset.
 */
internal class AddonPack(
    val name: String, private val sha256: String, val size: Long, private val required: List<String>,
    private val reuseDeviceCopy: Boolean = sha256.isEmpty() && !BuildConfig.CI_BUILD,
    private val bundledAsset: ((Context) -> InputStream?)? = null,
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

    // A local build without a generated pack can only reuse a verified copy already on-device.
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

    private fun copyBundledPack(context: Context, archive: File): Boolean {
        val input = try {
            bundledAsset?.invoke(context) ?: context.assets.open("addon-packs/$name.tar.zst")
        } catch (_: FileNotFoundException) {
            return false
        } catch (e: Exception) {
            Log.w(TAG, "$name: cannot open bundled pack", e)
            return false
        }
        return try {
            input.use { source -> FileOutputStream(archive).use { source.copyTo(it) } }
            val correctSize = size <= 0L || archive.length() == size
            val correctDigest = Hashes.sha256(archive).equals(sha256, ignoreCase = true)
            if (!correctSize || !correctDigest) {
                Log.w(TAG, "$name: bundled pack does not match its pinned size or SHA-256")
                archive.delete()
                false
            } else {
                File("${archive.path}.resume.properties").delete()
                true
            }
        } catch (e: Exception) {
            archive.delete()
            Log.w(TAG, "$name: cannot copy bundled pack", e)
            false
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
        val downloaded = copyBundledPack(context, archive) || urls.any { url ->
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
