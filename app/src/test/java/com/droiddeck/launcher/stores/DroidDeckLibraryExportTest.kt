package com.droiddeck.launcher.stores

import android.app.Application
import com.droiddeck.launcher.frontend.Library
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DroidDeckLibraryExportTest {
    @After fun clearCatalog() {
        StoresState.library.clear()
    }

    @Test fun exportUsesTheValidatedSteamSnapshotAndKeepsOtherStoreCatalogs() {
        StoresState.library[Store.STEAM] = listOf(CatalogItem(Store.STEAM, "999", "Stale Steam row", null, owned = true))
        StoresState.library[Store.GOG] = listOf(CatalogItem(Store.GOG, "gog-1", "GOG game", null, owned = true))
        val steam = listOf(
            Library.SteamGame(620, "Portal 2", null, "Steam", installed = true),
            Library.SteamGame(550, "Left 4 Dead 2", null, Library.NOT_INSTALLED, installed = false),
        )

        val exported = JSONObject(DroidDeckLibraryExport.json(steam, setOf(620, 550)))
            .getJSONArray("games")
        val games = (0 until exported.length()).map { exported.getJSONObject(it) }

        assertEquals(setOf("steam:620", "steam:550", "gog:gog-1"), games.map { "${it.getString("store")}:${it.getString("storeId")}" }.toSet())
        assertTrue(games.single { it.getString("storeId") == "620" }.getBoolean("owned"))
        assertFalse(games.any { it.getString("title") == "Stale Steam row" })
        assertTrue(games.any { it.getString("title") == "GOG game" })
    }
}
