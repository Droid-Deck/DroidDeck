package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.droiddeck.launcher.hud.DroidDeckHudConfig
import com.droiddeck.launcher.hud.FrameStats
import com.droiddeck.launcher.hud.GameFrameFeed
import com.droiddeck.launcher.hud.HudFrames
import com.droiddeck.launcher.hud.HudMetrics
import com.droiddeck.launcher.hud.KeyValueSet
import com.droiddeck.launcher.hud.MangoReadings
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.StripMetric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

private const val STRIP_FRAME_MS = 100L
private const val STRIP_METRICS_MS = 500L
private const val STRIP_TEMP_PLAIN = 0xFFF2F5F9.toInt()

/**
 * The one-line strip under the bottom-screen tabs during a session, on every page but In-game stats (that page has
 * its own HUD and strip). It reads the game's frames and the device itself, so it works with the top-screen HUD
 * off; the Bottom screen HUD settings (master switch, strip on every tab, numbers) are read again every half second.
 */
@Composable
internal fun SessionBottomStrip(pageId: String?, fps: () -> Float?) {
    val context = LocalContext.current
    val frames = remember { GameFrameFeed(File(LinuxRuntime.rootDir(context), HudFrames.PATH)) }
    DisposableEffect(frames) { onDispose { frames.close() } }
    val page by rememberUpdatedState(pageId)
    var frameTick by remember { mutableIntStateOf(0) }
    var device by remember { mutableStateOf<HudMetrics.Snapshot?>(null) }
    var clock by remember { mutableStateOf("") }
    var onTabs by remember { mutableStateOf(SessionPrefs.bottomHud(context) && SessionPrefs.bottomStripOnTabs(context)) }
    var metrics by remember { mutableStateOf(SessionPrefs.bottomStripMetrics(context)) }
    var thresholds by remember { mutableStateOf<Map<HudMetrics.TempSensor, HudMetrics.Thresholds>>(emptyMap()) }
    val tempDisplay = remember { HudMetrics.TempDisplay.from(KeyValueSet(DroidDeckHudConfig.encode(DroidDeckHudConfig.load(context)))) }
    LaunchedEffect(frames) {
        while (isActive) {
            if (frames.poll(System.nanoTime())) frameTick++
            delay(STRIP_FRAME_MS)
        }
    }
    LaunchedEffect(Unit) {
        val reader = HudMetrics(context).apply { setMangoReadings(MangoReadings(File(context.filesDir, "session"), LinuxRuntime.rootDir(context))) }
        thresholds = withContext(Dispatchers.IO) {
            HudMetrics.TempSensor.values().mapNotNull { sensor -> runCatching { sensor to reader.resolveThresholds(sensor, tempDisplay) }.getOrNull() }.toMap()
        }
        while (isActive) {
            onTabs = SessionPrefs.bottomHud(context) && SessionPrefs.bottomStripOnTabs(context)
            metrics = SessionPrefs.bottomStripMetrics(context)
            // Hidden (switched off, or on In-game stats): no device reads.
            if (onTabs && page != BOTTOM_PAGE_TELEMETRY) {
                if (StripMetric.GPU in metrics || StripMetric.VRAM in metrics) com.droiddeck.launcher.hud.BottomHudPresence.beat()
                device = withContext(Dispatchers.IO) { runCatching { reader.snapshot() }.getOrNull() }
                clock = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
            }
            delay(STRIP_METRICS_MS)
        }
    }
    if (!onTabs || pageId == BOTTOM_PAGE_TELEMETRY) return
    frameTick.let {
        val live = frames.live
        val stats = frames.stats
        val graph = FloatArray(FrameStats.GRAPH_FRAMES).also { if (live) stats.graph(it) }
        LineStrip(StripData(
            fps = if (live) stats.fps else fps(), device = device,
            fpsHistory = emptyList(), cpuHistory = emptyList(), gpuHistory = emptyList(), wattsHistory = emptyList(), clock = clock,
            engine = if (live && !frames.steamFocused) frames.engine else "",
            lows = if (live) stats.lows(System.nanoTime()) else null, graph = graph, metrics = metrics,
            fahrenheit = tempDisplay.fahrenheit,
            tempColor = { sensor, c ->
                thresholds[sensor]?.let { Color(HudMetrics.tempColor(c.toFloat(), it, tempDisplay, STRIP_TEMP_PLAIN)) } ?: Color(STRIP_TEMP_PLAIN)
            },
        ))
    }
}
