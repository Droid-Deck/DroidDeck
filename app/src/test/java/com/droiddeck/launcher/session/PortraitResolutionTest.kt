package com.droiddeck.launcher.session

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PortraitResolutionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val panel = 2400 to 1080

    @Before fun clear() {
        app.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun portraitFollowsLandscapeUntilItIsSet() {
        assertEquals(SessionDisplay.LINK_LANDSCAPE, SessionPrefs.resolutionStored(app, "steam", panel, true))
        SessionPrefs.setResolutionChoice(app, "steam", "900p")
        assertEquals("900p", SessionPrefs.resolutionChoice(app, "steam", panel, false))
        assertEquals("900p", SessionPrefs.resolutionChoice(app, "steam", panel, true))
    }

    @Test fun eachOrientationKeepsItsOwnResolution() {
        SessionPrefs.setResolutionChoice(app, "steam", "1600x720")
        SessionPrefs.setResolutionChoice(app, "steam", "1080p", portrait = true)
        assertEquals("1600x720", SessionPrefs.resolutionChoice(app, "steam", panel, false))
        assertEquals("1080p", SessionPrefs.resolutionChoice(app, "steam", panel, true))
        assertEquals("1080p", SessionPrefs.resolutionStored(app, "steam", panel, true))
    }

    @Test fun landscapeCanFollowPortrait() {
        SessionPrefs.setResolutionChoice(app, "steam", "720x1280", portrait = true)
        SessionPrefs.setResolutionChoice(app, "steam", SessionDisplay.LINK_PORTRAIT)
        assertEquals(SessionDisplay.LINK_PORTRAIT, SessionPrefs.resolutionStored(app, "steam", panel, false))
        assertEquals("1280x720", SessionPrefs.resolutionChoice(app, "steam", panel, false))
        assertEquals("720x1280", SessionPrefs.resolutionChoice(app, "steam", panel, true))
    }

    @Test fun followingEachOtherNeverLoops() {
        SessionPrefs.setResolutionChoice(app, "steam", "900p")
        SessionPrefs.setResolutionChoice(app, "steam", SessionDisplay.LINK_PORTRAIT)
        assertEquals("900p", SessionPrefs.resolutionChoice(app, "steam", panel, false))
        assertEquals("900p", SessionPrefs.resolutionChoice(app, "steam", panel, true))
        assertFalse(SessionDisplay.isLink(SessionPrefs.resolutionStored(app, "steam", panel, true)))
    }

    @Test fun modesKeepSeparateChoices() {
        SessionPrefs.setResolutionChoice(app, "steam", "1080p", portrait = true)
        assertEquals(SessionDisplay.LINK_LANDSCAPE, SessionPrefs.resolutionStored(app, "lxqt", panel, true))
    }

    @Test fun resettingSettingsClearsThePortraitChoice() {
        SessionPrefs.setResolutionChoice(app, "steam", "1080p", portrait = true)
        assertTrue(SettingsReset.isSessionSetting("displayResolution.portrait.steam"))
    }

    @Test fun effectiveChoiceFollowsTheOtherOrientation() {
        val d = SessionDisplay
        assertEquals("900p", d.effectiveChoice("900p", d.LINK_LANDSCAPE, true))
        assertEquals("1600x720", d.effectiveChoice("1600x720", "1080p", false))
        assertEquals("720x1600", d.effectiveChoice("1600x720", d.LINK_LANDSCAPE, true))
        assertEquals("1600x720", d.effectiveChoice(d.LINK_PORTRAIT, "720x1600", false))
        assertEquals(d.DEFAULT_RESOLUTION, d.effectiveChoice(d.LINK_PORTRAIT, d.LINK_LANDSCAPE, true))
        assertEquals("follow/2", d.effectiveChoice("follow/2", d.LINK_LANDSCAPE, true))
        assertEquals(d.MATCH_SCREEN, d.effectiveChoice(d.LINK_PORTRAIT, d.MATCH_SCREEN, false))
    }
}
