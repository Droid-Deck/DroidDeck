package com.droiddeck.launcher.stores.amazon

import android.content.Context
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreInstallRoot
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
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object AmazonMaintenanceAdapter : StoreMaintenanceAdapter {
    override val store: Store = Store.AMAZON
    private const val TARGET_SCHEMA = 1
    private const val INSTALLED_MANIFEST = ".droiddeck-maintenance/amazon/installed.manifest"

    override fun capabilities(game: InstalledStoreGame): Set<MaintenanceAction> = buildSet {
        if (game.sidecar.extra["entitlementId"].orEmpty().isNotBlank()) add(MaintenanceAction.UPDATE)
        if (game.sidecar.installVersion.isNotBlank() && File(game.folder, INSTALLED_MANIFEST).isFile) add(MaintenanceAction.REPAIR)
    }

    override fun check(context: Context, game: InstalledStoreGame): MaintenanceCheck {
        val token = AmazonCredentialStore.getValidAccessToken(context)
            ?: return MaintenanceCheck(MaintenanceCheckState.FAILED, reason = "Not signed in to Amazon Games")
        val entitlement = game.sidecar.extra["entitlementId"].orEmpty()
        require(entitlement.isNotBlank()) { "The installed Amazon record has no entitlement ID" }
        val latest = AmazonApiClient.getGameDownload(token, entitlement)
            ?: error("Amazon's current game version could not be checked")
        if (latest.versionId.isBlank()) return MaintenanceCheck(MaintenanceCheckState.UNKNOWN, reason = "Amazon did not provide a version ID")
        val installed = game.sidecar.installVersion
        if (installed.isBlank()) return MaintenanceCheck(MaintenanceCheckState.UNKNOWN, latest.versionId, "The installed Amazon version is unknown")
        return MaintenanceCheck(
            if (latest.versionId == installed) MaintenanceCheckState.CURRENT else MaintenanceCheckState.AVAILABLE,
            latest.versionId,
        )
    }

    override fun prepare(context: Context, game: InstalledStoreGame, action: MaintenanceAction): MaintenanceProposal {
        require(action == MaintenanceAction.UPDATE || action == MaintenanceAction.REPAIR) { "Amazon supports Update and Repair" }
        require(action in capabilities(game)) { "The installed Amazon record is missing the data required for ${action.name.lowercase()}" }
        require(StoreMaintenance.readOperation(game.folder) == null) { "Resume or recover the existing Amazon operation first" }
        val token = AmazonCredentialStore.getValidAccessToken(context) ?: error("Not signed in to Amazon Games")
        val entitlement = game.sidecar.extra["entitlementId"].orEmpty()
        val current = AmazonApiClient.getGameDownload(token, entitlement) ?: error("Amazon's current download is unavailable")
        require(current.versionId.isNotBlank()) { "Amazon did not provide a version ID" }
        val dir = File(game.folder, ".droiddeck-maintenance/amazon")
        require(!Files.isSymbolicLink(File(game.folder, ".droiddeck-maintenance").toPath())) { "Amazon maintenance metadata is a symbolic link" }
        require(!Files.isSymbolicLink(dir.toPath())) { "Amazon maintenance metadata is a symbolic link" }
        dir.mkdirs()
        val (version, path, bytes) = if (action == MaintenanceAction.REPAIR) {
            require(game.sidecar.installVersion.isNotBlank()) { "The installed Amazon version is unknown; repair cannot safely choose a target" }
            val installed = File(game.folder, INSTALLED_MANIFEST)
            require(installed.isFile && !Files.isSymbolicLink(installed.toPath())) { "The installed Amazon manifest is unavailable; repair cannot safely choose a target" }
            val raw = installed.readBytes()
            val info = AmazonNative.inspect(raw) ?: error("The installed Amazon manifest could not be verified")
            require(!info.has("error")) { info.optString("error", "The installed Amazon manifest could not be verified") }
            require(info.optInt("unverifiableFiles", 0) == 0) { "Amazon's installed manifest has file hashes DroidDeck cannot verify; Repair cannot safely replace those files" }
            Triple(game.sidecar.installVersion, installed, info.optLong("totalBytes", 0L))
        } else {
            val raw = AmazonApiClient.getBytes(AmazonApiClient.appendPath(current.downloadUrl, "manifest.proto"), token)
                ?: error("Amazon's update manifest could not be downloaded")
            val info = AmazonNative.inspect(raw) ?: error("Amazon's update manifest could not be read")
            require(!info.has("error")) { info.optString("error", "Amazon's update manifest could not be read") }
            require(info.optInt("unverifiableFiles", 0) == 0) { "Amazon's update manifest has file hashes DroidDeck cannot verify; it cannot safely replace those files" }
            val target = File(dir, "target.manifest")
            val temp = File(dir, "target.manifest.tmp")
            require(!Files.isSymbolicLink(temp.toPath())) { "Amazon maintenance metadata is a symbolic link" }
            temp.writeBytes(raw)
            try { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            Triple(current.versionId, target, info.optLong("totalBytes", 0L))
        }
        val target = JSONObject().put("schema", TARGET_SCHEMA).put("versionId", version)
            .put("manifestPath", path.absolutePath)
        return MaintenanceProposal(
            game.folder.canonicalFile, game.sidecar, "Version $version", action,
            downloadBytes = bytes, diskBytes = bytes, description = "Up to ${com.droiddeck.launcher.stores.formatBytes(bytes)} of whole-file data; existing files are verified against Amazon's manifest.",
            target = target,
        )
    }

    override fun enqueue(context: Context, game: InstalledStoreGame, proposal: MaintenanceProposal) {
        check(proposal.operation == MaintenanceAction.UPDATE || proposal.operation == MaintenanceAction.REPAIR)
        check(!DownloadQueue.isActive(game.key)) { "Amazon maintenance is already queued" }
        val target = validateTarget(game, proposal.target)
        enqueueJob(context, game, target, proposal.downloadBytes, proposal.operation)
    }

    override fun resume(context: Context, game: InstalledStoreGame, operation: JSONObject) {
        require(operation.optString("phase") in setOf("queued", "download", "applying")) { "The Amazon operation needs attention before it can resume" }
        val target = validateTarget(game, operation.getJSONObject("target"))
        val bytes = operation.optLong("downloadBytes", 0L).coerceAtLeast(0L)
        enqueueJob(context, game, target, bytes, MaintenanceAction.valueOf(operation.getString("operation")))
    }

    override fun recovery(context: Context, game: InstalledStoreGame): MaintenanceRecovery {
        val phase = runCatching { StoreMaintenance.readOperation(game.folder)?.optString("phase").orEmpty() }.getOrDefault("")
        return if (phase in setOf("queued", "download", "applying")) MaintenanceRecovery(RecoveryState.RESUMABLE, phase)
        else if (phase.isNotEmpty()) MaintenanceRecovery(RecoveryState.NEEDS_ATTENTION, phase) else MaintenanceRecovery()
    }

    private fun validateTarget(game: InstalledStoreGame, target: JSONObject): JSONObject {
        require(target.optInt("schema") == TARGET_SCHEMA) { "The saved Amazon maintenance target is unsupported" }
        require(target.optString("versionId").isNotBlank()) { "The saved Amazon target has no version ID" }
        val rawPath = File(target.optString("manifestPath"))
        require(!Files.isSymbolicLink(rawPath.toPath())) { "The saved Amazon manifest is a symbolic link" }
        val path = rawPath.canonicalFile
        require(path.path.startsWith(game.folder.canonicalPath + File.separator) && path.isFile) {
            "The saved Amazon manifest is unavailable or outside the install folder"
        }
        val checked = AmazonNative.inspect(path.readBytes()) ?: error("The saved Amazon manifest could not be verified")
        require(!checked.has("error")) { checked.optString("error", "The saved Amazon manifest could not be verified") }
        require(checked.optInt("unverifiableFiles", 0) == 0) { "Amazon's saved manifest has file hashes DroidDeck cannot verify; it cannot safely replace those files" }
        return JSONObject(target.toString()).put("manifestPath", path.absolutePath)
    }

    private fun enqueueJob(context: Context, game: InstalledStoreGame, target: JSONObject, bytes: Long, action: MaintenanceAction) {
        val app = context.applicationContext
        val sidecar = game.sidecar
        val item = CatalogItem(Store.AMAZON, sidecar.id, sidecar.title, sidecar.hero, tallImageUrl = sidecar.cover,
            extra = mapOf("entitlementId" to sidecar.extra["entitlementId"].orEmpty(), "sku" to sidecar.extra["sku"].orEmpty(),
                StoreMaintenance.OPERATION_KEY to action.name, StoreMaintenance.FOLDER_KEY to game.folder.absolutePath))
        SavedQueue.remember(app, item, null)
        DownloadQueue.enqueue(app, DownloadEntry(Store.AMAZON, item.id, item.title, cover = item.tallImageUrl,
            bytesTotal = bytes, location = StoreInstallRoot.labelFor(app, game.folder))) {
            AmazonInstaller(app, item, game.folder, target, game)
        }
    }
}
