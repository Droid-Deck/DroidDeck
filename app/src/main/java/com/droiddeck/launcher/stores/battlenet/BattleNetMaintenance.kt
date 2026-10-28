package com.droiddeck.launcher.stores.battlenet

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

internal object BattleNetMaintenanceAdapter : StoreMaintenanceAdapter {
    override val store = Store.BATTLENET

    override fun capabilities(game: InstalledStoreGame): Set<MaintenanceAction> = setOf(MaintenanceAction.OPEN_CLIENT)

    override fun check(context: Context, game: InstalledStoreGame): MaintenanceCheck =
        BattleNetInstall.maintenanceCheck(context, game)

    override fun prepare(context: Context, game: InstalledStoreGame, action: MaintenanceAction): MaintenanceProposal =
        error("Battle.net updates must use its Agent/client workflow")

    override fun enqueue(context: Context, game: InstalledStoreGame, proposal: MaintenanceProposal): Boolean =
        error("Battle.net updates must use its Agent/client workflow")

    override fun resume(context: Context, game: InstalledStoreGame, operation: JSONObject): Unit =
        error("Resume this Battle.net operation through its Agent/client workflow")

    override fun recovery(context: Context, game: InstalledStoreGame): MaintenanceRecovery {
        if (StoreMaintenance.readOperation(game.folder) == null) return MaintenanceRecovery()
        return MaintenanceRecovery(RecoveryState.NEEDS_ATTENTION, "Inspect this update in Battle.net before launching the game")
    }

    override fun openClient(context: Context, game: InstalledStoreGame) = BattleNetClient.open(emptyList())

    override fun openClientLabel(game: InstalledStoreGame) = "Open Battle.net for Scan and Repair"
}
