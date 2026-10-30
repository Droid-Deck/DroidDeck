package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.UsbLink

@Composable
internal fun SlowUsbDialog(drive: UsbLink.SlowDrive, never: Boolean, onNever: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val speed = if (drive.mbps % 1.0 == 0.0) drive.mbps.toLong().toString() else drive.mbps.toString()
    val message = if (drive.portLimited) R.string.slow_usb_port else R.string.slow_usb_cable
    AppDialog(shown, close, "slowUsb", wide = false, maxWidth = 520.dp) {
        DialogTitleRow(stringResource(R.string.slow_usb_title), close) {
            Icon(Icons.Filled.Usb, null, tint = LocalPalette.current.signal, modifier = Modifier.size(20.dp))
        }
        Text(
            stringResource(message, drive.name, UsbLink.generation(drive.mbps), speed, UsbLink.megabytesPerSecond(drive.mbps)),
            fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val pal = LocalPalette.current
        val src = remember { MutableInteractionSource() }
        val hot = rememberHot(src)
        val flip = { onNever(!never) }
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().clip(Shape12)
                .background(if (hot) pal.signal.copy(alpha = 0.12f) else Color.Transparent)
                .border(if (hot) 2.dp else 1.dp, if (hot) pal.signal else pal.line, Shape12)
                .hoverable(src)
                .clickable(interactionSource = src, indication = null, role = Role.Switch, onClick = flip)
                .controllerConfirm(onClick = flip)
                .padding(start = 14.dp, end = 4.dp),
        ) {
            Text(stringResource(R.string.slow_usb_never), fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
            ToggleSwitch(never, label = stringResource(R.string.slow_usb_never), modifier = Modifier.focusProperties { canFocus = false }) { onNever(it) }
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            PrimaryButton(stringResource(R.string.slow_usb_dismiss), modifier = Modifier.focusRequester(focus), onClick = close)
        }
    }
}
