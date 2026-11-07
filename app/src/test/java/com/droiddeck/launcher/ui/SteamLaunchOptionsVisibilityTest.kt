package com.droiddeck.launcher.ui

import com.droiddeck.launcher.frontend.Library
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamLaunchOptionsVisibilityTest {
    private fun game(library: String = "main", source: String = Library.SOURCE_STEAM) =
        Library.SteamGame(42, "Test game", null, library, source = source)

    @Test fun nativeSteamGamesExposeLaunchOptionsOutsideShortcutPicking() {
        assertTrue(canEditSteamLaunchOptions(game(), shortcutPicker = false))
    }

    @Test fun pickerAddedAndStoreGamesDoNotExposeSteamLaunchOptions() {
        assertFalse(canEditSteamLaunchOptions(game(), shortcutPicker = true))
        assertFalse(canEditSteamLaunchOptions(game(library = Library.ADDED), shortcutPicker = false))
        assertFalse(canEditSteamLaunchOptions(game(source = "gog"), shortcutPicker = false))
    }
}
