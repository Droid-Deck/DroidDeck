package com.droiddeck.launcher.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SettingsReset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class SteamIntegrationSuggestionTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test fun closingTheSuggestionKeepsItHiddenForGood() {
        assertTrue(SteamIntegration.read(context).suggested)
        SteamIntegration.dismissSuggestion(context)
        assertFalse(SteamIntegration.read(context).suggested)
        SettingsReset.resetAll(context, gameProfiles = false)
        assertFalse(SteamIntegration.read(context).suggested)
        assertTrue(SessionPrefs.sessionPrefs(context).getBoolean("steam_integration_seen", false))
    }
}
