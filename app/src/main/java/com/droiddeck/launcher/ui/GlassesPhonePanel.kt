package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R

/** Actions for the phone while the picture is on the glasses. Null hides the button. */
internal class GlassesPhoneActions(
    val onRecenter: (() -> Unit)?,
    val onRecenterJoyCons: (() -> Unit)?,
    val onSteamVrMenu: (() -> Unit)?,
    val onSwitchSticks: (() -> Unit)?,
    val controllers: String?,
    val onNextControllers: (() -> Unit)?,
    val onKeyboard: () -> Unit,
    val onSessionMenu: () -> Unit,
    val onStopSteamVr: (() -> Unit)?,
    val brightness: Int,
    val brightnessMax: Int,
    val onBrightness: ((Int) -> Unit)?,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GlassesPhonePanel(model: String, status: String?, a: GlassesPhoneActions) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout).padding(16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 640.dp)) {
            Icon(GlassesIcon, null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.glasses_playing, model), color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
            if (status != null) Text(status, color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                a.onRecenter?.let { PanelButton(stringResource(R.string.glasses_phone_recenter), "phone-recenter", it) }
                a.onRecenterJoyCons?.let { PanelButton(stringResource(R.string.glasses_recenter_joycons_button), "phone-recenter-joycons", it) }
                a.onSteamVrMenu?.let { PanelButton(stringResource(R.string.glasses_phone_steamvr_menu), "phone-steamvr-menu", it) }
                a.onNextControllers?.let { next -> a.controllers?.let {
                    PanelButton(stringResource(R.string.glasses_phone_controllers, it), "phone-controllers", next) } }
                a.onSwitchSticks?.let { PanelButton(stringResource(R.string.glasses_phone_switch_sticks), "phone-switch-sticks", it) }
                PanelButton(stringResource(R.string.glasses_phone_keyboard), "phone-keyboard", a.onKeyboard)
                PanelButton(stringResource(R.string.glasses_phone_session_menu), "phone-session-menu", a.onSessionMenu)
                a.onStopSteamVr?.let { PanelButton(stringResource(R.string.glasses_phone_stop_steamvr), "phone-stop-steamvr", it) }
            }
            val setBrightness = a.onBrightness
            if (setBrightness != null && a.brightnessMax > 0) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PanelButton("−", "phone-brightness-down") { setBrightness((a.brightness - 1).coerceAtLeast(0)) }
                    Text(stringResource(R.string.glasses_phone_brightness, a.brightness.coerceAtLeast(0), a.brightnessMax),
                        color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                    PanelButton("+", "phone-brightness-up") { setBrightness((a.brightness + 1).coerceAtMost(a.brightnessMax)) }
                }
            }
        }
    }
}

@Composable
private fun PanelButton(label: String, tag: String, onClick: () -> Unit) =
    SecondaryButton(label, compact = true, modifier = Modifier.testTag(tag), onClick = onClick)
