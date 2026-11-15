package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
        DialogToggleRow(stringResource(R.string.slow_usb_never), never, onNever)
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            PrimaryButton(stringResource(R.string.slow_usb_dismiss), modifier = Modifier.focusRequester(focus), onClick = close)
        }
    }
}
