package com.droiddeck.launcher.ui

import android.graphics.BitmapFactory
import android.text.format.DateFormat
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.width
import com.droiddeck.launcher.companion.CompanionLink
import com.droiddeck.launcher.companion.RpgCompanion
import com.droiddeck.launcher.companion.StardewCompanion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val GAME_POLL_MS = 2_000L

internal fun companionFor(appId: Long?): Long? =
    appId?.takeIf { it == StardewCompanion.APP_ID || it == RpgCompanion.SKYRIM_SE || it == RpgCompanion.FALLOUT_4 }

@Composable
internal fun CompanionPanel(runningGame: () -> String?) {
    var appId by remember { mutableStateOf(runningGame()?.toLongOrNull()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            appId = runningGame()?.toLongOrNull()
            delay(GAME_POLL_MS)
        }
    }
    when (companionFor(appId)) {
        StardewCompanion.APP_ID -> StardewLive()
        RpgCompanion.SKYRIM_SE, RpgCompanion.FALLOUT_4 -> RpgLive(appId!!)
        else -> PanelNote(stringResource(R.string.bottom_companion_none))
    }
}

@Composable
private fun StardewLive() {
    val link = remember { CompanionLink(StardewCompanion.PORT) }
    var state by remember { mutableStateOf(StardewCompanion.State()) }
    var connected by remember { mutableStateOf(false) }
    DisposableEffect(link) { onDispose { link.close() } }
    LaunchedEffect(link) {
        withContext(Dispatchers.IO) {
            link.run(
                onConnected = { connected = it },
                onText = { text -> state = StardewCompanion.reduce(state, text) },
                hello = StardewCompanion.refresh(),
            )
        }
    }
    when {
        !connected -> PanelNote(stringResource(R.string.bottom_companion_stardew_missing))
        !state.inGame -> PanelNote(stringResource(R.string.bottom_companion_title_screen))
        else -> StardewPanel(state) { slot -> link.send(StardewCompanion.selectSlot(slot)) }
    }
}

@Composable
internal fun StardewPanel(state: StardewCompanion.State, onSlot: (Int) -> Unit) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val twelveHour = !DateFormat.is24HourFormat(LocalContext.current)
    val sprites = remember(state.sprites) { state.sprites.mapValues { (_, png) -> decode(png) } }
    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            state.day?.let { d ->
                SettingsGroup(stringResource(R.string.bottom_companion_today)) {
                    PanelRow("${d.weekday} · ${d.season.replaceFirstChar { it.uppercase() }} ${d.day}", listOf(d.location, d.weather).filter { it.isNotEmpty() }.joinToString(" · "),
                        trailing = { PanelValue(StardewCompanion.clock(d.time, twelveHour)) })
                    PanelRow(stringResource(R.string.bottom_companion_gold), trailing = { PanelValue("%,d".format(d.gold), CompanionGold) })
                    MeterRow(stringResource(R.string.bottom_companion_energy), d.energy, d.maxEnergy, pal.good)
                    MeterRow(stringResource(R.string.bottom_companion_health), d.health, d.maxHealth, colors.error)
                }
            }
            SettingsGroup(stringResource(R.string.bottom_companion_hotbar)) {
                for (row in state.hotbar.withIndex().chunked(6)) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((slot, item) in row) {
                        val selected = slot == state.selected
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                                .background(if (selected) pal.signal.copy(alpha = 0.18f) else colors.surfaceVariant)
                                .border(1.dp, if (selected) pal.signal else pal.line2, RoundedCornerShape(8.dp))
                                .clickable(role = Role.Button) { onSlot(slot) }.testTag("stardew-slot-$slot"),
                            contentAlignment = Alignment.Center,
                        ) {
                            val sprite = item?.let { sprites[it.id] }
                            if (sprite != null) Image(sprite, item.name, Modifier.fillMaxSize().padding(5.dp), filterQuality = FilterQuality.None)
                            else if (item != null) Text(item.name.take(3), fontSize = 10.sp, color = colors.onSurfaceVariant)
                            if (item != null && item.count > 1) Text("${item.count}", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                color = colors.onBackground, modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp))
                        }
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            if (state.birthdays.isNotEmpty()) SettingsGroup(stringResource(R.string.bottom_companion_birthdays)) {
                for (b in state.birthdays) PanelRow(b.name,
                    trailing = { PanelValue(if (b.days == 0) stringResource(R.string.bottom_companion_today) else stringResource(R.string.bottom_companion_in_days, b.days),
                        if (b.days == 0) pal.signal else colors.onSurfaceVariant) })
            }
            if (state.quests.isNotEmpty()) SettingsGroup(stringResource(R.string.bottom_companion_quests)) {
                for (q in state.quests) PanelRow(q.title, q.objective, selected = q.complete)
            }
        }
    }
}

@Composable
private fun RpgLive(appId: Long) {
    val link = remember(appId) { CompanionLink(RpgCompanion.port(appId)) }
    var state by remember(appId) { mutableStateOf(RpgCompanion.State()) }
    var connected by remember(appId) { mutableStateOf(false) }
    var seq by remember { mutableStateOf(0) }
    DisposableEffect(link) { onDispose { link.close() } }
    LaunchedEffect(link) {
        withContext(Dispatchers.IO) {
            link.run(
                onConnected = { connected = it },
                onText = { text -> state = RpgCompanion.reduce(appId, state, text) },
                hello = RpgCompanion.subscribe(appId),
            )
        }
    }
    if (!connected) {
        PanelNote(stringResource(if (appId == RpgCompanion.FALLOUT_4) R.string.bottom_companion_pipboy_missing else R.string.bottom_companion_skyrim_missing))
        return
    }
    RpgPanel(state,
        onSlot = { slot -> link.send(RpgCompanion.trigger(appId, slot, ++seq)) },
        onRadio = { on -> link.send(RpgCompanion.radio(on, ++seq)) })
}

@Composable
internal fun RpgPanel(state: RpgCompanion.State, onSlot: (RpgCompanion.Slot) -> Unit, onRadio: (Boolean) -> Unit) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            SettingsGroup(stringResource(R.string.bottom_companion_character), detail = state.level?.let { stringResource(R.string.bottom_companion_level, it) }) {
                for (bar in state.bars) {
                    val (label, color) = when (bar.key) {
                        "hp", "health" -> stringResource(R.string.bottom_companion_health) to colors.error
                        "magicka" -> stringResource(R.string.bottom_companion_magicka) to pal.primary
                        "stamina" -> stringResource(R.string.bottom_companion_stamina) to pal.good
                        else -> stringResource(R.string.bottom_companion_action_points) to pal.good
                    }
                    MeterRow(label, bar.value.toInt(), bar.max.toInt(), color)
                }
                for ((key, value) in state.facts) PanelRow(
                    when (key) {
                        "caps" -> stringResource(R.string.bottom_companion_caps)
                        "rads" -> stringResource(R.string.bottom_companion_rads)
                        else -> stringResource(R.string.bottom_companion_weight)
                    },
                    trailing = { PanelValue(value) },
                )
            }
            state.radio?.let { on ->
                SettingsGroup(stringResource(R.string.bottom_companion_radio)) {
                    SettingsRow(stringResource(R.string.bottom_companion_radio), null) { ToggleSwitch(on, label = stringResource(R.string.bottom_companion_radio)) { onRadio(it) } }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            if (state.slots.isNotEmpty()) SettingsGroup(stringResource(R.string.bottom_companion_favorites)) {
                for (slot in state.slots) PanelRow(slot.label, slot.detail.ifEmpty { null }, tag = "rpg-slot-${slot.key}", selected = slot.active,
                    onClick = { onSlot(slot) })
            }
        }
    }
}

@Composable
private fun MeterRow(label: String, value: Int, max: Int, color: Color) {
    val pal = LocalPalette.current
    PanelRow(label, trailing = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(72.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(pal.line2)) {
                Box(Modifier.fillMaxWidth(if (max > 0) (value.toFloat() / max).coerceIn(0f, 1f) else 0f).fillMaxHeight().background(color))
            }
            PanelValue("$value / $max")
        }
    })
}

private val CompanionGold = Color(0xFFE2B33C)

private fun decode(png: String): ImageBitmap? = runCatching {
    val bytes = Base64.decode(png, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()
