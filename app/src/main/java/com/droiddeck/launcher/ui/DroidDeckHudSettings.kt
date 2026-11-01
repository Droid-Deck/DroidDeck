package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.hud.DroidDeckHudSize
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.StripMetric

/** Bannerlator's DroidDeck HUD settings (DeckOverlaySettings): one row per key of the HUD's config string. */
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
    // The bottom screen's own numbers: shown with the top HUD off too, they do not change the top HUD.
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
    }

    val size = DroidDeckHudSize.from(cfg["hudSize"])
    val chips = size.supportedChips() + EXTRA[size].orEmpty()
    SettingsGroup(stringResource(R.string.hud_group_metrics)) {
        for (metric in METRICS) {
            if (metric.chip !in chips || metric.chip in HIDDEN[size].orEmpty()) continue
            ToggleRow(host, "hud-${metric.key}", stringResource(metric.label), null, on(metric.key),
                chipModifier = track("hud-${metric.key}")) { a.onHudSetting(metric.key, flag(it)) }
        }
    }

    val tempsShown = ("Temp" in chips && on("showTemp")) || ("GPU temp" in chips && on("showGpuTemp"))
    if (tempsShown) SettingsGroup(stringResource(R.string.hud_group_temps)) {
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

/** The strip under the bottom-screen tabs and the In-game stats Line strip: on/off and which numbers they show. */
@Composable
private fun BottomStripSettings(host: MenuHost, track: (String) -> Modifier) {
    val context = LocalContext.current
    var onTabs by remember { mutableStateOf(SessionPrefs.bottomStripOnTabs(context)) }
    var shown by remember { mutableStateOf(SessionPrefs.bottomStripMetrics(context)) }
    SettingsGroup(stringResource(R.string.bottom_hud_group), detail = stringResource(R.string.bottom_hud_group_detail)) {
        ToggleRow(host, "bottom-strip-tabs", stringResource(R.string.bottom_strip_tabs), stringResource(R.string.bottom_strip_tabs_hint), onTabs,
            chipModifier = track("bottom-strip-tabs")) { on ->
            onTabs = on
            SessionPrefs.setBottomStripOnTabs(context, on)
        }
        for ((metric, label) in BOTTOM_STRIP_ROWS) {
            ToggleRow(host, "bottom-strip-${metric.id}", stringResource(label), null, metric in shown,
                chipModifier = track("bottom-strip-${metric.id}")) { on ->
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

// The Pill also draws the battery temperature and watts.
private val EXTRA = mapOf(DroidDeckHudSize.PILL to setOf("Temp", "Power"))

private class HudMetric(val chip: String, val key: String, val label: Int)

// DroidDeckHudSize.supportedChips() names; Wrapper and DX version have nothing to show in DroidDeck.
private val METRICS = listOf(
    HudMetric("FPS", "showFPS", R.string.drawer_hud_fps),
    HudMetric("FPS graph", "showFPSGraph", R.string.hud_metric_graph),
    HudMetric("FPS .1", "fpsDecimal", R.string.hud_metric_decimal),
    HudMetric("0.01% low", "showLow001", R.string.hud_metric_low001),
    HudMetric("Engine", "showEngine", R.string.hud_metric_engine),
    HudMetric("GPU model", "showGpuModel", R.string.hud_metric_gpu_model),
    HudMetric("GPU", "showGPULoad", R.string.hud_metric_gpu),
    HudMetric("GPU temp", "showGpuTemp", R.string.hud_metric_gpu_temp),
    HudMetric("CPU", "showCPUUsage", R.string.hud_metric_cpu),
    HudMetric("Per-core", "showPerCore", R.string.hud_metric_per_core),
    HudMetric("Temp", "showTemp", R.string.hud_metric_temp),
    HudMetric("RAM", "showRAM", R.string.hud_metric_ram),
    HudMetric("VRAM", "showVram", R.string.hud_metric_vram),
    HudMetric("Swap", "showSwap", R.string.hud_metric_swap),
    HudMetric("Battery", "showBattery", R.string.hud_metric_battery),
    HudMetric("Power", "showPower", R.string.hud_metric_power),
    HudMetric("Network", "showNet", R.string.hud_metric_network),
    HudMetric("Resolution", "showResolution", R.string.hud_metric_resolution),
    HudMetric("Proton", "showProton", R.string.hud_metric_proton),
    HudMetric("Session", "showSession", R.string.hud_metric_session),
    HudMetric("Clock", "showClock", R.string.hud_metric_clock),
)

// Switches a size lists but never draws (DroidDeckHudView): the Pill and Minimal always show the FPS, the
// Pill has no graph, Tiles has no graph and no 0.01% low, the Strip always shows the FPS.
private val HIDDEN = mapOf(
    DroidDeckHudSize.PILL to setOf("FPS", "FPS graph"),
    DroidDeckHudSize.MINIMAL to setOf("FPS"),
    DroidDeckHudSize.TILES to setOf("FPS graph", "0.01% low"),
    DroidDeckHudSize.STRIP to setOf("FPS"),
)
