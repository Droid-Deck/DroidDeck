package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.StoresNative


@Composable
internal fun StoresEngineRow() {
    val version = StoresNative.version
    SettingsRow(
        stringResource(R.string.setup_stores_engine),
        null,
    ) {
        Chip(if (version != null) stringResource(R.string.setup_stores_engine_chip_ready) else stringResource(R.string.setup_stores_engine_chip_missing), ok = version != null)
    }
}
