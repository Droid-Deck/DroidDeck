package com.droiddeck.launcher.ui

import android.content.Intent
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-port", application = android.app.Application::class)
class GameResourcesRowTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val game = Library.SteamGame(292030, "The Witcher 3", null, "/steam")

    @Test fun everyResourceIsReachableOnANarrowPageAndOpensTheExpectedBrowserUrl() {
        compose.setContent { DroidDeckTheme { GameResourcesRow(game.name, game) } }
        for (link in gameResources(game.name, game)) {
            compose.onNodeWithText(ctx.getString(link.label)).assertIsDisplayed().performClick()
            val intent = shadowOf(ctx).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(link.url, intent.dataString)
        }
    }

    @Test fun controllerCanMoveBetweenLinksAndOpenAResource() {
        lateinit var inputMode: InputModeManager
        compose.setContent { DroidDeckTheme {
            inputMode = LocalInputModeManager.current
            GameResourcesRow(game.name, game)
        } }
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        compose.onNodeWithText(ctx.getString(R.string.game_resource_pcgw)).requestFocus().assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText(ctx.getString(R.string.game_resource_guides)).assertIsFocused().performKeyInput { pressKey(Key.ButtonA) }
        assertEquals(gameResources(game.name, game)[1].url, shadowOf(ctx).nextStartedActivity.dataString)
    }

    @Test @Config(qualifiers = "w640dp-h360dp-land")
    fun shortLandscapeWindowsKeepEveryResourceVisible() {
        compose.setContent { DroidDeckTheme { GameResourcesRow(game.name, game) } }
        for (link in gameResources(game.name, game)) {
            compose.onNodeWithText(ctx.getString(link.label)).assertIsDisplayed()
        }
    }
}
