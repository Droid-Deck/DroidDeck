package com.droiddeck.launcher.ui

import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamStoreSignInTest {
    private val steamTab = listOf(StoreTab(Store.STEAM, shown = true))
    private val steamBackend = setOf(Store.STEAM)

    @Test fun theSteamLibraryFilterRemainsAvailableAfterLoginWithoutASecondSignInChip() {
        val signedInPrompts = librarySignInSources(
            steamTab, steamBackend, LibraryPreview.OFF, mapOf(Store.STEAM to StoreAccount.SIGNED_IN),
        )

        assertTrue(signedInPrompts.isEmpty())
        assertEquals(
            listOf(Store.STEAM.id),
            librarySources(emptyList(), listOf(Store.STEAM.id), always = setOf(Store.STEAM.id)),
        )
        assertFalse(shouldShowStoreSignInCard(Store.STEAM.id, signedInPrompts, LibraryPreview.OFF, StoreAccount.SIGNED_IN, true))
    }

    @Test fun theSteamFilterOffersSignInOnlyWhenItsAccountNeedsIt() {
        assertEquals(
            setOf(Store.STEAM.id),
            librarySignInSources(steamTab, steamBackend, LibraryPreview.OFF, mapOf(Store.STEAM to StoreAccount.SIGNED_OUT)),
        )
        assertTrue(shouldShowStoreSignInCard(Store.STEAM.id, setOf(Store.STEAM.id), LibraryPreview.OFF, StoreAccount.SIGNED_OUT, false))
        assertFalse(shouldShowStoreSignInCard(Store.STEAM.id, setOf(Store.STEAM.id), LibraryPreview.OFF, StoreAccount.EXPIRED, true))
        assertTrue(shouldShowStoreSignInCard(Store.STEAM.id, setOf(Store.STEAM.id), LibraryPreview.OFF, StoreAccount.EXPIRED, false))
    }

    @Test fun hiddenSteamTabHasNoSignInChip() {
        val hidden = listOf(StoreTab(Store.STEAM, shown = false))
        assertTrue(librarySignInSources(hidden, steamBackend, LibraryPreview.OFF, emptyMap()).isEmpty())
    }
}
