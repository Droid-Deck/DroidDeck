package com.droiddeck.launcher.session

import android.content.Context
import android.content.pm.ActivityInfo
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

    @Test fun aGeneratedPortraitSizeSurvivesAReload() {
        SessionPrefs.setResolutionChoice(app, "steam", "2560x1440")
        SessionPrefs.setResolutionChoice(app, "steam", SessionDisplay.LINK_PORTRAIT)
        assertEquals("1440x2560", SessionPrefs.resolutionStored(app, "steam", panel, true))
        assertEquals("2560x1440", SessionPrefs.resolutionChoice(app, "steam", panel, false))
        assertEquals("1440x2560", SessionPrefs.resolutionChoice(app, "steam", panel, true))
    }

    @Test fun aSavedDesktopResolutionInEitherOrientationCountsAsChosen() {
        SessionPrefs.setResolutionChoice(app, SessionService.MODE_DESKTOP, "1080p", portrait = true)
        assertTrue(SessionPrefs.resolutionChosen(app, SessionService.MODE_DESKTOP, true))
        assertEquals(SessionService.MODE_DESKTOP,
            SessionPrefs.displayMode(app, SessionService.MODE_RUN, com.droiddeck.launcher.runtime.UserApps.SCRIPT_LAUNCHER))
    }

    @Test fun portraitInputAcceptsTallSizesLandscapeInputDoesNot() {
        assertNull(SessionPrefs.parseResolution("1440x2560"))
        assertEquals(1440 to 2560, SessionPrefs.parseOrientedResolution("1440x2560"))
        assertEquals(1280 to 720, SessionPrefs.parseOrientedResolution("1280x720"))
        assertNull(SessionPrefs.parseOrientedResolution("100x100"))
        SessionPrefs.setResolutionChoice(app, "steam", "1440x2560", portrait = true)
        assertEquals("1440x2560", SessionPrefs.resolutionStored(app, "steam", panel, true))
    }

    @Test fun followScreenInLandscapeLeavesAFixedPortraitSizeFixed() {
        SessionPrefs.setResolutionChoice(app, "steam", SessionDisplay.FOLLOW_SCREEN)
        SessionPrefs.setResolutionChoice(app, "steam", "1024x768", portrait = true)
        assertEquals(1f to null, SessionPrefs.followScales(app, "steam", panel))
    }

    @Test fun followScreenInPortraitLeavesAFixedLandscapeSizeFixed() {
        SessionPrefs.setResolutionChoice(app, "steam", "1080p")
        SessionPrefs.setResolutionChoice(app, "steam", SessionDisplay.followScaled(2f), portrait = true)
        assertEquals(null to 2f, SessionPrefs.followScales(app, "steam", panel))
    }

    @Test fun followScalesKeepLinkedAndFixedChoices() {
        SessionPrefs.setResolutionChoice(app, "steam", SessionDisplay.followScaled(1.5f))
        assertEquals(1.5f to 1.5f, SessionPrefs.followScales(app, "steam", panel))
        SessionPrefs.setResolutionChoice(app, "steam", "1024x768", portrait = true)
        SessionPrefs.setResolutionChoice(app, "steam", SessionDisplay.LINK_PORTRAIT)
        assertEquals(null to null, SessionPrefs.followScales(app, "steam", panel))
        app.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(null to null, SessionPrefs.followScales(app, "steam", panel))
    }

    @Test fun anAutoOrientationIsNotChangedByFollowScreen() {
        assertFalse(SessionPrefs.portrait(app))
        SessionPrefs.setOrientation(app, ActivityInfo.SCREEN_ORIENTATION_USER)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, SessionPrefs.windowOrientation(app, true, 700))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, SessionPrefs.windowOrientation(app, false, 700))
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
