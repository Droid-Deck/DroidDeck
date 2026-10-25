package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w800dp-h600dp")
class SegmentedTabsAnimationTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun selectionPillFollowsTheAnimatedOffsetLeftToRight() = checkPill(LayoutDirection.Ltr, 1f)

    @Test fun selectionPillStillMirrorsItsOffsetAtHigherDensity() = checkPill(LayoutDirection.Rtl, 2f)

    private fun checkPill(direction: LayoutDirection, density: Float) {
        compose.mainClock.autoAdvance = false
        val selected = mutableIntStateOf(0)
        compose.setContent {
            DroidDeckTheme {
                CompositionLocalProvider(
                    LocalLayoutDirection provides direction,
                    LocalDensity provides Density(density),
                ) {
                    SegmentedTabs(listOf(0 to "First", 1 to "Second", 2 to "Third"), selected.intValue,
                        modifier = Modifier.width(280.dp).testTag("tabs")) { selected.intValue = it }
                }
            }
        }
        for (index in listOf(0, 2, 1, 0, 2, 0)) {
            compose.onNodeWithText(listOf("First", "Second", "Third")[index]).performClick()
            compose.mainClock.advanceTimeBy(2_000)
            val container = compose.onNodeWithTag("tabs").fetchSemanticsNode().boundsInRoot
            val pill = compose.onNodeWithTag("segmented-tabs-selection", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val segment = (280f - 8f) / 3f
            val logicalCenter = (4f + segment * (index + 0.5f)) * density
            val expected = if (direction == LayoutDirection.Ltr) logicalCenter else container.width - logicalCenter
            assertEquals("selection $index in $direction", expected, pill.center.x - container.left, 3f)
        }
    }
}
