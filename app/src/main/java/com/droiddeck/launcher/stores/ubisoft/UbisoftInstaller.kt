package com.droiddeck.launcher.stores.ubisoft

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreExe
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstalls
import com.droiddeck.launcher.stores.StoreLanguage
import com.droiddeck.launcher.stores.amazon.AmazonNative
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadStage
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class UbisoftInstaller(
    private val app: Context,
    private val item: CatalogItem,
    private val folder: File,
) : DownloadQueue.DownloadJob {
    private val cancelled = AtomicBoolean(false)

    override fun run(handle: DownloadQueue.JobHandle): String? {
        handle.stage(DownloadStage.MANIFEST, app.getString(R.string.stores_sync_sign_in))
        val s = UbisoftAuth.current(app) ?: throw IllegalStateException("Not signed in to Ubisoft")
        val product = item.extra["productId"]?.toLongOrNull() ?: throw IllegalStateException("Ubisoft lists no download for ${item.title} on this account")
        val cover = item.tallImageUrl ?: item.imageUrl
        StoreInstalls.begin(folder, Store.UBISOFT, item.id, item.title, cover, item.imageUrl)
        if (cancelled.get()) return null
        val locale = StoreLanguage.localeFor(null, app)
        val request = JSONObject()
            .put("ticket", s.ticket)
            .put("sessionId", s.sessionId)
            .put("productId", product)
            .put("manifest", item.extra["manifest"].orEmpty())
            .put("folder", folder.absolutePath)
            .put("language", "${locale.language}-${locale.country}")
        val shown = AtomicLong(-1)
        val downloading = AtomicBoolean(false)
        val listener = object : AmazonNative.InstallListener {
            override fun onStatus(text: String) { handle.stage(DownloadStage.MANIFEST, text) }
            override fun onCheck(doneBytes: Long, totalBytes: Long, files: Int, filesTotal: Int) {
                handle.stageProgress(doneBytes, totalBytes, files, filesTotal, bytes = true)
            }
            override fun onSizes(pendingBytes: Long, totalBytes: Long) { handle.diskSize(totalBytes) }
            override fun onProgress(done: Long, total: Long, files: Int, filesTotal: Int) {
                if (downloading.compareAndSet(false, true)) handle.stage(DownloadStage.DOWNLOAD)
                while (true) {
                    val before = shown.get()
                    if (done < before) return
                    if (shown.compareAndSet(before, done)) break
                }
                handle.progress(done, total, "Downloading ($files/$filesTotal files)")
            }
            override fun onLog(line: String) { handle.log(line) }
            override fun beforeReplace(): Boolean = true
            override fun onComplete(success: Boolean, cancelled: Boolean, error: String, result: String) = Unit
        }
        val outcome = UbisoftNative.install(request, listener, cancelled)
        if (outcome.cancelled || cancelled.get() && !outcome.success) return null
        if (!outcome.success) throw IllegalStateException(outcome.error.ifEmpty { "The Ubisoft install failed" })

        handle.stage(DownloadStage.INSTALL, app.getString(R.string.stores_registering))
        val configured = resolve(folder, item.extra["exe"].orEmpty())
        val exe = configured ?: StoreExe.pick(folder, null, item.title).ifEmpty { "game.exe" }
        handle.log("ubisoft: exe=$exe")
        val sidecar = StoreGameSidecar(
            Store.UBISOFT, item.id, item.title, exe = exe,
            installVersion = item.extra["manifest"].orEmpty(), installedAt = System.currentTimeMillis(),
            cover = cover, hero = item.imageUrl, cloud = false,
            extra = item.extra.filterKeys { it in KEPT } + listOfNotNull(
                outcome.result?.optJSONArray("chunks")?.let { UbisoftLaunch.CHUNKS to it.toString() },
                outcome.result?.optJSONObject("setup")?.let { UbisoftLaunch.SETUP to it.toString() },
            ),
        )
        StoreInstalls.complete(app, folder, sidecar, onStep = { done, steps -> handle.stageProgress(done.toLong(), steps.toLong()) })
        return folder.path
    }

    override fun cancel(deleteFiles: Boolean) {
        cancelled.set(true)
        if (deleteFiles) StoreInstalls.discard(app, folder, emptyList())
    }

    internal companion object {
        val KEPT = setOf("productId", "spaceId", "manifest", "appId")

        /** A configured exe the way Windows finds it: any letter case, else the same file name anywhere in the install. */
        fun resolve(folder: File, configured: String): String? {
            val rel = configured.replace('\\', '/').trimStart('/').takeIf { it.isNotEmpty() } ?: return null
            var at = folder
            for (part in rel.split('/')) {
                at = at.listFiles()?.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return byName(folder, rel.substringAfterLast('/'))
            }
            return if (at.isFile) at.relativeTo(folder).invariantSeparatorsPath else byName(folder, rel.substringAfterLast('/'))
        }

        private fun byName(folder: File, name: String): String? =
            folder.walkTopDown().maxDepth(5).firstOrNull { it.isFile && it.name.equals(name, ignoreCase = true) }?.relativeTo(folder)?.invariantSeparatorsPath
    }
}
