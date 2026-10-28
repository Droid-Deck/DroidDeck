package com.droiddeck.launcher.stores.xbox

import android.content.Context
import com.droiddeck.launcher.stores.*
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadStage
import com.droiddeck.launcher.stores.maintenance.StoreMaintenance
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class XboxInstaller(private val app: Context, private val item: CatalogItem, private val folder: File,
    private val maintenanceGame: InstalledStoreGame? = null) : DownloadQueue.DownloadJob {
    private val cancelled = AtomicBoolean(false)
    private val paused = CountDownLatch(1)
    @Volatile private var child: Xodus.Child? = null
    @Volatile private var delete = false

    override fun run(handle: DownloadQueue.JobHandle): String? {
        if (cancelled.get()) return null
        check(StoreAccounts.signedInAs(app, Store.XBOX) != null) { "Sign in to Xbox before installing." }
        val market = item.extra["market"]?.takeIf { it.matches(Regex("[A-Z]{2}")) } ?: Xodus.market()
        val selected = item.extra["contentId"].orEmpty()
        require(selected.matches(Regex("[a-fA-F0-9-]{36}"))) { "Select a PC game package before installing." }
        val content = !item.extra["baseId"].isNullOrBlank()
        if (content) {
            check(!XboxRuntime.isActive(item.extra["baseId"].orEmpty())) { "Close the base game before changing Xbox content." }
            check(item.id in XboxManifest.read(XboxContent.base(app, item).folder).related) { "This content is not related to the base game." }
            val base = XboxContent.base(app, item)
            require(folder.canonicalPath == base.folder.canonicalPath + "/.xbox-content/${item.id}")
            File(folder, ".droiddeck-content.json").delete()
            folder.mkdirs()
        } else {
            check(!XboxRuntime.isActive(item.id)) { "Close this Xbox game before updating it." }
            if (maintenanceGame == null) {
                StoreInstalls.markUpdating(folder)
                StoreInstalls.begin(folder, Store.XBOX, item.id, item.title, item.tallImageUrl, item.imageUrl)
            }
        }
        var complete = false
        var sequence = -1L
        try {
            val source = if (content) selected else item.id
            val guestFolder = StoreInstallRoot.guestPathForXodus(app, folder)
            val selection = if (content) emptyList() else listOf("--selected-content-id", selected)
            val stream = stream@ {
                Xodus.start(app, listOf("streaming", source, guestFolder, "--market", market,
                    "--progress-json", "--control-stdin") + selection).use { process ->
                child = process
                val rejected = AtomicBoolean(false)
                val rejectedLine = Regex(Xodus.SIGN_IN_REJECTED)
                val diagnostics = Xodus.diagnostics(process) { line ->
                    StoresState.logLine("Xbox: $line")
                    if (line.matches(rejectedLine)) rejected.set(true)
                }
                if (cancelled.get()) process.process.destroy()
                process.process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                    if (line.startsWith("NEXUS_PROGRESS ")) {
                        val progress = JSONObject(line.removePrefix("NEXUS_PROGRESS "))
                        require(progress.getInt("schema") == 1 && progress.getLong("sequence") > sequence)
                        sequence = progress.getLong("sequence")
                        if (progress.optBoolean("paused")) paused.countDown()
                        when (progress.getString("phase")) {
                            "complete" -> complete = true
                            "downloading", "download", "transfer" -> handle.progress(progress.getLong("completedBytes"), progress.optLong("totalBytes"))
                            "finalizing", "installing" -> handle.stage(DownloadStage.INSTALL)
                            else -> handle.stage(DownloadStage.MANIFEST)
                        }
                    }
                } }
                check(process.process.waitFor(15, TimeUnit.SECONDS)) { "Xbox installer did not exit." }
                diagnostics.join(2000)
                if (cancelled.get()) return@stream
                check(!rejected.get()) { app.getString(com.droiddeck.launcher.R.string.stores_xbox_sign_in_again) }
                check(process.process.exitValue() == 0 && complete) { "Xbox download did not complete. Resume to retry with fresh licenses." }
                }
            }
            if (maintenanceGame != null) StoreMaintenance.withApplyLease(maintenanceGame, stream) else stream()
            if (cancelled.get()) return null
            if (content) {
                XboxContent.complete(app, item, folder)
                return folder.path
            }
            val exe = executable(folder)
            val version = XboxManifest.read(folder).version
            if (maintenanceGame != null) {
                StoreMaintenance.complete(app, maintenanceGame, version,
                    maintenanceSidecarExtra(maintenanceGame.sidecar.extra, selected, market))
            } else StoreInstalls.complete(app, folder, StoreGameSidecar(Store.XBOX, item.id, item.title, exe,
                installVersion = version, installedAt = System.currentTimeMillis(), cover = item.tallImageUrl, hero = item.imageUrl,
                extra = maintenanceSidecarExtra(item.extra, selected, market)))
            return folder.path
        } finally {
            child = null
            if (delete && !item.extra["baseId"].isNullOrBlank()) StoreInstalls.deleteTree(folder)
            else if (delete) StoreInstalls.discard(app, folder, emptyList())
        }
    }

    override fun cancel(deleteFiles: Boolean) {
        cancelled.set(true)
        delete = deleteFiles && maintenanceGame == null
        val process = child
        if (process == null) {
            if (delete && !item.extra["baseId"].isNullOrBlank()) StoreInstalls.deleteTree(folder)
            else if (delete) StoreInstalls.discard(app, folder, emptyList())
            return
        }
        Thread({
            runCatching {
                process.send(JSONObject().put("schema", 1).put("command", "pause"))
                paused.await(3, TimeUnit.SECONDS)
            }
            process.close()
        }, "xbox-download-stop").start()
    }

    companion object {
        internal fun executable(folder: File): String = XboxManifest.executable(folder)

        internal fun maintenanceSidecarExtra(before: Map<String, String>, contentId: String, market: String): Map<String, String> =
            LinkedHashMap(before).apply {
                remove(StoreMaintenance.OPERATION_KEY)
                remove(StoreMaintenance.FOLDER_KEY)
                put("contentId", contentId)
                put("market", market)
            }
    }
}
