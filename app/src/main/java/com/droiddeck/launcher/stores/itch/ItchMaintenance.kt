package com.droiddeck.launcher.stores.itch

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
import java.util.concurrent.ConcurrentHashMap

internal object ItchMaintenanceAdapter : StoreMaintenanceAdapter {
    override val store = Store.ITCH
    private val exactTargets = ConcurrentHashMap<String, JSONObject>()
    private val exactCheckedAt = ConcurrentHashMap<String, Long>()

    override fun capabilities(game: InstalledStoreGame): Set<MaintenanceAction> {
        val result = linkedSetOf(MaintenanceAction.REFRESH)
        if (StoreMaintenance.check(game).state == MaintenanceCheckState.AVAILABLE) result.add(MaintenanceAction.UPDATE)
        val exact = exactTargets[game.key]
        if (System.currentTimeMillis() - (exactCheckedAt[game.key] ?: 0L) < 30 * 60_000L &&
            !game.sidecar.installVersion.isBlank() && exact != null && exact.optString("uploadId") == game.sidecar.extra["uploadId"] &&
            exact.optString("updatedAt") == game.sidecar.installVersion) result.add(MaintenanceAction.REPAIR)
        return result
    }

    override fun check(context: Context, game: InstalledStoreGame): MaintenanceCheck {
        val key = ItchAuth.key(context)
        if (key.isBlank()) { forgetExact(game.key); return MaintenanceCheck(MaintenanceCheckState.FAILED, reason = "Sign in to itch.io to check uploads") }
        val gameId = game.sidecar.extra["gameId"].orEmpty()
        if (gameId.isBlank()) { forgetExact(game.key); return MaintenanceCheck(MaintenanceCheckState.FAILED, reason = "The installed itch.io game has no game ID") }
        val uploads = ItchApi.uploads(key, gameId, game.sidecar.extra["downloadKeyId"].orEmpty())
            ?: run { forgetExact(game.key); return MaintenanceCheck(MaintenanceCheckState.FAILED, reason = "itch.io upload check failed") }
        if (game.sidecar.installVersion.isBlank()) {
            forgetExact(game.key)
            return MaintenanceCheck(MaintenanceCheckState.FAILED, reason = "The installed itch.io upload version is unknown; use Refresh")
        }
        val uploadId = game.sidecar.extra["uploadId"].orEmpty()
        val installed = ItchUploads.byId(uploads, uploadId)
        val exact = installed?.takeIf { game.sidecar.installVersion.isNotBlank() && ItchUploads.updatedAt(it) == game.sidecar.installVersion }
        if (exact != null) {
            exactTargets[game.key] = targetJson(game, exact)
            exactCheckedAt[game.key] = System.currentTimeMillis()
        } else forgetExact(game.key)
        val update = ItchUpdates.evaluate(uploads, uploadId, game.sidecar.installVersion)
        return if (update == null) MaintenanceCheck(MaintenanceCheckState.CURRENT, game.sidecar.installVersion)
        else MaintenanceCheck(MaintenanceCheckState.AVAILABLE, update.updatedAt, if (update.replaced) "The installed upload was replaced" else "A newer upload is available")
    }

    override fun prepare(context: Context, game: InstalledStoreGame, action: MaintenanceAction): MaintenanceProposal {
        val token = ItchAuth.key(context)
        require(token.isNotBlank()) { "Sign in to itch.io to maintain this game" }
        val extra = game.sidecar.extra
        val gameId = extra["gameId"].orEmpty()
        val keyId = extra["downloadKeyId"].orEmpty()
        require(gameId.isNotBlank()) { "The installed itch.io game has no game ID" }
        val uploads = ItchApi.uploads(token, gameId, keyId) ?: throw IllegalStateException("itch.io upload check failed")
        val currentUpload = ItchUploads.byId(uploads, extra["uploadId"].orEmpty())
        val chosenUpload = when (action) {
            MaintenanceAction.REPAIR -> currentUpload?.takeIf { game.sidecar.installVersion.isNotBlank() && ItchUploads.updatedAt(it) == game.sidecar.installVersion }
                ?: throw IllegalStateException("The exact installed upload is no longer available; use Refresh for the current upload")
            MaintenanceAction.UPDATE -> {
                val next = ItchUpdates.evaluate(uploads, extra["uploadId"].orEmpty(), game.sidecar.installVersion)
                    ?: throw IllegalStateException("No newer itch.io upload is available")
                ItchUploads.byId(uploads, next.uploadId) ?: throw IllegalStateException("The selected itch.io upload disappeared")
            }
            MaintenanceAction.REFRESH -> currentUpload ?: ItchUploads.newestWindows(uploads)
                ?: throw IllegalStateException("No Windows itch.io upload is available")
            else -> throw IllegalArgumentException("Unsupported itch.io maintenance action")
        }
        val target = targetJson(game, chosenUpload)
        val id = target.optString("uploadId")
        val stamp = target.optString("updatedAt")
        val label = when (action) {
            MaintenanceAction.REPAIR -> "installed upload $id ($stamp)"
            MaintenanceAction.UPDATE -> "upload $id ($stamp)"
            else -> if (id == extra["uploadId"] && stamp == game.sidecar.installVersion) "current upload $id ($stamp)" else "current upload $id ($stamp); the installed upload is no longer available"
        }
        val size = target.optLong("size")
        return MaintenanceProposal(game.folder, game.sidecar, label, action, size, MaintenanceProposal.UNKNOWN_BYTES,
            "Downloads one pinned archive. Extracted size and required free space are unknown until download; DroidDeck checks the measured extracted payload before replacing files.", target)
    }

    override fun enqueue(context: Context, game: InstalledStoreGame, proposal: MaintenanceProposal): Boolean =
        enqueuePinned(context, game, proposal.target)

    override fun resume(context: Context, game: InstalledStoreGame, operation: JSONObject) {
        val target = operation.optJSONObject("target") ?: throw IllegalStateException("itch.io maintenance target is missing")
        check(enqueuePinned(context, game, target)) { "itch.io maintenance is already queued" }
    }

    override fun recovery(context: Context, game: InstalledStoreGame): MaintenanceRecovery {
        ItchStaging.readInventoryIfPresent(game.folder)
        val operation = StoreMaintenance.readOperation(game.folder)
        return if (operation?.optString("phase")?.let { it in setOf("queued", "download", "applying") } == true || ItchStaging.recover(game.folder))
            MaintenanceRecovery(RecoveryState.RESUMABLE, "Resume the interrupted itch.io archive replacement")
        else MaintenanceRecovery()
    }

    private fun enqueuePinned(context: Context, game: InstalledStoreGame, target: JSONObject): Boolean {
        val item = CatalogItem(
            Store.ITCH, game.sidecar.id, game.sidecar.title, game.sidecar.hero,
            tallImageUrl = game.sidecar.cover, isFree = game.sidecar.extra["downloadKeyId"].isNullOrBlank(), owned = true,
            extra = game.sidecar.extra + mapOf("uploadId" to target.optString("uploadId"),
                StoreMaintenance.OPERATION_KEY to (StoreMaintenance.readOperation(game.folder)?.optString("operation") ?: MaintenanceAction.REFRESH.name),
                StoreMaintenance.FOLDER_KEY to game.folder.absolutePath),
        )
        val entry = DownloadEntry(Store.ITCH, game.sidecar.id, game.sidecar.title,
            bytesTotal = target.optLong("size"), location = StoreInstallRoot.labelFor(context, game.folder))
        return DownloadQueue.enqueueIfIdle(context.applicationContext, entry, onAccepted = {
            SavedQueue.remember(context.applicationContext, item, null)
        }) { ItchInstaller(context.applicationContext, item, game.folder, target) }
    }

    private fun targetJson(game: InstalledStoreGame, upload: JSONObject): JSONObject = JSONObject()
        .put("schema", 1).put("gameId", game.sidecar.extra["gameId"].orEmpty())
        .put("downloadKeyId", game.sidecar.extra["downloadKeyId"].orEmpty())
        .put("uploadId", upload.optString("id"))
        .put("updatedAt", ItchUploads.updatedAt(upload))
        .put("filename", ItchUploads.filename(upload))
        .put("size", ItchUploads.size(upload))

    private fun forgetExact(gameKey: String) {
        exactTargets.remove(gameKey)
        exactCheckedAt.remove(gameKey)
    }
}
