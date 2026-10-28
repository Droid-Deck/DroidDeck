package com.droiddeck.launcher.stores.epic

import android.content.Context
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoreLanguage
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.SavedQueue
import com.droiddeck.launcher.stores.maintenance.MaintenanceAction
import com.droiddeck.launcher.stores.maintenance.MaintenanceCheck
import com.droiddeck.launcher.stores.maintenance.MaintenanceCheckState
import com.droiddeck.launcher.stores.maintenance.MaintenanceProposal
import com.droiddeck.launcher.stores.maintenance.MaintenanceRecovery
import com.droiddeck.launcher.stores.maintenance.RecoveryState
import com.droiddeck.launcher.stores.maintenance.StoreMaintenance
import com.droiddeck.launcher.stores.maintenance.StoreMaintenanceAdapter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object EpicMaintenanceAdapter : StoreMaintenanceAdapter {
    override val store: Store = Store.EPIC
    private const val TARGET_SCHEMA = 1
    private const val INSTALLED_MANIFEST = ".droiddeck-maintenance/epic/installed.manifest"

    override fun capabilities(game: InstalledStoreGame): Set<MaintenanceAction> = buildSet {
        val extra = game.sidecar.extra
        if (extra["namespace"].orEmpty().isNotBlank() && extra["catalogItemId"].orEmpty().isNotBlank()) add(MaintenanceAction.UPDATE)
        if (game.sidecar.installVersion.isNotBlank() && File(game.folder, INSTALLED_MANIFEST).isFile) add(MaintenanceAction.REPAIR)
    }

    override fun check(context: Context, game: InstalledStoreGame): MaintenanceCheck {
        val token = EpicCredentialStore.getValidAccessToken(context)
            ?: return MaintenanceCheck(MaintenanceCheckState.FAILED, reason = "Not signed in to Epic Games")
        val info = currentInfo(token, game.sidecar.id, game.sidecar.extra)
        val latest = info.optString("buildVersion")
        if (latest.isBlank()) return MaintenanceCheck(MaintenanceCheckState.UNKNOWN, reason = "Epic did not provide a build version")
        val installed = game.sidecar.extra["buildInfoVersion"].orEmpty().ifBlank { game.sidecar.installVersion }
        if (installed.isBlank()) return MaintenanceCheck(MaintenanceCheckState.UNKNOWN, latest, "The installed Epic build is unknown")
        return MaintenanceCheck(if (latest == installed) MaintenanceCheckState.CURRENT else MaintenanceCheckState.AVAILABLE, latest)
    }

    override fun prepare(context: Context, game: InstalledStoreGame, action: MaintenanceAction): MaintenanceProposal {
        require(action == MaintenanceAction.UPDATE || action == MaintenanceAction.REPAIR) { "Epic supports Update and Repair" }
        require(action in capabilities(game)) { "The installed Epic record is missing the data required for ${action.name.lowercase()}" }
        require(StoreMaintenance.readOperation(game.folder) == null) { "Resume or recover the existing Epic operation first" }
        val token = EpicCredentialStore.getValidAccessToken(context) ?: error("Not signed in to Epic Games")
        val info = currentInfo(token, game.sidecar.id, game.sidecar.extra)
        val tags = installedTags(context, game)
        val buildInfoVersion = info.optString("buildVersion").ifBlank { error("Epic did not provide a build version") }
        val dir = File(game.folder, ".droiddeck-maintenance/epic")
        require(!Files.isSymbolicLink(File(game.folder, ".droiddeck-maintenance").toPath()) && !Files.isSymbolicLink(dir.toPath())) {
            "Epic maintenance metadata is a symbolic link"
        }
        dir.mkdirs()
        val targetPath: File
        val manifestBytes: ByteArray
        val targetBuild: String
        val deploymentId: String
        if (action == MaintenanceAction.REPAIR) {
            targetPath = File(game.folder, INSTALLED_MANIFEST)
            require(targetPath.isFile && !Files.isSymbolicLink(targetPath.toPath())) { "The installed Epic manifest is unavailable; repair cannot safely choose a target" }
            manifestBytes = targetPath.readBytes()
            val parsed = EpicNative.inspect(manifestBytes, JSONObject()) ?: error("The installed Epic manifest could not be read")
            require(!parsed.has("error")) { parsed.optString("error", "The installed Epic manifest could not be read") }
            targetBuild = parsed.optString("buildVersion").ifBlank { error("The installed Epic manifest has no build version") }
            deploymentId = game.sidecar.extra["deploymentId"].orEmpty()
        } else {
            manifestBytes = EpicNative.fetch("manifest.download", JSONObject().put("info", info))
                ?: error("Epic's update manifest could not be downloaded")
            val parsed = EpicNative.inspect(manifestBytes, JSONObject()) ?: error("Epic's update manifest could not be read")
            require(!parsed.has("error")) { parsed.optString("error", "Epic's update manifest could not be read") }
            targetBuild = parsed.optString("buildVersion").ifBlank { buildInfoVersion }
            deploymentId = info.optString("deploymentId")
            targetPath = File(dir, "target.manifest")
            val temp = File(dir, "target.manifest.tmp")
            require(!Files.isSymbolicLink(temp.toPath())) { "Epic maintenance metadata is a symbolic link" }
            temp.writeBytes(manifestBytes)
            try { Files.move(temp.toPath(), targetPath.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp.toPath(), targetPath.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        }
        val estimate = EpicNative.inspect(manifestBytes, JSONObject().put("op", "estimate").put("tags", JSONArray(tags)))
            ?: error("Epic's target manifest could not be checked")
        require(!estimate.has("error")) { estimate.optString("error", "Epic's target manifest could not be checked") }
        val downloadBytes = estimate.optLong("downloadBytes", -1L)
        val diskBytes = estimate.optLong("diskBytes", -1L)
        require(downloadBytes >= 0 && diskBytes >= 0) { "Epic's target manifest has an invalid size estimate" }
        val target = JSONObject().put("schema", TARGET_SCHEMA).put("buildVersion", targetBuild)
            .put("buildInfoVersion", if (action == MaintenanceAction.REPAIR) game.sidecar.extra["buildInfoVersion"].orEmpty() else buildInfoVersion)
            .put("deploymentId", deploymentId)
            .put("manifestPath", targetPath.absolutePath).put("tags", JSONArray(tags))
        return MaintenanceProposal(
            game.folder.canonicalFile, game.sidecar, StoreInstallRoot.labelFor(context, game.folder), action,
            downloadBytes = downloadBytes, diskBytes = diskBytes,
            description = "Up to ${com.droiddeck.launcher.stores.formatBytes(downloadBytes)} of unique Epic chunks; files are SHA-1 verified against the pinned manifest.",
            target = target,
        )
    }

    override fun enqueue(context: Context, game: InstalledStoreGame, proposal: MaintenanceProposal) {
        check(proposal.operation == MaintenanceAction.UPDATE || proposal.operation == MaintenanceAction.REPAIR)
        check(!DownloadQueue.isActive(game.key)) { "Epic maintenance is already queued" }
        val target = validateTarget(game, proposal.target)
        enqueueJob(context, game, target, proposal.downloadBytes, proposal.operation)
    }

    override fun resume(context: Context, game: InstalledStoreGame, operation: JSONObject) {
        require(operation.optString("phase") in setOf("queued", "download", "applying")) { "The Epic operation needs attention before it can resume" }
        val target = validateTarget(game, operation.getJSONObject("target"))
        enqueueJob(context, game, target, operation.optLong("downloadBytes", 0L).coerceAtLeast(0L),
            MaintenanceAction.valueOf(operation.getString("operation")))
    }

    override fun recovery(context: Context, game: InstalledStoreGame): MaintenanceRecovery {
        val phase = runCatching { StoreMaintenance.readOperation(game.folder)?.optString("phase").orEmpty() }.getOrDefault("")
        return if (phase in setOf("queued", "download", "applying")) MaintenanceRecovery(RecoveryState.RESUMABLE, phase)
        else if (phase.isNotEmpty()) MaintenanceRecovery(RecoveryState.NEEDS_ATTENTION, phase) else MaintenanceRecovery()
    }

    private fun validateTarget(game: InstalledStoreGame, target: JSONObject): JSONObject {
        require(target.optInt("schema") == TARGET_SCHEMA) { "The saved Epic maintenance target is unsupported" }
        require(target.optString("buildVersion").isNotBlank()) { "The saved Epic target has no build version" }
        val rawPath = File(target.optString("manifestPath"))
        require(!Files.isSymbolicLink(rawPath.toPath())) { "The saved Epic manifest is a symbolic link" }
        val path = rawPath.canonicalFile
        require(path.path.startsWith(game.folder.canonicalPath + File.separator) && path.isFile) {
            "The saved Epic manifest is unavailable or outside the install folder"
        }
        val parsed = EpicNative.inspect(path.readBytes(), JSONObject()) ?: error("The saved Epic manifest could not be verified")
        require(!parsed.has("error") && parsed.optString("buildVersion") == target.optString("buildVersion")) {
            "The saved Epic manifest does not match the pinned build"
        }
        return JSONObject(target.toString()).put("manifestPath", path.absolutePath)
    }

    private fun currentInfo(token: String, id: String, extra: Map<String, String>): JSONObject {
        val namespace = extra["namespace"].orEmpty().ifBlank { error("The Epic namespace is missing from the install record") }
        val catalog = extra["catalogItemId"].orEmpty().ifBlank { error("The Epic catalog item ID is missing from the install record") }
        val raw = EpicApiClient.getManifestApiJson(token, namespace, catalog, extra["appName"] ?: id)
            ?: error("Epic's current build information could not be fetched")
        return JSONObject(raw)
    }

    private fun installedTags(context: Context, game: InstalledStoreGame): List<String> = runCatching {
        val raw = game.sidecar.extra["installTags"].orEmpty()
        val array = JSONArray(raw)
        List(array.length()) { array.getString(it) }.filter { it.isNotBlank() }
    }.getOrElse {
        EpicInstallTags.tagsFor(StoreLanguage.localeFor(game.sidecar.extra[StoreLanguage.EXTRA], context))
    }

    private fun enqueueJob(context: Context, game: InstalledStoreGame, target: JSONObject, bytes: Long, action: MaintenanceAction) {
        val app = context.applicationContext
        val sidecar = game.sidecar
        val extra = LinkedHashMap(sidecar.extra)
        val item = CatalogItem(Store.EPIC, sidecar.id, sidecar.title, sidecar.hero, tallImageUrl = sidecar.cover,
            extra = extra + mapOf(StoreMaintenance.OPERATION_KEY to action.name, StoreMaintenance.FOLDER_KEY to game.folder.absolutePath))
        SavedQueue.remember(app, item, null)
        DownloadQueue.enqueue(app, DownloadEntry(Store.EPIC, item.id, item.title, cover = item.tallImageUrl,
            bytesTotal = bytes, location = StoreInstallRoot.labelFor(app, game.folder))) {
            EpicInstaller(app, item, game.folder, target, game)
        }
    }
}
