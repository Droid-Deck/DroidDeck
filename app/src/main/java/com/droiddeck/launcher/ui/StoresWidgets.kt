package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.StoresNative

// Controls the Stores section and its Setup group share.

/** Whether the native download engine is in this build, said where the stores are turned on. */
@Composable
internal fun StoresEngineRow() {
    val version = StoresNative.version
    SettingsRow(
        stringResource(R.string.setup_stores_engine),
        if (version != null) stringResource(R.string.setup_stores_engine_ready, version)
        else stringResource(R.string.setup_stores_engine_missing),
    ) {
        Chip(if (version != null) stringResource(R.string.setup_stores_engine_chip_ready) else stringResource(R.string.setup_stores_engine_chip_missing), ok = version != null)
    }
}
