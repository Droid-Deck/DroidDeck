package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class PresetScaleTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun presetPopupAndPageKeepTheScaleChosenInSetup() {
        val scale = mutableIntStateOf(100)
        val preset = mutableStateOf("PERFORMANCE")
        val open = mutableStateOf(false)
        compose.setContent {
            DroidDeckTheme(appScale = scale.intValue) {
                Box(Modifier.size(100.dp).testTag("page-size"))
                FexPresetChip(preset.value, false, open.value, true,
                    onToggle = { open.value = true }, onDismiss = { open.value = false },
                    onPick = { preset.value = it!! })
            }
        }
        val originalWidth = compose.onNodeWithTag("page-size").fetchSemanticsNode().boundsInRoot.width
        compose.runOnIdle {
            scale.intValue = 75
            Snapshot.sendApplyNotifications()
        }
        compose.onNodeWithText("Performance").performClick()
        compose.onNodeWithText("Intermediate").performClick()
        compose.waitForIdle()
        assertEquals(originalWidth * 0.75f,
            compose.onNodeWithTag("page-size").fetchSemanticsNode().boundsInRoot.width, 1f)
        compose.runOnIdle {
            open.value = true
            Snapshot.sendApplyNotifications()
        }
        compose.onNodeWithText("Compatibility").performClick()
        compose.waitForIdle()
        assertEquals(originalWidth * 0.75f,
            compose.onNodeWithTag("page-size").fetchSemanticsNode().boundsInRoot.width, 1f)
    }

    @Test fun menuUsesItsAnchorsDensity() {
        var pageDensity = 0f
        var menuDensity = 0f
        compose.setContent {
            DroidDeckTheme(appScale = 75) {
                val density = LocalDensity.current.density
                SideEffect { pageDensity = density }
                AnchoredMenu(true, onDismiss = {}) {
                    val popupDensity = LocalDensity.current.density
                    SideEffect { menuDensity = popupDensity }
                    MenuItem("Preset", false) {}
                }
            }
        }
        compose.waitForIdle()
        assertEquals(pageDensity, menuDensity, 0f)
    }
}
