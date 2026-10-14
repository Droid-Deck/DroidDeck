package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp


val NarrowPaneWidth = 520.dp

val LocalNarrowPane = compositionLocalOf { false }

@Composable
internal fun isNarrowScreen(): Boolean = LocalConfiguration.current.screenWidthDp < 600

