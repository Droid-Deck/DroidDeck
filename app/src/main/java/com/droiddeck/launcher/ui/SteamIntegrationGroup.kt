package com.droiddeck.launcher.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.KeyboardCommandKey
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.SteamIntegration
import com.droiddeck.launcher.core.SteamIntegration.Feature

private val BadgeShape = RoundedCornerShape(10.dp)

internal fun SteamIntegration.State.enabled(feature: Feature) = when (feature) {
    Feature.WIFI -> wifi && locationPermission && locationOn
    Feature.BRIGHTNESS -> brightness
    Feature.HOME -> home
    Feature.SHORTCUTS -> home && shortcuts
}

internal fun SteamIntegration.State.enabledCount() = Feature.entries.count { enabled(it) }

@Composable
internal fun SteamIntegrationGroup(
    state: SteamIntegration.State,
    focus: Feature? = null,
    onFeature: (Feature) -> Unit,
    onAppInfo: () -> Unit,
    onLocation: () -> Unit = {},
) {
    SettingsGroup(
        stringResource(R.string.integration_title),
        detail = stringResource(R.string.integration_count, state.enabledCount(), Feature.entries.size),
    ) {
        Feature.entries.forEachIndexed { i, feature ->
            IntegrationRow(state, feature, focus == feature, i < Feature.entries.lastIndex, onFeature, onAppInfo, onLocation)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun IntegrationRow(
    state: SteamIntegration.State,
    feature: Feature,
    focused: Boolean,
    divider: Boolean,
    onFeature: (Feature) -> Unit,
    onAppInfo: () -> Unit,
    onLocation: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val on = state.enabled(feature)
    var flash by remember { mutableStateOf(false) }
    val reveal = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) {
        if (focused) try { flash = true; reveal.bringIntoView(); kotlinx.coroutines.delay(1600) } finally { flash = false }
    }
    val bg by animateColorAsState(if (flash) pal.signal.copy(alpha = 0.12f) else Color.Transparent, Motion.tw(400), label = "integrationFlash")
    val wifiLocationOff = feature == Feature.WIFI && state.wifi && state.locationPermission && !state.locationOn
    val wifiDenied = feature == Feature.WIFI && !state.locationPermission && state.locationBlocked
    val needsHome = feature == Feature.SHORTCUTS && !state.home
    val restricted = feature == Feature.SHORTCUTS && !needsHome && !state.shortcuts && state.shortcutsRestricted
    val maybeRestricted = feature == Feature.SHORTCUTS && !needsHome && !state.shortcuts && !restricted && state.shortcutsTried &&
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
    val hint = stringResource(when {
        wifiLocationOff -> R.string.integration_wifi_location_off
        wifiDenied -> R.string.integration_wifi_denied
        needsHome -> R.string.integration_shortcuts_needs_home
        restricted -> R.string.integration_shortcuts_restricted
        maybeRestricted -> R.string.integration_shortcuts_maybe_restricted
        feature == Feature.WIFI -> if (on) R.string.integration_wifi_on else R.string.integration_wifi_hint
        feature == Feature.BRIGHTNESS -> R.string.integration_brightness_hint
        feature == Feature.HOME -> R.string.integration_home_hint
        else -> R.string.integration_shortcuts_hint
    })
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(reveal).background(bg).heightIn(min = 64.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("integration-${feature.name.lowercase()}"),
    ) {
        FeatureBadge(featureIcon(feature), on)
        Column(Modifier.weight(1f)) {
            Text(stringResource(featureTitle(feature)), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(hint, fontSize = 13.sp, color = if (wifiLocationOff || wifiDenied || restricted) AttentionAmber else colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                wifiLocationOff -> SecondaryButton(stringResource(R.string.integration_location), compact = true, onClick = onLocation)
                wifiDenied -> SecondaryButton(stringResource(R.string.integration_app_info), compact = true, onClick = onLocation)
                restricted || maybeRestricted -> SecondaryButton(stringResource(R.string.integration_app_info), compact = true, onClick = onAppInfo)
            }
            val checked = if (feature == Feature.WIFI) state.wifi && state.locationPermission else on
            ToggleSwitch(checked, enabled = !needsHome && !restricted, label = stringResource(featureTitle(feature)),
                modifier = Modifier.testTag("integration-action-${feature.name.lowercase()}")) { onFeature(feature) }
        }
    }
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

@Composable
private fun FeatureBadge(icon: ImageVector, on: Boolean) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val tint by animateColorAsState(if (on) pal.good else colors.onSurfaceVariant, Motion.tw(250), label = "badgeTint")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(36.dp).clip(BadgeShape)
            .background(if (on) pal.good.copy(alpha = 0.14f) else colors.surfaceVariant)
            .border(1.dp, if (on) pal.good.copy(alpha = 0.3f) else pal.line2, BadgeShape),
    ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp)) }
}

private fun featureIcon(feature: Feature) = when (feature) {
    Feature.WIFI -> Icons.Outlined.Wifi
    Feature.BRIGHTNESS -> Icons.Outlined.LightMode
    Feature.HOME -> Icons.Outlined.Home
    Feature.SHORTCUTS -> Icons.Outlined.KeyboardCommandKey
}

private fun featureTitle(feature: Feature) = when (feature) {
    Feature.WIFI -> R.string.integration_wifi
    Feature.BRIGHTNESS -> R.string.integration_brightness
    Feature.HOME -> R.string.integration_home
    Feature.SHORTCUTS -> R.string.integration_shortcuts
}

@Composable
internal fun IntegrationSuggestion(state: SteamIntegration.State, onOpen: () -> Unit, onLater: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val pill = RoundedCornerShape(99.dp)
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(pill).background(colors.surfaceVariant.copy(alpha = 0.85f))
            .border(1.dp, pal.signal.copy(alpha = 0.3f), pill).testTag("integration-suggestion"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.heightIn(min = 40.dp).clip(pill).glideBorder(hot, pill, pal.signal, Color.Transparent)
                .hoverable(src).clickable(role = Role.Button, interactionSource = src, indication = LocalIndication.current, onClick = onOpen)
                .controllerConfirm(onClick = onOpen)
                .padding(start = 12.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = pal.signal, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.integration_title), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1)
            Text(stringResource(R.string.integration_count, state.enabledCount(), Feature.entries.size), fontSize = 12.sp,
                color = colors.onSurfaceVariant, maxLines = 1)
        }
        val closeSrc = remember { MutableInteractionSource() }
        val closeHot = rememberHot(closeSrc)
        val later = stringResource(R.string.common_close)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(end = 4.dp).size(32.dp).clip(CircleShape)
                .background(if (closeHot) pal.signal.copy(alpha = 0.16f) else Color.Transparent)
                .glideBorder(closeHot, CircleShape, pal.signal, Color.Transparent)
                .hoverable(closeSrc).clickable(role = Role.Button, interactionSource = closeSrc, indication = LocalIndication.current, onClick = onLater)
                .controllerConfirm(onClick = onLater)
                .semantics { contentDescription = later },
        ) { Icon(Icons.Outlined.Close, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp)) }
    }
}
