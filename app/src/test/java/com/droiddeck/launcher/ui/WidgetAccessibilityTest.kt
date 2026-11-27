package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetAccessibilityTest {
    @get:Rule val compose = createRobolectricComposeRule()

    private fun ellipsized(text: String): Boolean {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
        return results.single().isLineEllipsized(0)
    }

    @Test fun aSliderTellsAccessibilityServicesHowManyStepsItHas() {
        compose.setContent { DroidDeckTheme { ValueSlider(100, 80..125, 5, true, "Layout size", { "$it%" }) { } } }
        val info = compose.onNodeWithText("Layout size", useUnmergedTree = true).fetchSemanticsNode().let { node ->
            generateSequence(node) { it.parent }.first { SemanticsProperties.ProgressBarRangeInfo in it.config }.config[SemanticsProperties.ProgressBarRangeInfo]
        }
        assertEquals(8, info.steps)
        assertEquals(80f..125f, info.range)
    }

    @Test fun aTabLabelTooLongForItsSegmentEndsInAnEllipsis() {
        val long = "Controller buttons and more"
        compose.setContent { DroidDeckTheme { SegmentedTabs(listOf(0 to long, 1 to "Groups"), 0, Modifier.width(140.dp)) { } } }
        assertTrue(ellipsized(long))
    }

    @Test fun aPrimaryButtonLabelTooLongForItsWidthEndsInAnEllipsis() {
        val long = "Save every change to this layout"
        compose.setContent { DroidDeckTheme { PrimaryButton(long, compact = true, centred = true, modifier = Modifier.width(80.dp)) { } } }
        assertTrue(ellipsized(long))
    }
}
