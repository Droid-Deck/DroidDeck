package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

internal object ScreenTaps {
    var count by mutableStateOf(0)
}

/** A field that opens on a heavy page can take focus after the next tap has already finished. */
@Composable
internal fun CancelPendingFocus(pending: Boolean, askedAtTap: Int, focused: Boolean, cancel: () -> Unit) {
    val focusedNow by rememberUpdatedState(focused)
    val cancelNow by rememberUpdatedState(cancel)
    LaunchedEffect(pending, askedAtTap) {
        if (!pending) return@LaunchedEffect
        snapshotFlow { ScreenTaps.count }.first { it != askedAtTap }
        if (!focusedNow) cancelNow()
    }
}
