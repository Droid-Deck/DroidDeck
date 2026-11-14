package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.itch.ItchUpdate
import com.droiddeck.launcher.stores.itch.ItchUpdates

@Composable
internal fun ItchCheckButton(installed: InstalledStoreGame) {
    val ctx = LocalContext.current
    IconAction(Icons.Filled.Refresh, stringResource(R.string.store_check_updates), "game:check-updates", enabled = ItchUpdates.checkingKey == null, frosted = true) {
        ItchUpdates.checkGame(ctx, installed)
    }
}

@Composable
internal fun ItchUpdateButton(installed: InstalledStoreGame, enabled: Boolean) {
    val ctx = LocalContext.current
    val update = ItchUpdates.available[installed.key] ?: return
    var asking by remember { mutableStateOf(false) }
    SecondaryButton(stringResource(R.string.common_update), enabled = enabled) {
        if (update.replaced) asking = true else ItchUpdates.start(ctx, installed, update)
    }
    if (asking) ReplacedDialog(installed, update) { asking = false }
}

@Composable
private fun ReplacedDialog(installed: InstalledStoreGame, update: ItchUpdate, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    AppDialog(shown, close, "itchUpdate", wide = false, maxWidth = 460.dp) {
        DialogTitleRow(installed.sidecar.title, close)
        Text(
            stringResource(R.string.stores_itch_update_replaced, update.filename.ifBlank { update.uploadId }),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(stringResource(R.string.common_update)) {
                close()
                ItchUpdates.start(ctx, installed, update)
            }
            SecondaryButton(stringResource(R.string.common_cancel)) { close() }
        }
    }
}
