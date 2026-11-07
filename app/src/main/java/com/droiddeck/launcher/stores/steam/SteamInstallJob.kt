package com.droiddeck.launcher.stores.steam

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadStage
import com.droiddeck.launcher.stores.download.StoreDownloadTier
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Called by the engine on its own threads. */
interface SteamInstallListener {
    fun onProgress(doneBytes: Long, totalBytes: Long, phase: String?)
    fun onComplete(ok: Boolean, result: String?)
}

internal class SteamInstallJob(
    private val app: Context,
    private val appId: Int,
    private val libraryGuest: String,
) : DownloadQueue.DownloadJob {
    private val cancelled = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    @Volatile private var discard = false
    @Volatile private var installDir: String? = null
    @Volatile private var libraryHost: File? = null

    override fun run(handle: DownloadQueue.JobHandle): String? {
        started.set(true)
        try {
            return install(handle)
        } finally {
            if (discard) SteamStore.discardPartial(app, appId, libraryGuest, libraryHost, installDir)
        }
    }

    private fun install(handle: DownloadQueue.JobHandle): String? {
        handle.stage(DownloadStage.MANIFEST)
        if (SteamClient.clientMayRun()) throw IllegalStateException(app.getString(R.string.steam_store_close_steam))
        val engine = SteamClient.engineLibraries(app)
        val target = engine.libraries.firstOrNull { it.first.guest == libraryGuest }
            ?: throw IllegalStateException(app.getString(R.string.game_sync_err_steam_library))
        libraryHost = target.second
        val signIn = SteamEngine.signIn(app)
        if (!signIn.optBoolean("ok")) {
            SteamStore.signInFailed(app, signIn.optString("error"))
            throw IllegalStateException(SteamStore.errorText(app, signIn))
        }
        val language = SteamClient.language(app)
        val (plan, planned) = SteamStore.plan(app, appId, language)
        if (plan == null) throw IllegalStateException(SteamStore.errorText(app, planned))
        installDir = plan.installDir
        if (plan.installBytes > 0) handle.diskSize(plan.installBytes)
        if (cancelled.get()) return null

        val tier = StoreDownloadTier.current(app)
        val journal = File(app.filesDir, "${SteamStore.JOURNAL}/$appId").apply { mkdirs() }
        handle.log("steam: $appId tier=${tier.id} window=${tier.networkWindow} process_workers=${tier.processWorkers}")
        val done = CountDownLatch(1)
        val succeeded = AtomicBoolean(false)
        val answer = AtomicReference("")
        val listener = object : SteamInstallListener {
            private var stage: DownloadStage? = null
            private var shown = -1L

            override fun onProgress(doneBytes: Long, totalBytes: Long, phase: String?) {
                synchronized(this) {
                    val now = SteamLibraryFolders.stageOf(phase.orEmpty())
                    if (now != stage) {
                        stage = now
                        shown = -1L
                    }
                    if (doneBytes < shown) return
                    shown = doneBytes
                    if (now == DownloadStage.DOWNLOAD) handle.progress(doneBytes, totalBytes)
                    else {
                        handle.stage(now)
                        if (totalBytes > 0) handle.stageProgress(doneBytes, totalBytes, bytes = true)
                    }
                }
            }

            override fun onComplete(ok: Boolean, result: String?) {
                answer.set(result.orEmpty())
                succeeded.set(ok)
                done.countDown()
            }
        }
        val native = try {
            SteamNative.nativeStart(
                appId, language, SteamClient.root(app).absolutePath, target.second.absolutePath, libraryGuest, engine.json,
                journal.absolutePath, "", tier.networkWindow.coerceIn(1, 128), tier.processWorkers, listener,
            )
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException(SteamEngine.MISSING_TEXT)
        }
        if (native == 0L && done.count > 0L) throw IllegalStateException(SteamStore.errorText(app, parse(answer.get())))
        if (native != 0L) {
            try {
                var forwarded = false
                while (true) {
                    val finished = try {
                        done.await(CANCEL_POLL_MS, TimeUnit.MILLISECONDS)
                    } catch (e: InterruptedException) {
                        cancelled.set(true)
                        false
                    }
                    if (finished) break
                    if (!forwarded && cancelled.get()) {
                        runCatching { SteamNative.nativeCancel(native) }
                        forwarded = true
                    }
                }
            } finally {
                runCatching { SteamNative.nativeRelease(native) }
            }
        }
        val result = parse(answer.get())
        if (succeeded.get() && result.optBoolean("ok", true)) {
            journal.deleteRecursively()
            SteamStore.scanInstalled(app)
            return File(target.first.host, "steamapps/common/${plan.installDir}").path
        }
        if (cancelled.get() || result.optString("error") == "cancelled") return null
        throw IllegalStateException(SteamStore.errorText(app, result))
    }

    private fun parse(text: String): JSONObject =
        runCatching { JSONObject(text) }.getOrElse { SteamEngine.failure(text.ifBlank { "install failed" }) }

    override fun cancel(deleteFiles: Boolean) {
        cancelled.set(true)
        if (!deleteFiles) return
        discard = true
        if (!started.get()) SteamStore.discardPartial(app, appId, libraryGuest, null, null)
    }

    private companion object {
        const val CANCEL_POLL_MS = 250L
    }
}
