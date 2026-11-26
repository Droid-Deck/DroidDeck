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
internal fun BottomCompanionPage(runningGame: () -> String?) {
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
        else -> CompanionNote(stringResource(R.string.bottom_companion_none))
    }
}

@Composable
private fun CompanionNote(text: String) {
    Box(Modifier.fillMaxSize()) {
        Text(text, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(24.dp))
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
        !connected -> CompanionNote(stringResource(R.string.bottom_companion_stardew_missing))
        !state.inGame -> CompanionNote(stringResource(R.string.bottom_companion_title_screen))
        else -> StardewPanel(state) { slot -> link.send(StardewCompanion.selectSlot(slot)) }
    }
}

@Composable
internal fun StardewPanel(state: StardewCompanion.State, onSlot: (Int) -> Unit) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val twelveHour = !DateFormat.is24HourFormat(LocalContext.current)
    val sprites = remember(state.sprites) { state.sprites.mapValues { (_, png) -> decode(png) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.day?.let { d ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(pal.surface).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${d.weekday} · ${d.season.replaceFirstChar { it.uppercase() }} ${d.day} · ${StardewCompanion.clock(d.time, twelveHour)}",
                    fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
                Text(listOf(d.location, d.weather, "%,dg".format(d.gold)).filter { it.isNotEmpty() }.joinToString(" · "),
                    fontSize = 13.sp, color = colors.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Meter(stringResource(R.string.bottom_companion_energy), d.energy, d.maxEnergy, pal.good)
                    Meter(stringResource(R.string.bottom_companion_health), d.health, d.maxHealth, colors.error)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            state.hotbar.forEachIndexed { slot, item ->
                val selected = slot == state.selected
                Box(
                    Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (selected) pal.signal.copy(alpha = 0.25f) else pal.surface)
                        .border(1.dp, if (selected) pal.signal else pal.line, RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) { onSlot(slot) }.testTag("stardew-slot-$slot"),
                    contentAlignment = Alignment.Center,
                ) {
                    val sprite = item?.let { sprites[it.id] }
                    if (sprite != null) Image(sprite, item.name, Modifier.fillMaxSize().padding(4.dp), filterQuality = FilterQuality.None)
                    else if (item != null) Text(item.name.take(3), fontSize = 10.sp, color = colors.onBackground)
                    if (item != null && item.count > 1) Text("${item.count}", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = colors.onBackground, modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp))
                }
            }
        }
        if (state.birthdays.isNotEmpty()) Section(stringResource(R.string.bottom_companion_birthdays)) {
            for (b in state.birthdays) Text(
                if (b.days == 0) stringResource(R.string.bottom_companion_birthday_today, b.name)
                else stringResource(R.string.bottom_companion_birthday_in, b.name, b.days),
                fontSize = 13.sp, color = colors.onBackground,
            )
        }
        if (state.quests.isNotEmpty()) Section(stringResource(R.string.bottom_companion_quests)) {
            for (q in state.quests) Column {
                Text(q.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (q.complete) pal.good else colors.onBackground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (q.objective.isNotEmpty()) Text(q.objective, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
        CompanionNote(stringResource(if (appId == RpgCompanion.FALLOUT_4) R.string.bottom_companion_pipboy_missing else R.string.bottom_companion_skyrim_missing))
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(pal.surface).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.level?.let { Text(stringResource(R.string.bottom_companion_level, it), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.onBackground) }
            for (bar in state.bars) {
                val (label, color) = when (bar.key) {
                    "hp", "health" -> stringResource(R.string.bottom_companion_health) to colors.error
                    "magicka" -> stringResource(R.string.bottom_companion_magicka) to pal.primary
                    "stamina" -> stringResource(R.string.bottom_companion_stamina) to pal.good
                    else -> stringResource(R.string.bottom_companion_action_points) to pal.good
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("$label ${bar.value.toInt()} / ${bar.max.toInt()}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color)
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(pal.line)) {
                        Box(Modifier.fillMaxWidth(if (bar.max > 0) (bar.value / bar.max).coerceIn(0f, 1f) else 0f).fillMaxHeight().background(color))
                    }
                }
            }
            if (state.facts.isNotEmpty()) {
                val caps = stringResource(R.string.bottom_companion_caps)
                val rads = stringResource(R.string.bottom_companion_rads)
                Text(state.facts.joinToString(" · ") { (key, value) ->
                    when (key) {
                        "caps" -> caps.format(value)
                        "rads" -> rads.format(value)
                        else -> value
                    }
                }, fontSize = 13.sp, color = colors.onSurfaceVariant)
            }
        }
        if (state.slots.isNotEmpty()) Section(stringResource(R.string.bottom_companion_favorites)) {
            for (slot in state.slots) Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (slot.active) pal.signal.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(role = Role.Button) { onSlot(slot) }.testTag("rpg-slot-${slot.key}").padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(slot.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (slot.active) pal.signal else colors.onBackground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(slot.detail, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1)
            }
        }
        state.radio?.let { on -> BottomPillButton(stringResource(if (on) R.string.bottom_companion_radio_off else R.string.bottom_companion_radio_on), "rpg-radio") { onRadio(!on) } }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val pal = LocalPalette.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(pal.surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.signal)
        content()
    }
}

@Composable
private fun Meter(label: String, value: Int, max: Int, color: androidx.compose.ui.graphics.Color) {
    Text("$label $value / $max", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color)
}

private fun decode(png: String): ImageBitmap? = runCatching {
    val bytes = Base64.decode(png, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()
