package com.droiddeck.launcher.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.SteamIntegration
import com.droiddeck.launcher.core.SteamIntegration.Feature

@Composable
internal fun SteamIntegrationDialog(
    state: SteamIntegration.State,
    selected: Feature?,
    onSelect: (Feature?) -> Unit,
    onContinue: (Feature) -> Unit,
    onAppInfo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val back = { if (selected != null) onSelect(null) else onDismiss() }
    GlideAlertDialog(
        onDismissRequest = back,
        modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().testTag("steam-integration"),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(selected?.let(::integrationTitle) ?: R.string.integration_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val feature = selected
                if (feature == null) {
                    Text(stringResource(R.string.integration_optional))
                    Feature.entries.forEach { item ->
                        val enabled = when (item) {
                            Feature.WIFI -> state.wifi && state.locationPermission && state.locationOn
                            Feature.BRIGHTNESS -> state.brightness
                            Feature.HOME -> state.home
                            Feature.SHORTCUTS -> state.home && state.shortcuts
                        }
                        val status = when {
                            enabled -> R.string.integration_enabled
                            item == Feature.WIFI && state.wifi && !state.locationOn -> R.string.mode_wifi_location_off
                            item == Feature.SHORTCUTS && state.shortcuts -> R.string.integration_choose_home
                            else -> R.string.integration_set_up
                        }
                        ActionRow(stringResource(integrationTitle(item)), null, stringResource(status),
                            onClick = { onSelect(item) }, modifier = Modifier.testTag("integration-${item.name.lowercase()}"))
                    }
                } else {
                    Text(stringResource(when (feature) {
                        Feature.WIFI -> R.string.mode_wifi_explain_text
                        Feature.BRIGHTNESS -> R.string.integration_brightness_description
                        Feature.HOME -> R.string.integration_home_description
                        Feature.SHORTCUTS -> if (state.home) R.string.integration_shortcuts_description else R.string.integration_shortcuts_home_first
                    }))
                    if (feature == Feature.WIFI && state.wifi && !state.locationOn) Text(stringResource(R.string.mode_wifi_location_off))
                    if (feature == Feature.SHORTCUTS && state.home && !state.shortcuts && Build.VERSION.SDK_INT >= 33) {
                        Text(stringResource(R.string.integration_restricted_help))
                        SecondaryButton(stringResource(R.string.integration_app_info), onClick = onAppInfo)
                    }
                }
            }
        },
        confirmButton = {
            val feature = selected
            if (feature == null) SecondaryButton(stringResource(R.string.integration_done), onClick = onDismiss)
            else PrimaryButton(stringResource(when {
                feature == Feature.WIFI && state.wifi && state.locationPermission && state.locationOn -> R.string.integration_turn_off
                feature == Feature.HOME || (feature == Feature.SHORTCUTS && !state.home) -> R.string.integration_choose_home
                feature == Feature.SHORTCUTS && state.shortcuts -> R.string.mode_wifi_open_settings
                else -> R.string.mode_wifi_continue
            }), modifier = Modifier.testTag("integration-continue"), onClick = { onContinue(feature) })
        },
        dismissButton = if (selected == null) null else ({ SecondaryButton(stringResource(R.string.common_back), onClick = back) }),
    )
}

private fun integrationTitle(feature: Feature) = when (feature) {
    Feature.WIFI -> R.string.integration_wifi
    Feature.BRIGHTNESS -> R.string.integration_brightness
    Feature.HOME -> R.string.integration_home
    Feature.SHORTCUTS -> R.string.integration_shortcuts
}

@Composable
internal fun IntegrationSuggestion(onOpen: () -> Unit, onLater: () -> Unit) {
    Column(
        modifier = Modifier.clip(Shape12).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, LocalPalette.current.line, Shape12).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.integration_suggestion))
        Actions {
            SecondaryButton(stringResource(R.string.integration_set_up), onClick = onOpen)
            SecondaryButton(stringResource(R.string.integration_later), onClick = onLater)
        }
    }
}
