package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.hud.DroidDeckHudConfig
import com.droiddeck.launcher.hud.DroidDeckHudSize
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.StripMetric

@Composable
internal fun DroidDeckHudSettings(host: MenuHost, a: DrawerActions, track: (String) -> Modifier) {
    val cfg = a.hudConfig
    fun on(key: String) = cfg[key] == "1"
    fun int(key: String, fallback: Int) = cfg[key]?.toIntOrNull() ?: fallback

    SettingsGroup(stringResource(R.string.drawer_hud)) {
        ToggleRow(host, "hud", stringResource(R.string.drawer_hud), null, a.hudOn, chipModifier = track("hud"), onChange = a.onHud)
        if (!a.hudOn) return@SettingsGroup
        val size = DroidDeckHudSize.from(cfg["hudSize"])
        ChoiceRow(host, "hud-size", stringResource(R.string.hud_size), stringResource(R.string.hud_size_hint),
            listOf(
                DroidDeckHudSize.FULL to stringResource(R.string.hud_size_full),
                DroidDeckHudSize.TILES to stringResource(R.string.hud_size_tiles),
                DroidDeckHudSize.PILL to stringResource(R.string.hud_size_pill),
                DroidDeckHudSize.MINIMAL to stringResource(R.string.hud_size_minimal),
                DroidDeckHudSize.MEGA to stringResource(R.string.hud_size_mega),
                DroidDeckHudSize.STRIP to stringResource(R.string.hud_size_strip),
            ),
            size, chipModifier = track("hud-size")) { a.onHudSetting("hudSize", it.token) }
        ToggleRow(host, "hud-locked", stringResource(R.string.hud_locked), stringResource(R.string.hud_locked_hint), on("hudLocked"),
            chipModifier = track("hud-locked")) { a.onHudSetting("hudLocked", flag(it)) }
        ToggleRow(host, "hud-snap", stringResource(R.string.hud_snap), stringResource(R.string.hud_snap_hint), on("hudSnap"),
            chipModifier = track("hud-snap")) { a.onHudSetting("hudSnap", flag(it)) }
    }
    if (a.dashboardAvailable && a.secondScreenDisplays.isNotEmpty()) BottomStripSettings(host, track)
    if (!a.hudOn) return

    SettingsGroup(stringResource(R.string.hud_group_look)) {
        SliderRow(stringResource(R.string.hud_scale), null, int("hudScale", 100), 50..150, step = 5, format = { "$it%" },
            modifier = track("hud-scale")) { a.onHudSetting("hudScale", it.toString()) }
        SliderRow(stringResource(R.string.drawer_hud_opacity), null, int("hudOpacity", 80), 0..100, step = 5, format = { "$it%" },
            modifier = track("hud-opacity")) { a.onHudSetting("hudOpacity", it.toString()) }
        ChoiceRow(host, "hud-color", stringResource(R.string.drawer_hud_color), null,
            listOf("soft" to stringResource(R.string.hud_color_soft), "mid" to stringResource(R.string.hud_color_mid),
                "vivid" to stringResource(R.string.hud_color_vivid)),
            cfg["hudColor"] ?: "mid", chipModifier = track("hud-color")) { a.onHudSetting("hudColor", it) }
        SliderRow(stringResource(R.string.hud_outline), null, int("hudOutline", 40), 0..100, step = 10,
            modifier = track("hud-outline")) { a.onHudSetting("hudOutline", it.toString()) }
        ChoiceRow(host, "hud-outline-color", stringResource(R.string.hud_outline_color), null,
            listOf("0" to stringResource(R.string.hud_outline_gray), "1" to stringResource(R.string.hud_outline_theme)),
            if (cfg["hudOutlineAccent"] == "0") "0" else "1", chipModifier = track("hud-outline-color")) { a.onHudSetting("hudOutlineAccent", it) }
        ToggleRow(host, "hud-fps-decimal", stringResource(R.string.hud_metric_decimal), null, on("fpsDecimal"),
            chipModifier = track("hud-fps-decimal")) { a.onHudSetting("fpsDecimal", flag(it)) }
    }

    val order = DroidDeckHudConfig.order(cfg)
    SettingsGroup(stringResource(R.string.hud_group_metrics)) {
        for ((index, key) in order.withIndex()) {
            val metric = METRICS.firstOrNull { it.key == key } ?: continue
            HudMetricRow(host, metric, index, order.size, on(metric.key), track("hud-${metric.key}"),
                onChange = { a.onHudSetting(metric.key, flag(it)) },
                onMove = { a.onHudMove(metric.key, it) })
        }
    }

    if (on("showTemp") || on("showGpuTemp")) SettingsGroup(stringResource(R.string.hud_group_temps)) {
        ChoiceRow(host, "hud-temp-unit", stringResource(R.string.hud_temp_unit), null,
            listOf("c" to "°C", "f" to "°F"), if (cfg["tempUnit"].equals("f", true)) "f" else "c",
            chipModifier = track("hud-temp-unit")) { a.onHudSetting("tempUnit", it) }
        val bands = when {
            cfg["tempBands"] == "0" -> "0"
            cfg["tempAuto"] == "0" -> "2"
            else -> "1"
        }
        ChoiceRow(host, "hud-temp-bands", stringResource(R.string.hud_temp_bands), stringResource(R.string.hud_temp_bands_hint),
            listOf("0" to stringResource(R.string.hud_temp_bands_off), "1" to stringResource(R.string.hud_temp_bands_auto),
                "2" to stringResource(R.string.hud_temp_bands_manual)),
            bands, chipModifier = track("hud-temp-bands")) {
            a.onHudSetting("tempBands", if (it == "0") "0" else "1")
            a.onHudSetting("tempAuto", if (it == "2") "0" else "1")
        }
        if (bands == "2") {
            SliderRow(stringResource(R.string.hud_temp_red_cpu), null, int("tempRedCpu", 90), 50..110, format = { "$it°C" },
                modifier = track("hud-red-cpu")) { a.onHudSetting("tempRedCpu", it.toString()) }
            SliderRow(stringResource(R.string.hud_temp_red_gpu), null, int("tempRedGpu", 90), 50..110, format = { "$it°C" },
                modifier = track("hud-red-gpu")) { a.onHudSetting("tempRedGpu", it.toString()) }
            SliderRow(stringResource(R.string.hud_temp_red_battery), null, int("tempRedBat", 48), 35..60, format = { "$it°C" },
                modifier = track("hud-red-battery")) { a.onHudSetting("tempRedBat", it.toString()) }
        }
    }

    SettingsGroup(stringResource(R.string.hud_group_more)) {
        ActionRow(stringResource(R.string.hud_export), stringResource(R.string.hud_export_hint),
            stringResource(R.string.hud_export_button), onClick = a.onHudExport, modifier = track("hud-export"))
        ActionRow(stringResource(R.string.hud_reset), null, stringResource(R.string.hud_reset_button),
            onClick = a.onHudReset, modifier = track("hud-reset"))
    }
}

private fun flag(on: Boolean) = if (on) "1" else "0"

@Composable
private fun BottomStripSettings(host: MenuHost, track: (String) -> Modifier) {
    val context = LocalContext.current
    var hudOn by remember { mutableStateOf(SessionPrefs.bottomHud(context)) }
    var onTabs by remember { mutableStateOf(SessionPrefs.bottomStripOnTabs(context)) }
    var shown by remember { mutableStateOf(SessionPrefs.bottomStripMetrics(context)) }
    SettingsGroup(stringResource(R.string.bottom_hud_group)) {
        ToggleRow(host, "bottom-hud", stringResource(R.string.bottom_hud_group), null, hudOn,
            chipModifier = track("bottom-hud")) { on ->
            hudOn = on
            SessionPrefs.setBottomHud(context, on)
        }
        ToggleRow(host, "bottom-strip-tabs", stringResource(R.string.bottom_strip_tabs), null, onTabs,
            enabled = hudOn, chipModifier = track("bottom-strip-tabs")) { on ->
            onTabs = on
            SessionPrefs.setBottomStripOnTabs(context, on)
        }
        for ((metric, label) in BOTTOM_STRIP_ROWS) {
            ToggleRow(host, "bottom-strip-${metric.id}", stringResource(label), null, metric in shown,
                enabled = hudOn, chipModifier = track("bottom-strip-${metric.id}")) { on ->
                shown = if (on) shown + metric else shown - metric
                SessionPrefs.setBottomStripMetric(context, metric, on)
            }
        }
    }
}

private val BOTTOM_STRIP_ROWS = listOf(
    StripMetric.ENGINE to R.string.hud_metric_engine,
    StripMetric.GRAPH to R.string.hud_metric_graph,
    StripMetric.LOWS to R.string.bottom_strip_lows,
    StripMetric.LOW001 to R.string.hud_metric_low001,
    StripMetric.GPU to R.string.hud_metric_gpu,
    StripMetric.GPU_TEMP to R.string.hud_metric_gpu_temp,
    StripMetric.CPU to R.string.hud_metric_cpu,
    StripMetric.CPU_TEMP to R.string.bottom_strip_cpu_temp,
    StripMetric.VRAM to R.string.hud_metric_vram,
    StripMetric.RAM to R.string.hud_metric_ram,
    StripMetric.CLOCK to R.string.hud_metric_clock,
)

private class HudMetric(val key: String, val label: Int)

@Composable
private fun HudMetricRow(
    host: MenuHost, metric: HudMetric, index: Int, count: Int, checked: Boolean,
    modifier: Modifier, onChange: (Boolean) -> Unit, onMove: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    SettingsRow(stringResource(metric.label), null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToggleSwitch(checked, true, stringResource(metric.label), modifier) {
                host.open = null; onChange(it)
            }
            MoveButton(Icons.Filled.KeyboardArrowUp, stringResource(R.string.hud_move_up), colors.onBackground, index > 0) { onMove(true) }
            MoveButton(Icons.Filled.KeyboardArrowDown, stringResource(R.string.hud_move_down), colors.onBackground, index < count - 1) { onMove(false) }
        }
    }
}

@Composable
private fun MoveButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String,
                       tint: Color, enabled: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(36.dp).alpha(if (enabled) 1f else 0.3f)
            .clip(RoundedCornerShape(8.dp))
            .hoverable(src)
            .clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .controllerConfirm(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
}

// Wrapper and DX version have nothing to show in DroidDeck.
private val METRICS = listOf(
    HudMetric("showGpuModel", R.string.hud_metric_gpu_model),
    HudMetric("showGPU", R.string.hud_metric_gpu),
    HudMetric("showCPUUsage", R.string.hud_metric_cpu),
    HudMetric("showPerCore", R.string.hud_metric_per_core),
    HudMetric("showTemp", R.string.hud_metric_temp),
    HudMetric("showGpuTemp", R.string.hud_metric_gpu_temp),
    HudMetric("showVram", R.string.hud_metric_vram),
    HudMetric("showRAM", R.string.hud_metric_ram),
    HudMetric("showSwap", R.string.hud_metric_swap),
    HudMetric("showBattery", R.string.hud_metric_battery),
    HudMetric("showBatteryTemp", R.string.drawer_hud_battery),
    HudMetric("showPower", R.string.hud_metric_power),
    HudMetric("showFPS", R.string.drawer_hud_fps),
    HudMetric("showFPSGraph", R.string.hud_metric_graph),
    HudMetric("showLatency", R.string.hud_metric_latency),
    HudMetric("showAvg", R.string.hud_metric_avg),
    HudMetric("showLow1", R.string.hud_metric_low1),
    HudMetric("showLow01", R.string.hud_metric_low01),
    HudMetric("showLow001", R.string.hud_metric_low001),
    HudMetric("showEngine", R.string.hud_metric_engine),
    HudMetric("showDisplay", R.string.hud_metric_display),
    HudMetric("showHdr", R.string.hud_metric_hdr),
    HudMetric("showNet", R.string.hud_metric_network),
    HudMetric("showResolution", R.string.hud_metric_resolution),
    HudMetric("showProton", R.string.hud_metric_proton),
    HudMetric("showSession", R.string.hud_metric_session),
    HudMetric("showClock", R.string.hud_metric_clock),
)
