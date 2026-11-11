package com.droiddeck.launcher.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

internal object KeyWindows {
    private val views = java.util.concurrent.CopyOnWriteArrayList<android.view.View>()

    fun add(view: android.view.View) {
        views.remove(view)
        views.add(view)
    }

    fun remove(view: android.view.View) { views.remove(view) }

    fun topOn(displayId: Int): android.view.View? =
        views.lastOrNull { it.isAttachedToWindow && it.display?.displayId == displayId }
}

@Composable
internal fun TrackKeyWindow() {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        KeyWindows.add(view)
        onDispose { KeyWindows.remove(view) }
    }
}

internal val LocalPresentationWindow = staticCompositionLocalOf { false }

private val OriginPosition = object : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}

@Composable
internal fun WindowDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    if (!LocalPresentationWindow.current) {
        Dialog(onDismissRequest, properties, content)
        return
    }
    Popup(
        OriginPosition, onDismissRequest,
        PopupProperties(focusable = true, dismissOnBackPress = properties.dismissOnBackPress, dismissOnClickOutside = false, clippingEnabled = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))
                .tapOutside { if (properties.dismissOnClickOutside) onDismissRequest() },
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

@Composable
internal fun GlideDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    WindowDialog(onDismissRequest, properties) { TrackKeyWindow(); FocusGlideHost(content = content) }
}

@Composable
private fun PresentationAlert(
    confirmButton: @Composable () -> Unit,
    modifier: Modifier,
    dismissButton: (@Composable () -> Unit)?,
    icon: (@Composable () -> Unit)?,
    title: (@Composable () -> Unit)?,
    text: (@Composable () -> Unit)?,
    shape: Shape,
    containerColor: Color,
    iconContentColor: Color,
    titleContentColor: Color,
    textContentColor: Color,
    tonalElevation: Dp,
) {
    Surface(modifier.blockTouches().padding(24.dp).widthIn(min = 280.dp, max = 560.dp), shape, containerColor, tonalElevation = tonalElevation) {
        Column(Modifier.padding(24.dp)) {
            icon?.let {
                Box(Modifier.align(Alignment.CenterHorizontally)) { CompositionLocalProvider(LocalContentColor provides iconContentColor, content = it) }
                Spacer(Modifier.height(16.dp))
            }
            title?.let {
                ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                    CompositionLocalProvider(LocalContentColor provides titleContentColor, content = it)
                }
                Spacer(Modifier.height(16.dp))
            }
            text?.let {
                ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                    CompositionLocalProvider(LocalContentColor provides textContentColor, content = it)
                }
                Spacer(Modifier.height(24.dp))
            }
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
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
        if (properties.focusable) TrackKeyWindow()
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
        if (LocalPresentationWindow.current) WindowDialog(onDismissRequest, properties) {
            TrackKeyWindow()
            PresentationAlert(
                confirmButton, modifier.then(overlay), dismissButton, icon, title, text,
                shape, containerColor, iconContentColor, titleContentColor, textContentColor, tonalElevation,
            )
        } else AlertDialog(
            onDismissRequest, confirmButton, modifier.then(overlay), dismissButton, icon, title, text,
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
        DropdownMenu(expanded, onDismissRequest, modifier.then(overlay), offset, scrollState, properties, content)
    }
}
