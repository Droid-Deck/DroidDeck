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
import com.droiddeck.launcher.stores.gamejolt.GameJoltUpdate
import com.droiddeck.launcher.stores.gamejolt.GameJoltUpdates

@Composable
internal fun GameJoltCheckButton(installed: InstalledStoreGame) {
    val ctx = LocalContext.current
    IconAction(Icons.Filled.Refresh, stringResource(R.string.store_check_updates), "game:check-updates", enabled = GameJoltUpdates.checkingKey == null, frosted = true) {
        GameJoltUpdates.checkGame(ctx, installed)
    }
}

@Composable
internal fun GameJoltUpdateButton(installed: InstalledStoreGame, enabled: Boolean) {
    val ctx = LocalContext.current
    val update = GameJoltUpdates.available[installed.key] ?: return
    var asking by remember { mutableStateOf(false) }
    SecondaryButton(stringResource(R.string.common_update), enabled = enabled) {
        if (update.replaced) asking = true else GameJoltUpdates.start(ctx, installed, update)
    }
    if (asking) GameJoltReplacedDialog(installed, update) { asking = false }
}

@Composable
private fun GameJoltReplacedDialog(installed: InstalledStoreGame, update: GameJoltUpdate, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    AppDialog(shown, close, "gameJoltUpdate", wide = false, maxWidth = 460.dp) {
        DialogTitleRow(installed.sidecar.title, close)
        Text(
            stringResource(R.string.stores_itch_update_replaced, update.filename.ifBlank { update.buildId }),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(stringResource(R.string.common_update)) {
                close()
                GameJoltUpdates.start(ctx, installed, update)
            }
            SecondaryButton(stringResource(R.string.common_cancel)) { close() }
        }
    }
}
