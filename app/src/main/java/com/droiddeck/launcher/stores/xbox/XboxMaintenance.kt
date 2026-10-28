package com.droiddeck.launcher.stores.xbox

import android.content.Context
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.maintenance.MaintenanceAction
import com.droiddeck.launcher.stores.maintenance.MaintenanceCheck
import com.droiddeck.launcher.stores.maintenance.MaintenanceProposal
import com.droiddeck.launcher.stores.maintenance.MaintenanceRecovery
import com.droiddeck.launcher.stores.maintenance.RecoveryState
import com.droiddeck.launcher.stores.maintenance.StoreMaintenance
import com.droiddeck.launcher.stores.maintenance.StoreMaintenanceAdapter
import org.json.JSONObject
import java.io.File

internal object XboxMaintenanceAdapter : StoreMaintenanceAdapter {
    override val store = Store.XBOX

    override fun capabilities(game: InstalledStoreGame): Set<MaintenanceAction> =
        if (game.sidecar.extra["contentId"].orEmpty().matches(Regex("[a-fA-F0-9-]{36}"))) setOf(MaintenanceAction.UPDATE) else emptySet()

    override fun check(context: Context, game: InstalledStoreGame): MaintenanceCheck = XboxBackend.maintenanceCheck(context, game)

    override fun prepare(context: Context, game: InstalledStoreGame, action: MaintenanceAction): MaintenanceProposal {
        require(action == MaintenanceAction.UPDATE) { "Xbox Repair is not exposed by Xodus" }
        return XboxBackend.prepareMaintenance(context, game)
    }

    override fun enqueue(context: Context, game: InstalledStoreGame, proposal: MaintenanceProposal) {
        require(proposal.operation == MaintenanceAction.UPDATE && proposal.target.optInt("schema") == 1)
        XboxBackend.enqueueMaintenance(context, game, proposal)
    }

    override fun resume(context: Context, game: InstalledStoreGame, operation: JSONObject) {
        require(operation.optString("phase") in setOf("queued", "download", "applying"))
        val target = operation.optJSONObject("target") ?: error("Xbox maintenance target is missing")
        require(target.optInt("schema") == 1 && target.optString("contentId").matches(Regex("[a-fA-F0-9-]{36}")))
        val proposal = MaintenanceProposal(game.folder.canonicalFile,
            com.droiddeck.launcher.stores.StoreGameSidecar.parse(operation.getJSONObject("before").toString())
                ?: error("The original Xbox settings are missing"), operation.optString("targetLabel"), MaintenanceAction.UPDATE,
            operation.optLong("downloadBytes", MaintenanceProposal.UNKNOWN_BYTES),
            operation.optLong("diskBytes", MaintenanceProposal.UNKNOWN_BYTES), operation.optString("description"), JSONObject(target.toString()))
        XboxBackend.enqueueMaintenance(context, game, proposal)
    }

    override fun recovery(context: Context, game: InstalledStoreGame): MaintenanceRecovery {
        val journal = StoreMaintenance.readOperation(game.folder) ?: return MaintenanceRecovery()
        return when (journal.optString("phase")) {
            "queued", "download", "applying" -> MaintenanceRecovery(RecoveryState.RESUMABLE, "Resume the Xodus package update")
            else -> MaintenanceRecovery(RecoveryState.NEEDS_ATTENTION, "The Xbox package update needs recovery")
        }
    }
}
