package com.droiddeck.launcher.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.session.GameEnvironmentStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GameEnvironmentEditorTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun backgroundSelectionCannotRetargetAnOpenVariableEdit() {
        val context: Context = RuntimeEnvironment.getApplication()
        val variable = "MESA_SHADER_CACHE_MAX_SIZE"
        GameEnvironmentStore.save(context, GameEnvironment.Config(games = mapOf(
            "42" to mapOf(variable to "512M"),
            "43" to mapOf(variable to "2G"),
        )))
        val selectedScope = mutableStateOf("42")
        compose.setContent {
            DroidDeckTheme { GameEnvironmentRow(initialScope = selectedScope.value) }
        }
        compose.onNodeWithText(context.getString(R.string.game_env_edit)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("512M")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("512M").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.game_env_change)).performClick()
        compose.onAllNodesWithText("512M").onLast().performClick()
        compose.onNodeWithText("4G").performClick()

        compose.runOnIdle { selectedScope.value = "43" }
        compose.onNodeWithText(context.getString(R.string.game_env_save)).performClick()
        compose.waitUntil(5_000) {
            GameEnvironmentStore.read(context).entries("42")[variable] == "4G"
        }
        assertEquals("2G", GameEnvironmentStore.read(context).entries("43")[variable])
        compose.onNodeWithText("4G").assertExists()

        compose.onNodeWithText(context.getString(R.string.game_env_done)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.game_env_edit)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("2G")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("2G").assertExists()
    }
}
