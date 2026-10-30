package com.droiddeck.launcher.ui

import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import java.util.concurrent.CopyOnWriteArrayList

internal object ActiveGlideWindows {
    private class Entry(
        val view: View,
        val onDismissRequest: (() -> Unit)?,
    )

    private val entries = CopyOnWriteArrayList<Entry>()

    fun register(view: View, onDismissRequest: (() -> Unit)?) {
        entries.removeAll { it.view === view }
        entries.add(Entry(view, onDismissRequest))
    }

    fun unregister(view: View) {
        entries.removeAll { it.view === view }
    }

    private fun topmostEntry(activityDecorView: View?): Entry? {
        val activityRoot = activityDecorView?.rootView
        for (i in entries.indices.reversed()) {
            val entry = entries.getOrNull(i) ?: continue
            val v = entry.view
            if (!v.isAttachedToWindow || !v.isShown) continue
            val root = v.rootView ?: v
            if (activityRoot == null || root !== activityRoot) {
                return entry
            }
        }
        return null
    }

    fun hasActiveOverlay(activityDecorView: View?): Boolean =
        topmostEntry(activityDecorView) != null

    fun dispatchKeyEvent(activityDecorView: View?, event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> return false
        }
        val entry = topmostEntry(activityDecorView) ?: return false
        val root = entry.view.rootView ?: entry.view
        val handled = root.dispatchKeyEvent(event)
        if (!handled && (event.keyCode == KeyEvent.KEYCODE_BUTTON_B || event.keyCode == KeyEvent.KEYCODE_BACK)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                entry.onDismissRequest?.invoke()
            }
        }
        return true
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun TrackActiveGlideWindow(onDismissRequest: (() -> Unit)?) {
    val view = LocalView.current
    val currentDismiss = rememberUpdatedState(onDismissRequest)
    val inputModeManager = LocalInputModeManager.current
    LaunchedEffect(HeldKeys.focusWakeup) {
        if (HeldKeys.focusWakeup > 0 && inputModeManager.inputMode != InputMode.Keyboard) {
            inputModeManager.requestInputMode(InputMode.Keyboard)
        }
    }
    DisposableEffect(view) {
        ActiveGlideWindows.register(view, { currentDismiss.value?.invoke() })
        onDispose { ActiveGlideWindows.unregister(view) }
    }
}

@Composable
internal fun GlideDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest, properties) {
        TrackActiveGlideWindow(onDismissRequest)
        FocusGlideHost(content = content)
    }
}

@Composable
internal fun GlidePopup(
    popupPositionProvider: PopupPositionProvider,
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit,
) {
    Popup(popupPositionProvider, onDismissRequest, properties) {
        TrackActiveGlideWindow(onDismissRequest)
        FocusGlideHost(content = content)
    }
}

@Composable
internal fun GlideAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    FocusGlideWindow { overlay ->
        AlertDialog(
            onDismissRequest,
            confirmButton = {
                TrackActiveGlideWindow(onDismissRequest)
                confirmButton()
            },
            modifier.then(overlay), dismissButton, icon, title, text,
            shape, containerColor, iconContentColor, titleContentColor, textContentColor, tonalElevation, properties,
        )
    }
}

@Composable
internal fun GlideDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    FocusGlideWindow { overlay ->
        DropdownMenu(expanded, onDismissRequest, modifier.then(overlay), offset, scrollState, properties) {
            if (expanded) TrackActiveGlideWindow(onDismissRequest)
            content()
        }
    }
}
