package com.droiddeck.launcher.stores.battlenet

import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.maintenance.MaintenanceAction
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class BattleNetMaintenanceTest {
    @Test fun agentManagedGamesOfferOnlyClientHandoffThroughSharedMaintenance() {
        val folder = File("/tmp/battlenet-agent-game")
        val game = InstalledStoreGame(StoreGameSidecar(Store.BATTLENET, "wow", "World of Warcraft", ""), folder)

        assertEquals(setOf(MaintenanceAction.OPEN_CLIENT), BattleNetMaintenanceAdapter.capabilities(game))
    }
}
