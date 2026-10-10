package com.droiddeck.launcher.stores

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Installed tab draws what its count counts: the installs on disk. */
class InstalledCardsTest {
    private fun install(store: Store, id: String, title: String) =
        InstalledStoreGame(StoreGameSidecar(store, id, title, "game.exe", cover = "https://img/$id.jpg"), File("/x/$id"))

    @Test fun everyInstallIsACardWhetherOrNotTheLibraryHasLoaded() {
        val installed = listOf(install(Store.GOG, "1", "DOOM + DOOM II"), install(Store.GOG, "2", "DOOM I Enhanced"), install(Store.EPIC, "e", "DOOMBLADE"))
        // Library not loaded: cards from the sidecars.
        val bare = installedCards(Store.GOG, installed, emptyList())
        assertEquals(listOf("DOOM + DOOM II", "DOOM I Enhanced"), bare.map { it.title })
        assertEquals("https://img/1.jpg", bare.first().tallImageUrl)
        // Loaded: the library item wins, by id, or by title when the ids differ.
        val library = listOf(
            CatalogItem(Store.GOG, "1", "DOOM + DOOM II", imageUrl = "wide1", owned = true),
            CatalogItem(Store.GOG, "999", "DOOM I Enhanced", imageUrl = "wide2", owned = true),
            CatalogItem(Store.GOG, "3", "Not installed", imageUrl = null, owned = true),
        )
        val joined = installedCards(Store.GOG, installed, library)
        assertEquals(listOf("wide1", "wide2"), joined.map { it.imageUrl })
        assertEquals(1, installedCards(Store.EPIC, installed, library).size)
    }
}
