package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier

internal fun Modifier.launcherSafePadding(insets: FullscreenSafeInsets): Modifier =
    windowInsetsPadding(WindowInsets(insets.left, insets.top, insets.right, insets.bottom))
