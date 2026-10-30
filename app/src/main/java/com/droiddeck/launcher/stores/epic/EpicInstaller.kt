package com.droiddeck.launcher.stores.epic

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.WinCompSources
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreExe
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoreInstalls
import com.droiddeck.launcher.stores.StoreLanguage
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadStage
import com.droiddeck.launcher.stores.download.StoreDownloadTier
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.maintenance.StoreMaintenance
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class EpicInstaller(
    private val app: Context,
    private val item: CatalogItem,
    private val folder: File,
    private val maintenance: JSONObject? = null,
    private val maintenanceGame: InstalledStoreGame? = null,
) : DownloadQueue.DownloadJob {
    private val cancelled = AtomicBoolean(false)
    private val namespace = item.extra["namespace"].orEmpty()
    private val catalogItemId = item.extra["catalogItemId"].orEmpty()

    private fun scratch(): File = StoreInstallRoot.scratchDir(app, Store.EPIC, item.id)

    private fun installLocale() = StoreLanguage.localeFor(item.extra[StoreLanguage.EXTRA], app)

    override fun run(handle: DownloadQueue.JobHandle): String? {
        handle.stage(DownloadStage.MANIFEST, app.getString(R.string.stores_sync_sign_in))
        val token = EpicCredentialStore.getValidAccessToken(app) ?: throw IllegalStateException("Not signed in to Epic Games")
        val cover = item.tallImageUrl ?: item.imageUrl
        val tags = maintenance?.optJSONArray("tags")?.let { a -> List(a.length()) { a.optString(it) } }
            ?: EpicInstallTags.tagsFor(installLocale())
        val priorTagsRaw = maintenanceGame?.sidecar?.extra?.get("installTags")
            ?: StoreGameSidecar.read(folder)?.extra?.get("installTags").orEmpty()
        val previousTags = runCatching {
            if (priorTagsRaw.isBlank()) tags else JSONArray(priorTagsRaw).let { a -> List(a.length()) { a.optString(it) } }
        }.getOrDefault(tags)
        if (maintenance == null) StoreInstalls.begin(folder, Store.EPIC, item.id, item.title, cover, item.imageUrl)
        if (cancelled.get()) return null

        handle.stage(DownloadStage.MANIFEST, "Reading the build list…")
        val info = EpicApiClient.getManifestApiJson(token, namespace, catalogItemId, item.id)
            ?: throw IllegalStateException("The manifest could not be fetched")
        if (cancelled.get()) return null

        val tier = StoreDownloadTier.current(app)
        val request = JSONObject()
            .put("info", JSONObject(info))
            .put("gameDir", folder.absolutePath)
            .put("scratchDir", if (StoreInstallRoot.isRemovable(app, folder)) scratch().absolutePath else "")
            .put("storageLabel", StoreInstallRoot.labelFor(app, folder))
            .put("tags", JSONArray(tags))
            .put("previousTags", JSONArray(previousTags))
            .put("maxWorkers", tier.networkWindow.coerceIn(1, 128))
            .put("processWorkers", tier.processWorkers)
            .put("caBundle", "")
        maintenance?.let { request.put("targetManifestPath", it.getString("manifestPath")) }

        val shown = AtomicLong(-1)
        val installing = AtomicBoolean(false)
        var applyLease: AutoCloseable? = null
        val prefs = EpicPrefs.get(app)
        val listener = object : EpicNative.InstallListener {
            override fun onStatus(text: String) { handle.stage(DownloadStage.MANIFEST, text) }

            override fun onCheck(doneBytes: Long, totalBytes: Long, files: Int, filesTotal: Int) {
                handle.stage(DownloadStage.MANIFEST, "Checking files…")
                handle.stageProgress(doneBytes, totalBytes, files, filesTotal)
            }

            override fun onSizes(pendingBytes: Long, diskBytes: Long) {
                handle.diskSize(diskBytes)
                if (diskBytes > 0) prefs.edit().putLong("size_${item.id}", diskBytes).apply()
            }

            override fun onProgress(written: Long, total: Long, files: Int, filesTotal: Int) {
                if (installing.get()) return
                while (true) {
                    val before = shown.get()
                    if (written < before) return
                    if (shown.compareAndSet(before, written)) break
                }
                handle.progress(written, total, "Downloading chunks ($files/$filesTotal files)")
            }

            override fun onInstall(done: Long, total: Long, files: Int, filesTotal: Int) {
                installing.set(true)
                handle.stage(DownloadStage.INSTALL)
                handle.stageProgress(done, total, files, filesTotal, bytes = true)
            }

            override fun onLog(line: String) { handle.log("epic: $line") }

            override fun beforeReplace(): Boolean = try {
                if (maintenance == null) StoreInstalls.markUpdating(folder)
                else if (applyLease == null) applyLease = StoreMaintenance.beginApplyLease(maintenanceGame!!)
                true
            } catch (e: Exception) {
                handle.log("epic: the install record could not be switched to updating (${e.javaClass.simpleName})")
                false
            }

            override fun onComplete(success: Boolean, cancelled: Boolean, error: String, result: String) = Unit
        }

        val outcome = try { EpicNative.install(request, listener, cancelled) }
        finally { runCatching { applyLease?.close() } }
        if (outcome.cancelled || cancelled.get() && !outcome.success) return null
        if (!outcome.success) throw IllegalStateException(outcome.error.ifEmpty { "Native Epic install failed" })
        val result = outcome.result ?: JSONObject()

        if (maintenance != null && maintenanceGame != null) {
            if (applyLease == null) StoreMaintenance.beginApplyLease(maintenanceGame).close()
            val extra = LinkedHashMap(maintenanceGame.sidecar.extra)
            maintenance.optString("deploymentId").takeIf { it.isNotBlank() }?.let { extra["deploymentId"] = it }
            maintenance.optString("buildInfoVersion").takeIf { it.isNotBlank() }?.let { extra["buildInfoVersion"] = it }
            StoreMaintenance.complete(app, maintenanceGame, result.optString("buildVersion"), extra)
            return folder.path
        }

        handle.stage(DownloadStage.INSTALL, app.getString(R.string.stores_registering))
        val picked = StoreExe.pick(folder, result.optString("launchExe"), item.title)
        if (picked.isEmpty()) handle.log("epic: no exe found in ${folder.name}; pick one in Steam settings › Added games")
        val creds = EpicCredentialStore.load(app)
        val additional = EpicApiClient.getCustomAttribute(token, namespace, catalogItemId, "AdditionalCommandLine").orEmpty()
        val deployment = result.optString("deploymentId")
        val argsAnswer = EpicNative.call(
            "launch.args",
            JSONObject().put("displayName", creds?.displayName.orEmpty()).put("accountId", creds?.accountId.orEmpty())
                .put("namespace", namespace).put("locale", EpicInstallTags.launchLocale(installLocale())).put("appName", item.id)
                .put("deploymentId", deployment).put("additional", additional),
        )
        val argList = argsAnswer?.optJSONArray("args")
        val args = List(argList?.length() ?: 0) { argList!!.optString(it) }.filter { it.isNotEmpty() }

        val extra = LinkedHashMap<String, String>()
        extra["appName"] = item.id
        extra["namespace"] = namespace
        extra["catalogItemId"] = catalogItemId
        if (deployment.isNotEmpty()) extra["deploymentId"] = deployment
        extra["buildInfoVersion"] = JSONObject(info).optString("buildVersion")
        extra[StoreLanguage.EXTRA] = item.extra[StoreLanguage.EXTRA] ?: StoreLanguage.preferredCode(app)
        extra["installTags"] = JSONArray(EpicInstallTags.tagsFor(installLocale())).toString()
        extra[WinCompSources.EPIC_PREREQ_PATH] = result.optString("prereqPath")
        extra[WinCompSources.EPIC_PREREQ_NAME] = result.optString("prereqName")
        if (result.optBoolean("prereqRead", false)) extra[WinCompSources.LIST_READ] = "1"

        val sidecar = StoreGameSidecar(
            Store.EPIC, item.id, item.title, exe = picked.ifEmpty { "game.exe" }, args = args,
            installVersion = result.optString("buildVersion"), installedAt = System.currentTimeMillis(),
            cover = cover, hero = item.imageUrl, extra = extra, logo = item.logoUrl,
        )
        StoreInstalls.complete(app, folder, sidecar, onStep = { done, steps -> handle.stageProgress(done.toLong(), steps.toLong()) })
        val disk = result.optLong("diskBytes", 0L)
        if (disk > 0) prefs.edit().putLong("size_${item.id}", disk).apply()
        return folder.path
    }

    override fun cancel(deleteFiles: Boolean) {
        cancelled.set(true)
        if (deleteFiles) StoreInstalls.discard(app, folder, listOf(File(folder, ".chunks"), scratch()))
    }
}
