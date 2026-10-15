package com.droiddeck.launcher.stores.epic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar

class EpicClientLaunchTest {
    @Test
    fun libraryLaunchTargetsTheEpicClientWithGameIdentity() {
        assertEquals(
            listOf("run", "--app", "ExampleApp", "--namespace", "example-namespace", "--item", "example-item"),
            EpicClient.runArguments("ExampleApp", "example-namespace", "example-item"),
        )
    }

    @Test
    fun importedClientGameUsesItsAppIdWhenTheSidecarHasNoAppName() {
        val sidecar = StoreGameSidecar(
            store = Store.EPIC,
            id = "ImportedApp",
            title = "Imported Game",
            exe = "Game.exe",
            extra = mapOf("namespace" to "ns", "catalogItemId" to "item", "clientManaged" to "true"),
        )
        assertEquals("ImportedApp", EpicClient.appName(sidecar))
    }

    @Test
    fun emptyEpicGameIdentityIsRejected() {
        val sidecar = StoreGameSidecar(store = Store.EPIC, id = "", title = "Broken", exe = "Game.exe")
        assertNull(EpicClient.appName(sidecar))
    }
}
