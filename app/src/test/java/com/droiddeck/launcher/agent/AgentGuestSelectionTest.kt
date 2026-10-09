package com.droiddeck.launcher.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentGuestSelectionTest {
    private val installedAppIds = setOf(646570L, 3180310L)

    @Test fun focusedInstalledGameIsSelected() {
        assertEquals(3180310L, AgentGuest.focusedInstalledSteamAppId(3180310L, installedAppIds))
    }

    @Test fun focusedInstalledGameIsActive() {
        assertEquals(
            3180310L,
            AgentGuest.activeInstalledSteamAppId(3180310L, listOf(769L, 3180310L), installedAppIds),
        )
    }

    @Test fun selectedSteamGameWinsOverStaleActiveSteamGame() {
        assertEquals(
            646570L,
            AgentGuest.preferredInstalledSteamAppId(646570L, 3180310L, installedAppIds),
        )
    }

    @Test fun soleFocusableInstalledGameRemainsActiveUnderSteamOverlay() {
        assertEquals(
            3180310L,
            AgentGuest.activeInstalledSteamAppId(769L, listOf(3180310L), installedAppIds),
        )
    }

    @Test fun ambiguousFocusableGamesAreIgnored() {
        assertNull(
            AgentGuest.activeInstalledSteamAppId(769L, listOf(646570L, 3180310L), installedAppIds),
        )
    }

    @Test fun soleBaselayerGameResolvesAmbiguousSteamOverlayFocus() {
        assertEquals(
            646570L,
            AgentGuest.activeInstalledSteamAppId(
                focusedAppId = 769L,
                focusableAppIds = listOf(646570L, 3180310L),
                installedAppIds = installedAppIds,
                baselayerAppIds = listOf(769L, 646570L),
            ),
        )
    }

    @Test fun steamClientFocusIsIgnored() {
        assertNull(AgentGuest.focusedInstalledSteamAppId(769L, installedAppIds))
    }

    @Test fun unknownGameFocusIsIgnored() {
        assertNull(AgentGuest.focusedInstalledSteamAppId(42L, installedAppIds))
    }

    @Test fun missingFocusSnapshotIsIgnored() {
        assertNull(AgentGuest.focusedInstalledSteamAppId(null, installedAppIds))
    }
}
