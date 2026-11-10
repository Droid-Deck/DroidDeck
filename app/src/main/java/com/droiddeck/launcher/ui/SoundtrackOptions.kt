package com.droiddeck.launcher.ui

import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Soundtrack
import com.droiddeck.launcher.playback.SoundtrackController
import com.droiddeck.launcher.playback.SoundtrackSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private class EqInfo(
    val bands: Int, val min: Int, val max: Int, val freqs: List<Int>, val presets: List<Pair<String, List<Int>>>, val bassSupported: Boolean,
)

private fun readEqInfo(audio: AudioManager): EqInfo? = runCatching {
    val id = audio.generateAudioSessionId()
    val eq = Equalizer(0, id)
    try {
        val count = eq.numberOfBands.toInt()
        val presets = (0 until eq.numberOfPresets.toInt()).map { preset ->
            eq.usePreset(preset.toShort())
            eq.getPresetName(preset.toShort()) to (0 until count).map { eq.getBandLevel(it.toShort()).toInt() }
        }
        val bass = runCatching { BassBoost(0, id).let { b -> try { b.strengthSupported } finally { b.release() } } }.getOrDefault(false)
        EqInfo(
            count, eq.bandLevelRange[0].toInt(), eq.bandLevelRange[1].toInt(),
            (0 until count).map { eq.getCenterFreq(it.toShort()) / 1000 }, presets, bass,
        )
    } finally {
        eq.release()
    }
}.getOrNull()

@Composable
internal fun SoundtrackOptionsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { SoundtrackSettings.load(ctx) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.soundtracks_options)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                RateRow(stringResource(R.string.theme_rgb_speed), SoundtrackSettings.speed, "soundtrack-speed") { SoundtrackController.setSpeed(ctx, it) }
                RateRow(stringResource(R.string.soundtracks_pitch), SoundtrackSettings.pitch, "soundtrack-pitch") { SoundtrackController.setPitch(ctx, it) }
                SwitchRow(stringResource(R.string.soundtracks_skip_silence), SoundtrackSettings.skipSilence, "soundtrack-skip-silence") {
                    SoundtrackController.setSkipSilence(ctx, it)
                }
                SleepSection()
                EqualizerSection()
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.common_close)) } },
    )
}

@Composable
private fun RateRow(label: String, value: Float, tag: String, onChange: (Float) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface, modifier = Modifier.weight(1f))
            Text("%.2f×".format(value), fontSize = 14.sp, color = colors.onSurfaceVariant)
            TextButton(onClick = { onChange(1f) }, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.soundtracks_reset)) }
        }
        Slider(
            value = value, onValueChange = { v -> (v * 20f).roundToInt().div(20f).let { if (it != value) onChange(it) } },
            valueRange = SoundtrackSettings.MIN_RATE..SoundtrackSettings.MAX_RATE, steps = 29,
            colors = SliderDefaults.colors(thumbColor = pal.signal, activeTrackColor = pal.signal, inactiveTrackColor = colors.onSurface.copy(alpha = 0.18f)),
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().focusGlide(RoundedCornerShape(12.dp)).toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp).testTag(tag),
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(99.dp)
    Text(
        label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        color = if (selected) pal.signal else colors.onSurface,
        modifier = Modifier.focusGlide(shape).clip(shape)
            .background(if (selected) pal.signal.copy(alpha = 0.18f) else colors.onSurface.copy(alpha = 0.08f))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun SleepSection() {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var left by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            left = SoundtrackController.sleepRemainingMs()
            delay(1000)
        }
    }
    val trackEnd = SoundtrackController.sleepTrackEnd
    val timed = left > 0L
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.soundtracks_sleep), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface, modifier = Modifier.weight(1f))
            if (timed) Text(stringResource(R.string.soundtracks_sleep_left, Soundtrack.formatLength(left)), fontSize = 13.sp, color = colors.onSurfaceVariant)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionChip(stringResource(R.string.perf_cap_off), !timed && !trackEnd) { SoundtrackController.setSleep(ctx, 0) }
            for (minutes in listOf(15, 30, 60)) OptionChip(stringResource(R.string.soundtracks_sleep_minutes, minutes), false) { SoundtrackController.setSleep(ctx, minutes) }
            OptionChip(stringResource(R.string.soundtracks_sleep_track_end), trackEnd) { SoundtrackController.setSleep(ctx, -1) }
        }
    }
}

@Composable
private fun EqualizerSection() {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val info by produceState<EqInfo?>(null) {
        value = withContext(Dispatchers.IO) { readEqInfo(ctx.getSystemService(AudioManager::class.java)) }
    }
    val ready = info
    val on = SoundtrackSettings.eqOn
    val levels = ready?.let { i -> SoundtrackSettings.bands.takeIf { it.size == i.bands } ?: List(i.bands) { 0 } }.orEmpty()
    val bass = SoundtrackSettings.bass
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SwitchRow(stringResource(R.string.soundtracks_equalizer), on && ready != null, "soundtrack-eq") {
            if (ready != null) SoundtrackController.setEqualizer(ctx, it, levels, bass)
        }
        if (ready == null || ready.bands == 0) {
            if (ready != null) Text(stringResource(R.string.soundtracks_eq_unavailable), fontSize = 13.sp, color = colors.onSurfaceVariant)
            return@Column
        }
        if (ready.presets.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((name, preset) in ready.presets) OptionChip(name, on && preset == levels) { SoundtrackController.setEqualizer(ctx, true, preset, bass) }
        }
        for (band in 0 until ready.bands) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val hz = ready.freqs[band]
                Text(if (hz >= 1000) "%.1f kHz".format(hz / 1000f) else "$hz Hz", fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(end = 2.dp))
                Slider(
                    value = levels[band].toFloat(), valueRange = ready.min.toFloat()..ready.max.toFloat(),
                    onValueChange = { v -> SoundtrackController.setEqualizer(ctx, true, levels.toMutableList().also { it[band] = v.roundToInt() }, bass) },
                    colors = SliderDefaults.colors(thumbColor = pal.signal, activeTrackColor = pal.signal, inactiveTrackColor = colors.onSurface.copy(alpha = 0.18f)),
                    modifier = Modifier.weight(1f).testTag("soundtrack-eq-$band"),
                )
            }
        }
        if (ready.bassSupported) Column {
            Text(stringResource(R.string.soundtracks_bass), fontSize = 13.sp, color = colors.onSurfaceVariant)
            Slider(
                value = bass.toFloat(), valueRange = 0f..1000f,
                onValueChange = { v -> SoundtrackController.setEqualizer(ctx, true, levels, v.roundToInt()) },
                colors = SliderDefaults.colors(thumbColor = pal.signal, activeTrackColor = pal.signal, inactiveTrackColor = colors.onSurface.copy(alpha = 0.18f)),
                modifier = Modifier.fillMaxWidth().testTag("soundtrack-bass"),
            )
        }
        TextButton(onClick = { SoundtrackController.setEqualizer(ctx, on, List(ready.bands) { 0 }, 0) }, modifier = Modifier.focusGlide(CircleShape)) {
            Text(stringResource(R.string.soundtracks_reset))
        }
    }
}
