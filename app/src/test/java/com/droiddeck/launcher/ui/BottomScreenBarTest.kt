package com.droiddeck.launcher.ui

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w640dp-h480dp-land", application = android.app.Application::class)
class BottomScreenBarTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val context = RuntimeEnvironment.getApplication()

    @After fun reset() {
        SessionState.mode = SessionService.MODE_DESKTOP
    }

    @Test fun steamSessionsGetSteamAndQuickAccess() {
        SessionState.mode = SessionService.MODE_STEAM
        var steam = 0
        var qam = 0
        val model = sessionBarModel("Trackpad", {}, {}, { steam++ }, { qam++ })
        assertNotNull(model.onSteam)
        assertNotNull(model.onQam)
        model.onSteam?.invoke()
        model.onQam?.invoke()
        assertEquals(1, steam)
        assertEquals(1, qam)
    }

    @Test fun otherSessionsLeaveSteamOut() {
        SessionState.mode = SessionService.MODE_DESKTOP
        val model = sessionBarModel("Trackpad", {}, {}, {}, {})
        assertNull(model.onSteam)
        assertNull(model.onQam)
    }

    @Test fun everyButtonOnTheBarReachesItsAction() {
        var closes = 0
        var menus = 0
        var steam = 0
        var qam = 0
        var keyboard = 0
        compose.setContent {
            DroidDeckTheme {
                BottomScreenBar(BottomBarModel("Trackpad", { closes++ }, { menus++ }, { steam++ }, { qam++ },
                    listOf(BarExtra(context.getString(R.string.second_show_keyboard), icon = R.drawable.ic_bottom_keyboard) { keyboard++ })))
            }
        }
        for (label in listOf(R.string.common_close, R.string.touch_droiddeck_menu, R.string.drawer_steam_menu, R.string.drawer_open_qam,
            R.string.second_show_keyboard)) {
            compose.onNodeWithContentDescription(context.getString(label)).performClick()
        }
        compose.waitForIdle()
        assertEquals(listOf(1, 1, 1, 1, 1), listOf(closes, menus, steam, qam, keyboard))
    }

    @Test fun aBarWithoutMenuOrSteamShowsOnlyClose() {
        compose.setContent {
            DroidDeckTheme { BottomScreenBar(BottomBarModel("Terminal", {}, null, null, null)) }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.common_close)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.touch_droiddeck_menu)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.drawer_steam_menu)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.drawer_open_qam)).assertDoesNotExist()
    }
}
