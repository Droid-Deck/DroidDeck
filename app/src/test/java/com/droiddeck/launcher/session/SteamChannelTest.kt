package com.droiddeck.launcher.session

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SteamChannelTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun freshSettingsDefaultToDeckBetaWithOrWithoutDeckMode() {
        assertEquals("steamdeck_publicbeta", SessionPrefs.steamChannel(context))
        SessionPrefs.setSteamDeckMode(context, false)
        assertEquals("steamdeck_publicbeta", SessionPrefs.steamChannel(context))
    }

    @Test fun explicitChannelIsPreservedWhenDeckModeIsOff() {
        SessionPrefs.setSteamDeckMode(context, false)
        SessionPrefs.setSteamChannel(context, "publicbeta")
        assertEquals("publicbeta", SessionPrefs.steamChannel(context))
        SessionPrefs.setSteamChannel(context, "steamdeck_publicbeta")
        assertEquals("steamdeck_publicbeta", SessionPrefs.steamChannel(context))
    }

    @Test fun deckModeUsesDeckBetaAndRestoresSavedSelectionWhenDisabled() {
        SessionPrefs.setSteamChannel(context, "publicbeta")
        assertEquals("steamdeck_publicbeta", SessionPrefs.steamChannel(context))
        SessionPrefs.setSteamDeckMode(context, false)
        assertEquals("publicbeta", SessionPrefs.steamChannel(context))
    }
}
