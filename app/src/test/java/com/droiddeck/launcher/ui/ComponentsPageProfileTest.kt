package com.droiddeck.launcher.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ComponentsPageProfileTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val gameA = Library.SteamGame(42, "Game A", null, "internal")
    private val gameB = Library.SteamGame(43, "Game B", null, "internal")

    @Test fun gameProfilesCoexistWithTheDefaultComponentStack() {
        val context = RuntimeEnvironment.getApplication()
        show()
        val stackHeading = context.getString(R.string.comp_this_proton).uppercase()
        compose.onNodeWithText(stackHeading).assertExists()
        compose.onNodeWithText(context.getString(R.string.comp_scope_general)).performClick()
        compose.onNodeWithText("Game A").performClick()
        compose.onNodeWithText(context.getString(R.string.comp_game_hint)).assertExists()
        compose.onNodeWithText(stackHeading).assertDoesNotExist()

        compose.onNodeWithText("Game A").performClick()
        compose.onNodeWithText(context.getString(R.string.comp_scope_general)).performClick()
        compose.onNodeWithText(stackHeading).assertExists()
    }

    @Test fun opensTheRequestedGameProfileAndAllowsSwitchingToDefault() {
        val context = RuntimeEnvironment.getApplication()
        show { gameB.profileKey }
        compose.onNodeWithText("Game B").assertExists()
        compose.onNodeWithText(context.getString(R.string.comp_game_hint)).assertExists()
        compose.onNodeWithText(context.getString(R.string.comp_this_proton).uppercase()).assertDoesNotExist()

        compose.onNodeWithText("Game B").performClick()
        compose.onNodeWithText(context.getString(R.string.comp_scope_general)).performClick()
        compose.onNodeWithText(context.getString(R.string.comp_this_proton).uppercase()).assertExists()
    }

    @Test fun aNewNavigationTargetReplacesThePreviousProfile() {
        val context = RuntimeEnvironment.getApplication()
        val target = mutableStateOf<String?>(gameA.profileKey)
        show { target.value }
        compose.onNodeWithText("Game A").assertExists()

        compose.runOnIdle { target.value = gameB.profileKey }
        compose.onNodeWithText("Game B").assertExists()
        compose.onNodeWithText("Game A").assertDoesNotExist()

        compose.runOnIdle { target.value = null }
        compose.onNodeWithText(context.getString(R.string.comp_scope_general)).assertExists()
        compose.onNodeWithText(context.getString(R.string.comp_this_proton).uppercase()).assertExists()
    }

    @Test fun anUnavailableGameFallsBackToTheDefaultProfile() {
        val context = RuntimeEnvironment.getApplication()
        show { "missing-game" }
        compose.onNodeWithText(context.getString(R.string.comp_scope_general)).assertExists()
        compose.onNodeWithText(context.getString(R.string.comp_this_proton).uppercase()).assertExists()
    }

    private fun show(initialGameKey: () -> String? = { null }) {
        compose.setContent {
            DroidDeckTheme {
                ComponentsPage(
                    snapshot = null,
                    catalog = emptyList(),
                    catalogAt = 0,
                    protonId = null,
                    comp = PROTON_TAB,
                    checking = false,
                    busy = null,
                    downloads = emptyMap(),
                    onProton = {},
                    onComp = {},
                    onSwap = {},
                    onRestore = {},
                    onCancelQueued = {},
                    onDeletePackage = {},
                    onDeleteOriginal = {},
                    onDownload = {},
                    onRefresh = {},
                    onImport = {},
                    onBack = {},
                    games = listOf(gameA, gameB),
                    initialGameKey = initialGameKey(),
                    requestInitialFocus = false,
                )
            }
        }
    }
}
