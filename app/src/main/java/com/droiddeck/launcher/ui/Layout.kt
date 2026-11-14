package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp


val NarrowPaneWidth = 520.dp

val LocalNarrowPane = compositionLocalOf { false }

@Composable
internal fun isNarrowScreen(): Boolean = LocalConfiguration.current.screenWidthDp < 600

@Composable
internal fun LauncherLayout(
    bottomNavigation: Boolean, divider: Color, modifier: Modifier = Modifier,
    navigation: @Composable () -> Unit, content: @Composable () -> Unit,
) {
    Layout(modifier = modifier, content = {
        Box { content() }
        Box(Modifier.background(divider))
        Box { navigation() }
    }) { children, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val nav = children[2].measure(Constraints(
            minWidth = if (bottomNavigation) width else 0, maxWidth = width,
            minHeight = if (bottomNavigation) 0 else height, maxHeight = height,
        ))
        val line = if ((if (bottomNavigation) nav.height else nav.width) == 0) 0 else 1.dp.roundToPx()
        val contentWidth = if (bottomNavigation) width else (width - nav.width - line).coerceAtLeast(0)
        val contentHeight = if (bottomNavigation) (height - nav.height - line).coerceAtLeast(0) else height
        val pane = children[0].measure(Constraints.fixed(contentWidth, contentHeight))
        val edge = children[1].measure(Constraints.fixed(if (bottomNavigation) width else line, if (bottomNavigation) line else height))
        layout(width, height) {
            if (bottomNavigation) {
                pane.placeRelative(0, 0)
                edge.placeRelative(0, contentHeight)
                nav.placeRelative(0, contentHeight + line)
            } else {
                nav.placeRelative(0, 0)
                edge.placeRelative(nav.width, 0)
                pane.placeRelative(nav.width + line, 0)
            }
        }
    }
}

