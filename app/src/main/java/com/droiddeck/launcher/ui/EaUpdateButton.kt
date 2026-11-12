package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.ea.EaUpdates

@Composable
internal fun EaUpdateButton(installed: InstalledStoreGame, enabled: Boolean) {
    val ctx = LocalContext.current
    LaunchedEffect(installed.key, installed.sidecar.installVersion) { EaUpdates.check(ctx, installed) }
    if (EaUpdates.available[installed.key] == null) return
    SecondaryButton(stringResource(R.string.common_update), enabled = enabled) { EaUpdates.start(ctx, installed) }
}
