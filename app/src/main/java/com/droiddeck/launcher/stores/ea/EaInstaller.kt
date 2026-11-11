package com.droiddeck.launcher.stores.ea

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
import com.droiddeck.launcher.stores.download.StoreDownloadTier
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class EaInstaller(
    private val app: Context,
    private val item: CatalogItem,
    private val folder: File,
) : DownloadQueue.DownloadJob {
    private val cancelled = AtomicBoolean(false)

    override fun run(handle: DownloadQueue.JobHandle): String? {
        handle.stage(DownloadStage.MANIFEST, app.getString(R.string.stores_sync_sign_in))
        var token = EaAuth.accessToken(app) ?: throw IllegalStateException("Not signed in to EA")
        val url = try {
            EaApi.downloadUrl(token, item.id)
        } catch (e: EaApiException) {
            if (e.status != 401) throw IllegalStateException(e.message)
            token = EaAuth.refreshNow(app, token) ?: throw IllegalStateException("The EA sign-in has expired")
            EaApi.downloadUrl(token, item.id)
        }
        val game = EaLibrary.game(app, item.id)
        val version = runCatching { EaApi.offers(token, listOf(item.id)).firstOrNull()?.gameVersion }.getOrNull().orEmpty()
            .ifEmpty { game?.version.orEmpty() }
        val cover = item.tallImageUrl ?: item.imageUrl
        StoreInstalls.begin(folder, Store.EA, item.id, item.title, cover, item.imageUrl)
        if (cancelled.get()) return null

        val tier = StoreDownloadTier.current(app)
        handle.log("ea: \"${item.title}\" tier=${tier.id} window=${tier.networkWindow}")
        val request = JSONObject()
            .put("url", url)
            .put("gameDir", folder.absolutePath)
            .put("maxWorkers", tier.networkWindow.coerceIn(1, 64))
            .put("processWorkers", tier.processWorkers)
            .put("caBundle", "")
        val shown = AtomicLong(-1)
        val downloading = AtomicBoolean(false)
        val listener = object : AmazonNative.InstallListener {
            override fun onStatus(text: String) { handle.stage(DownloadStage.MANIFEST, text) }
            override fun onCheck(doneBytes: Long, totalBytes: Long, files: Int, filesTotal: Int) {
                handle.stage(DownloadStage.MANIFEST, "Checking files…")
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
            override fun onLog(line: String) { handle.log("ea: $line") }
            override fun beforeReplace(): Boolean = try {
                StoreInstalls.markUpdating(folder)
                true
            } catch (e: Exception) {
                handle.log("ea: the install record could not be switched to updating (${e.javaClass.simpleName})")
                false
            }
            override fun onComplete(success: Boolean, cancelled: Boolean, error: String, result: String) = Unit
        }
        val outcome = EaNative.install(request, listener, cancelled)
        if (outcome.cancelled || cancelled.get() && !outcome.success) return null
        if (!outcome.success) throw IllegalStateException(outcome.error.ifEmpty { "The EA install failed" })
        val result = outcome.result ?: JSONObject()
        result.optLong("totalBytes").takeIf { it > 0 }?.let { EaPrefs.get(app).edit().putLong("size_${item.id}", it).apply() }

        handle.stage(DownloadStage.INSTALL, app.getString(R.string.stores_registering))
        val sidecar = sidecarFor(app, item, folder, game, version, cover) { handle.log("ea: $it") }
        StoreInstalls.complete(app, folder, sidecar, onStep = { done, steps -> handle.stageProgress(done.toLong(), steps.toLong()) })
        return folder.path
    }

    override fun cancel(deleteFiles: Boolean) {
        cancelled.set(true)
        if (deleteFiles) StoreInstalls.discard(app, folder, emptyList())
    }

    companion object {
        fun locale(context: Context, available: List<String>): String {
            val wanted = StoreLanguage.localeFor(null, context)
            val exact = "${wanted.language}_${wanted.country}"
            return available.firstOrNull { it.equals(exact, true) }
                ?: available.firstOrNull { it.substringBefore('_').equals(wanted.language, true) }
                ?: available.firstOrNull { it.equals("en_US", true) }
                ?: "en_US"
        }

        internal fun sidecarFor(app: Context, item: CatalogItem, folder: File, game: EaGame?, version: String, cover: String?, log: (String) -> Unit): StoreGameSidecar {
            val manifest = EaManifest.read(folder)
            if (manifest == null) log("no installerdata.xml in ${folder.name}")
            val launcher = manifest?.launcher()
            val candidates = listOfNotNull(launcher?.path, game?.executePath?.takeIf { it.isNotEmpty() })
            var exe = candidates.firstNotNullOfOrNull { EaPaths.relativeOnDisk(folder, EaPaths.relative(it)) }.orEmpty()
            if (exe.isEmpty()) exe = StoreExe.pick(folder, null, item.title)
            if (exe.isEmpty()) { log("no exe found; pick one in Steam settings › Added games"); exe = "game.exe" } else log("exe=$exe")
            val args = EaPaths.splitArgs(listOf(game?.parameters.orEmpty(), launcher?.parameters.orEmpty()).filter { it.isNotBlank() }.joinToString(" "))
            val keys = JSONArray()
            listOfNotNull(game?.installCheck, game?.executePath, launcher?.path).mapNotNull { EaPaths.registered(it) }
                .distinctBy { it.key.lowercase() + "|" + it.value.lowercase() }
                .forEach { keys.put(JSONArray().put(it.key).put(it.value)) }
            val extra = linkedMapOf(
                "contentId" to (game?.contentId ?: item.extra["contentId"].orEmpty()),
                "slug" to (game?.slug ?: item.extra["slug"].orEmpty()),
                "multiplayerId" to game?.multiplayerId.orEmpty(),
                "masterTitleId" to game?.masterTitleId.orEmpty(),
                "locale" to locale(app, game?.locales.orEmpty()),
                "registry" to keys.toString(),
                "touchup" to manifest?.touchupPath.orEmpty().replace('\\', '/').trimStart('/'),
                "touchupArgs" to manifest?.touchupParameters.orEmpty(),
                "contentIds" to manifest?.contentIds.orEmpty().joinToString(","),
                "displayName" to manifest?.title(locale(app, game?.locales.orEmpty())).orEmpty(),
            ).filterValues { it.isNotEmpty() }
            return StoreGameSidecar(
                Store.EA, item.id, item.title, exe = exe, args = args,
                installVersion = version.ifEmpty { manifest?.version.orEmpty() }, installedAt = System.currentTimeMillis(),
                cover = cover, hero = item.imageUrl, extra = extra, cloud = false, logo = item.logoUrl,
            )
        }
    }
}
