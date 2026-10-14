package com.droiddeck.launcher.ui

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.droiddeck.launcher.R
import com.droiddeck.launcher.store.FlathubApi
import com.droiddeck.launcher.store.StoreState
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land", application = android.app.Application::class)
class FlathubFocusTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @After fun clearStore() {
        StoreState.sections.clear()
        StoreState.details.clear()
        StoreState.openRequest = null
    }

    @Test fun openingAnAppReleasesTheCoveredNativeSearchField() {
        val id = "io.github.test.Datcord"
        StoreState.sections["popular"] = listOf(
            FlathubApi.AppSummary(id, "Datcord", "Discord client", "", null, false, 0),
        )
        StoreState.details[id] = FlathubApi.AppDetails(
            id, "Datcord", "Discord client", "", null, "", null, null, null,
            emptyList(), emptyList(), null, 0, 0, listOf("aarch64"),
        )
        lateinit var root: View
        val focus = FrontFocus()
        compose.setContent {
            DroidDeckTheme {
                val view = LocalView.current
                SideEffect { root = view.rootView }
                CompositionLocalProvider(LocalFrontFocus provides focus) {
                    FocusGlideHost {
                        Storefront(FrontEndState(
                            installed = "r9", ready = true, available = null, busy = false, stage = "", percent = -1,
                            desktopInstalled = true, offlineAccount = null, offline = false, frameGenLabel = "",
                            romsDir = null, logsEnabled = false, steamGames = emptyList(), emulators = emptyList(), running = null,
                        ), FrontEndActions(
                            onPlay = {}, onPlayDesktopUi = {}, onSteamGame = {}, onDesktop = {}, onEmulator = {},
                            onRom = {}, onResume = {}, onSteamSettings = {}, onDesktopSettings = {},
                            onInstallPackage = {}, onRemovePackage = {}, onRuntime = {}, onFrameGenPick = {},
                            onImportLossless = {}, onProtons = {}, onComponents = {}, onPerformance = {},
                            onRoms = {}, onFiles = {}, onLogs = {}, onOffline = {},
                            onWirelessAdbPair = { _, _, _, _ -> }, onWirelessAdbApply = { _, _, _, _ -> },
                            onSetPhantomProcessLimit = { _, _ -> },
                        ))
                    }
                }
            }
        }
        val context = RuntimeEnvironment.getApplication()
        compose.onNodeWithContentDescription(context.getString(R.string.stores_search_open)).performClick()
        compose.waitForIdle()
        val field = editText(root)!!
        compose.runOnIdle {
            field.setText(" ")
            field.requestFocus()
            assertTrue(field.isFocused)
        }
        compose.waitForIdle()
        compose.onNodeWithText("Datcord").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText(context.getString(R.string.store_install)).assertExists()
        compose.runOnIdle {
            assertFalse("Covered search field must release focus", field.isFocused)
            assertEquals(" ", field.text.toString())
            focus.items.getValue("tile:store:$id").requestFocus()
        }
        compose.onNode(hasText("Datcord") and hasClickAction()).assertIsNotFocused()
    }

    private fun editText(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            editText(view.getChildAt(i))?.let { return it }
        }
        return null
    }
}
