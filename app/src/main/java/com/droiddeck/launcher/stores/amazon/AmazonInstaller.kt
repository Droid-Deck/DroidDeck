package com.droiddeck.launcher.stores.amazon

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import com.droiddeck.launcher.stores.StoreExe
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstalls
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadStage
import com.droiddeck.launcher.stores.download.StoreDownloadTier
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class AmazonInstaller(
    private val app: Context,
    private val item: CatalogItem,
    private val folder: File,
) : DownloadQueue.DownloadJob {
    private val cancelled = AtomicBoolean(false)

    override fun run(handle: DownloadQueue.JobHandle): String? {
        handle.stage(DownloadStage.MANIFEST, app.getString(R.string.stores_sync_sign_in))
        val token = AmazonCredentialStore.getValidAccessToken(app) ?: throw IllegalStateException("Not signed in to Amazon Games")
        val entitlementId = item.extra["entitlementId"].orEmpty()
        val sku = item.extra["sku"].orEmpty()
        val cover = item.tallImageUrl ?: item.imageUrl
        StoreInstalls.begin(folder, Store.AMAZON, item.id, item.title, cover, item.imageUrl)
        if (cancelled.get()) return null

        val tier = StoreDownloadTier.current(app)
        handle.log("amazon: \"${item.title}\" tier=${tier.id} window=${tier.networkWindow} process_workers=${tier.processWorkers}")
        val request = JSONObject()
            .put("accessToken", token)
            .put("entitlementId", entitlementId)
            .put("gameDir", folder.absolutePath)
            .put("maxWorkers", tier.networkWindow.coerceIn(1, 128))
            .put("processWorkers", tier.processWorkers)
            .put("caBundle", "")

        val shown = AtomicLong(-1)
        val downloading = AtomicBoolean(false)
        val prefs = AmazonPrefs.get(app)
        val listener = object : AmazonNative.InstallListener {
            override fun onStatus(text: String) { handle.stage(DownloadStage.MANIFEST, text) }

            override fun onCheck(doneBytes: Long, totalBytes: Long, files: Int, filesTotal: Int) {
                handle.stage(DownloadStage.MANIFEST, "Checking files…")
                handle.stageProgress(doneBytes, totalBytes, files, filesTotal, bytes = true)
            }

            override fun onSizes(pendingBytes: Long, totalBytes: Long) {
                handle.diskSize(totalBytes)
            }

            override fun onProgress(done: Long, total: Long, files: Int, filesTotal: Int) {
                if (downloading.compareAndSet(false, true)) handle.stage(DownloadStage.DOWNLOAD)
                while (true) {
                    val before = shown.get()
                    if (done < before) return
                    if (shown.compareAndSet(before, done)) break
                }
                handle.progress(done, total, "Downloading ($files/$filesTotal files)")
            }

            override fun onLog(line: String) { handle.log("amazon: $line") }

            override fun beforeReplace(): Boolean = try {
                StoreInstalls.markUpdating(folder)
                true
            } catch (e: Exception) {
                handle.log("amazon: the install record could not be switched to updating (${e.javaClass.simpleName})")
                false
            }

            override fun onComplete(success: Boolean, cancelled: Boolean, error: String, result: String) = Unit
        }

        val outcome = AmazonNative.install(request, listener, cancelled)
        if (outcome.cancelled || cancelled.get() && !outcome.success) return null
        if (!outcome.success) throw IllegalStateException(outcome.error.ifEmpty { "The Amazon install failed" })
        val result = outcome.result ?: JSONObject()

        handle.stage(DownloadStage.INSTALL, "Finishing…")
        val versionId = result.optString("versionId")
        val total = result.optLong("totalBytes", 0L)
        prefs.edit().apply {
            if (total > 0) putLong("size_${item.id}", total)
            putString("version_${item.id}", versionId)
        }.apply()

        handle.stage(DownloadStage.INSTALL, app.getString(R.string.stores_registering))
        val spec = AmazonNative.call("launch.spec", JSONObject().put("gameDir", folder.absolutePath).put("title", item.title))
            ?: throw IllegalStateException(AmazonNative.ENGINE_MISSING)
        spec.optString("warning").takeIf { it.isNotEmpty() && it != "null" }?.let { handle.log("amazon: $it") }
        var exe = spec.optString("exe")
        if (exe.isEmpty()) exe = StoreExe.pick(folder, null, item.title)
        if (exe.isEmpty()) {
            handle.log("amazon: no exe found in ${folder.name}; pick one in Steam settings › Added games")
            exe = "game.exe"
        } else {
            handle.log("amazon: exe=$exe (${spec.optString("source").ifEmpty { "picker" }})")
        }
        val argList = spec.optJSONArray("args")
        val args = List(argList?.length() ?: 0) { argList!!.optString(it) }.filter { it.isNotEmpty() }

        val display = StoreAccounts.signedInAs(app, Store.AMAZON).orEmpty()
        val envAnswer = AmazonNative.call(
            "launch.env",
            JSONObject().put("entitlementId", entitlementId).put("sku", sku).put("displayName", display),
        )?.optJSONArray("env") ?: throw IllegalStateException(AmazonNative.ENGINE_MISSING)
        val env = LinkedHashMap<String, String>()
        for (i in 0 until envAnswer.length()) {
            val pair = envAnswer.optJSONArray(i) ?: continue
            env[pair.optString(0)] = pair.optString(1)
        }

        val sidecar = StoreGameSidecar(
            Store.AMAZON, item.id, item.title, exe = exe, args = args, env = env,
            installVersion = versionId, installedAt = System.currentTimeMillis(),
            cover = cover, hero = item.imageUrl,
            extra = mapOf("entitlementId" to entitlementId, "sku" to sku),
        )
        StoreInstalls.complete(app, folder, sidecar, onStep = { done, steps -> handle.stageProgress(done.toLong(), steps.toLong()) })
        return folder.path
    }

    override fun cancel(deleteFiles: Boolean) {
        cancelled.set(true)
        if (deleteFiles) StoreInstalls.discard(app, folder, emptyList())
    }
}
