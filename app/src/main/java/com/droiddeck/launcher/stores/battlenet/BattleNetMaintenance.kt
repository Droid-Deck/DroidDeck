package com.droiddeck.launcher.stores.battlenet

import android.content.Context
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.Store
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

internal object BattleNetMaintenanceAdapter : StoreMaintenanceAdapter {
    override val store = Store.BATTLENET
    private const val TARGET_SCHEMA = 1

    override fun capabilities(game: InstalledStoreGame): Set<MaintenanceAction> = buildSet {
        if (BattleNetInstall.product(game.sidecar.id) != null) add(MaintenanceAction.UPDATE)
        add(MaintenanceAction.OPEN_CLIENT)
    }

    override fun check(context: Context, game: InstalledStoreGame): MaintenanceCheck =
        BattleNetInstall.maintenanceCheck(context, game)

    override fun prepare(context: Context, game: InstalledStoreGame, action: MaintenanceAction): MaintenanceProposal {
        require(action == MaintenanceAction.UPDATE) { "Battle.net maintenance is delegated to Agent and its client" }
        val check = BattleNetInstall.maintenanceCheck(context, game)
        require(check.state != MaintenanceCheckState.CURRENT) { "Battle.net reports this installed build is current" }
        val local = BattleNetInstall.buildKey(File(game.folder, ".build.info").readText())
            ?: error("The installed Battle.net build identity is unknown")
        val target = JSONObject().put("schema", TARGET_SCHEMA).put("installUid", BattleNetInstall.installUid(context, game))
            .put("region", local.first)
            .put("installedBuild", local.second)
            .put("targetBuild", check.version)
        require(target.optString("installUid").isNotBlank() && target.optString("region").isNotBlank()) {
            "Battle.net install identity is incomplete"
        }
        return MaintenanceProposal(game.folder.canonicalFile, game.sidecar, check.version.ifBlank { "latest Battle.net build" }, action,
            description = if (check.state == MaintenanceCheckState.AVAILABLE) "Battle.net Agent applies the selected regional update in its existing client environment."
            else "The target build could not be checked; Battle.net Agent selects the current regional build.", target = target)
    }

    override fun enqueue(context: Context, game: InstalledStoreGame, proposal: MaintenanceProposal) {
        require(proposal.operation == MaintenanceAction.UPDATE)
        enqueuePinned(context, game, proposal.target)
    }

    override fun resume(context: Context, game: InstalledStoreGame, operation: JSONObject) {
        val target = operation.optJSONObject("target") ?: error("Battle.net maintenance target is missing")
        require(operation.optString("phase") in setOf("queued", "download", "applying"))
        enqueuePinned(context, game, target)
    }

    override fun recovery(context: Context, game: InstalledStoreGame): MaintenanceRecovery {
        val operation = StoreMaintenance.readOperation(game.folder) ?: return MaintenanceRecovery()
        return when (operation.optString("phase")) {
            "queued", "download", "applying" -> MaintenanceRecovery(RecoveryState.RESUMABLE, "Resume with Battle.net Agent")
            else -> MaintenanceRecovery(RecoveryState.NEEDS_ATTENTION, "Inspect the Battle.net client before resuming")
        }
    }

    override fun openClient(context: Context, game: InstalledStoreGame) = BattleNetClient.open(emptyList())

    override fun openClientLabel(game: InstalledStoreGame) = "Open Battle.net for Scan and Repair"

    private fun enqueuePinned(context: Context, game: InstalledStoreGame, target: JSONObject) {
        require(target.optInt("schema") == TARGET_SCHEMA && target.optString("installUid").isNotBlank()) {
            "The saved Battle.net target is unsupported"
        }
        require(target.optString("region").matches(Regex("[a-z]{2}")) && target.optString("installedBuild").matches(Regex("[0-9a-f]{32}"))) {
            "The saved Battle.net build identity is invalid"
        }
        val item = CatalogItem(Store.BATTLENET, game.sidecar.id, game.sidecar.title, game.sidecar.hero,
            tallImageUrl = game.sidecar.cover, owned = true,
            extra = game.sidecar.extra + mapOf(BattleNetInstall.INSTALL_UID to target.getString("installUid"),
                StoreMaintenance.OPERATION_KEY to MaintenanceAction.UPDATE.name, StoreMaintenance.FOLDER_KEY to game.folder.canonicalPath))
        val root = game.folder.parentFile?.parentFile ?: error("Battle.net install root is missing")
        SavedQueue.remember(context.applicationContext, item, root)
        BattleNetInstall.enqueueMaintenance(context, item, game)
    }
}
