package com.droiddeck.launcher.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** A row that shows focus and answers the controller's A button, like the rest of the settings widgets. */
@Composable
internal fun Modifier.focusRow(shape: Shape = RoundedCornerShape(10.dp), role: Role = Role.Button, enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val hot = source.collectIsFocusedAsState().value || source.collectIsHoveredAsState().value
    return clip(shape)
        .background(if (hot && enabled) Color.White.copy(alpha = 0.07f) else Color.Transparent)
        .glideBorder(hot && enabled, shape, LocalPalette.current.signal)
        .hoverable(source)
        .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, role = role, onClick = onClick)
        .controllerConfirm(enabled = enabled, onClick = onClick)
}

@Composable
internal fun PadTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.controllerConfirm(enabled = enabled, onClick = onClick), content = content)
}
