package com.droiddeck.launcher.ui

import android.content.Context
import android.content.res.Resources
import android.system.Os
import android.system.OsConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.CpuCores
import com.droiddeck.launcher.frontend.AddedGameArt
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.frontend.SteamGameArt
import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.hud.DroidDeckHudConfig
import com.droiddeck.launcher.hud.DroidDeckHudHost
import com.droiddeck.launcher.hud.FrameStats
import com.droiddeck.launcher.hud.GameFrameFeed
import com.droiddeck.launcher.hud.HudFrames
import com.droiddeck.launcher.hud.HudMetrics
import com.droiddeck.launcher.hud.KeyValueSet
import com.droiddeck.launcher.hud.MangoReadings
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.GameStatusFiles
import com.droiddeck.launcher.session.GameTelemetry
import com.droiddeck.launcher.session.HwmonComponent
import com.droiddeck.launcher.input.SteamTouchConfig
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.SpeedUnit
import com.droiddeck.launcher.session.StatsDash
import com.droiddeck.launcher.session.StatsHud
import com.droiddeck.launcher.session.StatsStrip
import com.droiddeck.launcher.session.Telemetry
import com.droiddeck.launcher.session.TelemetryOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

private const val DRAW_MS = 66L
private const val TELEMETRY_STALE_MS = 3_000L
private const val METRICS_MS = 500L
private const val THUNDER_MS = 500L
private const val THUNDER_RETRY_MS = 3_000L
private const val ELITE_MS = 1_000L
private const val ELITE_SEARCH_MS = 10_000L
private const val OUTPUT_SCAN_MS = 3_000L
/** Top strip history: two readings a second for 30 s. */
private const val PERF_N = 60
/** The HUD and the Line strip redraw at most this often while the game's frames arrive (about 10 a second). */
private const val FRAME_MS = 100L
private const val MINUTE_NS = 60_000_000_000L
private const val TEMP_PLAIN = 0xFFF2F5F9.toInt()

/** What arrives on one telemetry port; written only by that port's listener. */
private class PortTraffic {
    @Volatile var packets = 0L
    @Volatile var length = 0
    @Volatile var readable = true
}

private data class UnknownTraffic(val port: Int, val packets: Long, val length: Int)

@Composable
fun BottomTelemetryPage(fps: () -> Float?, runningGame: () -> String? = { null }) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var outputs by remember { mutableStateOf<List<TelemetryOutput.Game>>(emptyList()) }
    var menuGames by remember { mutableStateOf<List<TelemetryOutput.MenuGame>>(emptyList()) }
    var running by remember { mutableStateOf<String?>(null) }
    var gameName by remember { mutableStateOf<String?>(null) }
    val steamClient = remember { if (SessionState.mode == SessionService.MODE_STEAM) SessionPrefs.activeSteamClientArch(context) else null }
    var outputFailed by remember { mutableStateOf<String?>(null) }
    var rescan by remember { mutableIntStateOf(0) }
    var auto by remember { mutableStateOf(SessionPrefs.telemetryAuto(context)) }
    val latest = remember { AtomicReference<Pair<Long, Telemetry>?>(null) }
    var race by remember { mutableStateOf<Telemetry?>(null) }
    var fpsNow by remember { mutableStateOf<Float?>(null) }
    var device by remember { mutableStateOf<HudMetrics.Snapshot?>(null) }
    var thunder by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var elite by remember { mutableStateOf<GameStatusFiles.Elite?>(null) }
    var busyPorts by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val traffic = remember { GameTelemetry.PORTS.associateWith { PortTraffic() } }
    var unknown by remember { mutableStateOf<List<UnknownTraffic>>(emptyList()) }
    var strip by remember { mutableStateOf(StatsStrip.from(SessionPrefs.statsStrip(context))) }
    var dash by remember { mutableStateOf(StatsDash.from(SessionPrefs.statsDash(context))) }
    var speedUnit by remember { mutableStateOf(SpeedUnit.from(SessionPrefs.statsSpeedUnit(context))) }
    val shownUnit = speedUnit.resolve(Resources.getSystem().configuration.locales[0].country)
    val fpsHistory = remember { History(PERF_N) }
    val cpuHistory = remember { History(PERF_N) }
    val gpuHistory = remember { History(PERF_N) }
    val wattsHistory = remember { History(PERF_N) }
    val throttleTrace = remember { History(TRACE_N) }
    val brakeTrace = remember { History(TRACE_N) }
    val speedTrace = remember { History(TRACE_N) }
    var perfTick by remember { mutableIntStateOf(0) }
    var traceTick by remember { mutableIntStateOf(0) }
    var clock by remember { mutableStateOf("") }
    // Read here as well as by the top-screen HUD, so FPS shows while that HUD is off.
    val frames = remember { GameFrameFeed(File(LinuxRuntime.rootDir(context), HudFrames.PATH)) }
    DisposableEffect(frames) { onDispose { frames.close() } }
    val currentFps = { if (frames.live) frames.stats.fps else fps() }
    var hud by remember { mutableStateOf(StatsHud.from(SessionPrefs.statsHud(context))) }
    // The Bottom screen HUD master switch (the big HUD and the Line strip; the game's dashboard stays).
    var hudOn by remember { mutableStateOf(SessionPrefs.bottomHud(context)) }
    var hudTick by remember { mutableIntStateOf(0) }
    var fanRpm by remember { mutableStateOf<Long?>(null) }
    var gpuName by remember { mutableStateOf("") }
    var proton by remember { mutableStateOf<String?>(null) }
    var playedSeconds by remember { mutableStateOf<Long?>(null) }
    var cover by remember { mutableStateOf<File?>(null) }
    var coreGroups by remember { mutableStateOf<List<CoreGroup>>(emptyList()) }
    var thresholds by remember { mutableStateOf<Map<HudMetrics.TempSensor, HudMetrics.Thresholds>>(emptyMap()) }
    val tempDisplay = remember { HudMetrics.TempDisplay.from(KeyValueSet(DroidDeckHudConfig.encode(DroidDeckHudConfig.load(context)))) }
    val gameCores = remember { CpuCores.parse(SessionPrefs.gameCpus(context)) }

    LaunchedEffect(Unit) {
        for (port in GameTelemetry.PORTS) launch(Dispatchers.IO) {
            val socket = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    soTimeout = 500
                    bind(InetSocketAddress(GameTelemetry.LISTEN_ADDRESS, port))
                }
            }.getOrNull()
            if (socket == null) {
                withContext(Dispatchers.Main) { busyPorts = busyPorts + port }
                return@launch
            }
            val seen = traffic.getValue(port)
            socket.use {
                val data = ByteArray(2048)
                val packet = DatagramPacket(data, data.size)
                while (isActive) {
                    try {
                        packet.length = data.size
                        it.receive(packet)
                        val t = GameTelemetry.parse(port, data, packet.length)
                        seen.packets++
                        seen.length = packet.length
                        seen.readable = t != null
                        if (t != null) latest.set(System.currentTimeMillis() to t)
                    } catch (_: SocketTimeoutException) {
                    } catch (_: java.io.IOException) {
                        break
                    }
                }
            }
        }
        while (isActive) {
            val reading = latest.get()
            race = reading?.takeIf { System.currentTimeMillis() - it.first < TELEMETRY_STALE_MS }?.second
            race?.let { r ->
                throttleTrace.add(r.throttle ?: 0f)
                brakeTrace.add(r.brake ?: 0f)
                speedTrace.add(r.speedKmh ?: 0f)
                traceTick++
            }
            fpsNow = currentFps()
            unknown = traffic.filter { (_, t) -> t.packets > 0 && !t.readable }.map { (port, t) -> UnknownTraffic(port, t.packets, t.length) }
            delay(DRAW_MS)
        }
    }
    // The frame numbers (FPS, lows, graph) on their own clock, so they do not wait for the telemetry loop.
    LaunchedEffect(frames) {
        while (isActive) {
            if (frames.poll(System.nanoTime())) hudTick++
            delay(FRAME_MS)
        }
    }
    LaunchedEffect(Unit) {
        val metrics = HudMetrics(context).apply { setMangoReadings(MangoReadings(File(context.filesDir, "session"), LinuxRuntime.rootDir(context))) }
        val fanPath = withContext(Dispatchers.IO) { runCatching { HwmonComponent.fanSource() }.getOrNull() }
        gpuName = withContext(Dispatchers.IO) { runCatching { GpuInfo.detect().displayName(context) }.getOrDefault("") }
        coreGroups = withContext(Dispatchers.IO) { coreGroups() }
        thresholds = withContext(Dispatchers.IO) {
            HudMetrics.TempSensor.values().mapNotNull { sensor -> runCatching { sensor to metrics.resolveThresholds(sensor, tempDisplay) }.getOrNull() }.toMap()
        }
        var protonPid = 0
        while (isActive) {
            device = withContext(Dispatchers.IO) { runCatching { metrics.snapshot() }.getOrNull() }
            fanRpm = fanPath?.let { path -> withContext(Dispatchers.IO) { HwmonComponent.readRpm(path) } }
            val pid = if (frames.live && !frames.steamFocused) frames.pid else 0
            if (pid > 0 && pid != protonPid) {
                protonPid = pid
                proton = withContext(Dispatchers.IO) { DroidDeckHudHost.protonName(pid) } ?: proton
            }
            playedSeconds = if (pid > 0 && running != null) withContext(Dispatchers.IO) { processSeconds(pid) } else null
            currentFps()?.takeIf { it > 0f }?.let { fpsHistory.add(it) }
            device?.let { s ->
                s.cpuPercent?.let { cpuHistory.add(it.toFloat()) }
                s.gpuPercent?.let { gpuHistory.add(it.toFloat()) }
                s.battery?.watts?.takeIf { it > 0f }?.let { wattsHistory.add(it) }
            }
            clock = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
            hudOn = SessionPrefs.bottomHud(context)
            // Keeps the GPU load, clock and VRAM readers going while this HUD shows them (top HUD off too).
            if (hudOn) com.droiddeck.launcher.hud.BottomHudPresence.beat()
            perfTick++
            delay(METRICS_MS)
        }
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            val rows = withContext(Dispatchers.IO) { runCatching { GameStatusFiles.warThunder(httpGet(GameStatusFiles.WAR_THUNDER_STATE)) }.getOrNull() }
            thunder = rows.orEmpty()
            delay(if (rows == null) THUNDER_RETRY_MS else THUNDER_MS)
        }
    }
    LaunchedEffect(Unit) {
        var file: File? = null
        var searchedAt = 0L
        while (isActive) {
            val now = System.currentTimeMillis()
            if (file == null && now - searchedAt > ELITE_SEARCH_MS) {
                searchedAt = now
                file = withContext(Dispatchers.IO) {
                    runCatching { GameStatusFiles.eliteStatusFile(LinuxRuntime.rootDir(context), SessionState.steamLibraryMounts) }.getOrNull()
                }
            }
            val f = file
            // Status.json changes while Elite runs; one older than a minute belongs to an earlier session.
            elite = if (f != null && now - f.lastModified() < 60_000L) {
                withContext(Dispatchers.IO) { runCatching { GameStatusFiles.elite(f.readText()) }.getOrNull() }
            } else null
            delay(ELITE_MS)
        }
    }
    LaunchedEffect(Unit) {
        menuGames = withContext(Dispatchers.IO) {
            runCatching { Library.steamGames(context).mapNotNull { TelemetryOutput.MENU_GAMES[it.appId] }.distinct() }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(running) {
        val id = running?.toLongOrNull()
        gameName = if (id == null) null
            else withContext(Dispatchers.IO) { runCatching { SteamTouchConfig.gameName(context, id.toInt()) }.getOrNull() }
        cover = if (id == null) null else withContext(Dispatchers.IO) { runCatching { gameCover(context, id.toInt()) }.getOrNull() }
    }
    LaunchedEffect(rescan) {
        while (isActive) {
            outputs = withContext(Dispatchers.IO) { runCatching { TelemetryOutput.scan(Library.compatdataDirs(context)) }.getOrDefault(emptyList()) }
            running = runningGame()
            delay(OUTPUT_SCAN_MS)
        }
    }

    val title = if (running != null) gameName else steamClient?.let { stringResource(steamClientLabel(it)) }
    val noData = race == null && thunder.isEmpty() && elite == null
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!hudOn) Unit
            else if (noData) hudTick.let {
                perfTick.let {
                    val now = System.nanoTime()
                    val live = frames.live
                    val stats = frames.stats
                    val graph = FloatArray(FrameStats.GRAPH_FRAMES).also { if (live) stats.graph(it) }
                    val hz = (frames.refreshMilliHz + 499) / 1000
                    val data = HudData(
                        fps = fpsNow, avgFps = if (live) stats.averageFps() else null, lows = if (live) stats.lows(now) else FrameStats.Lows.EMPTY,
                        graph = graph, slowPerMinute = if (live) stats.slowFrames(now, MINUTE_NS) else null,
                        engine = if (live && !frames.steamFocused) frames.engine else "",
                        resolution = if (frames.outputWidth > 0 && frames.outputHeight > 0) {
                            "${frames.outputWidth}x${frames.outputHeight}" + (if (hz > 0) " @ ${hz}Hz" else "")
                        } else "",
                        refreshHz = hz, proton = proton, device = device, gpuName = gpuName, fanRpm = fanRpm,
                        title = title, cover = cover, played = playedSeconds?.let(::playedText), clock = clock,
                        fpsHistory = fpsHistory.snapshot(), cpuHistory = cpuHistory.snapshot(), gpuHistory = gpuHistory.snapshot(),
                        coreGroups = coreGroups, gameCores = gameCores, fahrenheit = tempDisplay.fahrenheit,
                        tempColor = { sensor, c ->
                            thresholds[sensor]?.let { Color(HudMetrics.tempColor(c.toFloat(), it, tempDisplay, TEMP_PLAIN)) } ?: Color(TEMP_PLAIN)
                        },
                        redC = { thresholds[it]?.redC },
                    )
                    StatsHudView(hud, data, Modifier.fillMaxWidth().height(viewport)) {
                        hud = hud.next()
                        SessionPrefs.setStatsHud(context, hud.id)
                    }
                }
            } else hudTick.let {
                perfTick.let {
                    val live = frames.live
                    val graph = FloatArray(FrameStats.GRAPH_FRAMES).also { if (live) frames.stats.graph(it) }
                    val data = StripData(
                        fpsNow, device, fpsHistory.snapshot(), cpuHistory.snapshot(), gpuHistory.snapshot(), wattsHistory.snapshot(), clock, title,
                        engine = if (live && !frames.steamFocused) frames.engine else "",
                        lows = if (live) frames.stats.lows(System.nanoTime()) else null, graph = graph,
                        metrics = SessionPrefs.bottomStripMetrics(context), fahrenheit = tempDisplay.fahrenheit,
                        tempColor = { sensor, c ->
                            thresholds[sensor]?.let { Color(HudMetrics.tempColor(c.toFloat(), it, tempDisplay, TEMP_PLAIN)) } ?: Color(TEMP_PLAIN)
                        },
                    )
                    StatsStripView(strip, data) {
                        strip = strip.next()
                        SessionPrefs.setStatsStrip(context, strip.id)
                    }
                }
            }
            race?.let { r ->
                val shown = dash.resolve(r.source)
                traceTick.let {
                    RaceDashView(shown, r, TraceData(throttleTrace.snapshot(), brakeTrace.snapshot(), speedTrace.snapshot()), shownUnit) {
                        dash = StatsDash.tapped(shown)
                        SessionPrefs.setStatsDash(context, dash.id)
                    }
                }
            }
            if (thunder.isNotEmpty()) {
                if (FlightLabels.drawable(thunder)) FlightDash(thunder)
                else TelemetryCard(stringResource(R.string.bottom_telemetry_war_thunder)) { ValueGrid(thunder.take(GameStatusFiles.WAR_THUNDER_ROWS)) }
            }
            elite?.let { e ->
                if (e.pips != null || e.fuelMain != null) ShipDash(e)
                else TelemetryCard(stringResource(R.string.bottom_telemetry_elite)) {
                    ValueGrid(listOfNotNull(
                        e.fuelMain?.let { stringResource(R.string.bottom_telemetry_fuel) to "%.1f t".format(it) },
                        e.cargo?.let { stringResource(R.string.bottom_telemetry_cargo) to "%.0f t".format(it) },
                        e.pips?.takeIf { it.size == 3 }?.let { p ->
                            stringResource(R.string.bottom_telemetry_pips) to p.joinToString(" · ") { "%.1f".format(it / 2f) }
                        },
                        e.legal?.let { stringResource(R.string.bottom_telemetry_legal) to it },
                        e.balance?.let { stringResource(R.string.bottom_telemetry_balance) to "%,d CR".format(it) },
                    ))
                }
            }
            if (race == null && thunder.isEmpty() && elite == null && running == null) {
                Text(stringResource(R.string.bottom_telemetry_help),
                    fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            }
            if (outputs.isNotEmpty() || menuGames.isNotEmpty()) TelemetryCard(stringResource(R.string.bottom_telemetry_send_title)) {
                if (outputs.isNotEmpty()) SwitchRow(stringResource(R.string.bottom_telemetry_send_auto), stringResource(R.string.bottom_telemetry_send_auto_hint), auto, true) { want ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            SessionPrefs.setTelemetryAuto(context, want)
                            if (want) TelemetryOutput.applyAutomatic(context, running)
                            else TelemetryOutput.restoreTargets(outputs, running).forEach { TelemetryOutput.set(it, false) }
                        }
                        auto = want
                        rescan++
                    }
                }
                for (game in outputs) {
                    val locked = TelemetryOutput.isRunning(game.prefixId, running)
                    val hint = if (locked) stringResource(R.string.bottom_telemetry_send_close, game.name) else null
                    SwitchRow(game.name, hint, game.on, !locked) { want ->
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                val key = TelemetryOutput.key(game)
                                val off = SessionPrefs.telemetryOff(context)
                                SessionPrefs.setTelemetryOff(context, if (want) off - key else off + key)
                                TelemetryOutput.set(game, want)
                            }
                            outputFailed = if (ok) null else game.name
                            rescan++
                        }
                    }
                }
                for (game in menuGames) {
                    Text(stringResource(R.string.bottom_telemetry_send_menu, game.name, game.setting, game.port), fontSize = 13.sp, color = colors.onSurfaceVariant)
                }
                if (outputs.isNotEmpty()) Text(stringResource(R.string.bottom_telemetry_send_note), fontSize = 12.sp, color = colors.onSurfaceVariant)
                outputFailed?.let { Text(stringResource(R.string.bottom_telemetry_send_failed, it), fontSize = 12.sp, color = colors.error) }
            }
            for (u in unknown) {
                Text(stringResource(R.string.bottom_telemetry_unknown_format, u.port, u.packets, u.length), fontSize = 13.sp,
                    color = colors.error, modifier = Modifier.padding(horizontal = 4.dp))
            }
            if (busyPorts.isNotEmpty()) {
                Text(stringResource(R.string.bottom_telemetry_port_busy, busyPorts.sorted().joinToString()), fontSize = 13.sp,
                    color = colors.error, modifier = Modifier.padding(horizontal = 4.dp))
            }
            ChoiceRow(stringResource(R.string.stats_speed_units),
                if (speedUnit == SpeedUnit.AUTO) stringResource(R.string.stats_speed_units_auto, shownUnit.label) else speedUnit.label) {
                speedUnit = speedUnit.next()
                SessionPrefs.setStatsSpeedUnit(context, speedUnit.id)
            }
            Text(stringResource(R.string.stats_tap_hint), fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

/** Seconds since [pid] started: its start in /proc/<pid>/stat (clock ticks since boot) against /proc/uptime. */
private fun processSeconds(pid: Int): Long? = runCatching {
    val fields = File("/proc/$pid/stat").readText().substringAfterLast(')').trim().split(' ')
    val startTicks = fields[19].toLong()
    val uptime = File("/proc/uptime").readText().substringBefore(' ').toDouble()
    (uptime - startTicks.toDouble() / Os.sysconf(OsConstants._SC_CLK_TCK)).toLong()
}.getOrNull()?.takeIf { it >= 0 }

private fun playedText(seconds: Long): String =
    if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)

private fun gameCover(context: Context, appId: Int): File? =
    SteamGameArt.chosenFile(context, appId, AddedGameArt.Slot.COVER)
        ?: Library.steamCacheImage(File(SessionPrefs.activeSteamRoot(context), "appcache/librarycache"), appId, listOf("library_capsule.jpg", "library_600x900.jpg"))

/** The CPU's cores in runs that share a top clock, in core order. */
private fun coreGroups(): List<CoreGroup> {
    val out = mutableListOf<CoreGroup>()
    for (core in CpuCores.all) {
        val ghz = CpuCores.maxGhz(core)
        val last = out.lastOrNull()
        if (last != null && last.maxGhz == ghz) out[out.lastIndex] = CoreGroup(last.cores + core, ghz) else out += CoreGroup(listOf(core), ghz)
    }
    return out
}

private fun httpGet(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 300
    c.readTimeout = 500
    return try {
        c.inputStream.bufferedReader().use { it.readText() }
    } finally {
        c.disconnect()
    }
}


/** The Steam client the session runs, shown in the strip's top line while no game runs. */
internal fun steamClientLabel(arch: String): Int = if (arch == "x86_64") R.string.stats_steam_client_x86 else R.string.stats_steam_client_arm

@Composable
private fun ChoiceRow(title: String, value: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 15.sp, color = colors.onBackground, modifier = Modifier.weight(1f))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = LocalPalette.current.primary)
    }
}

@Composable
private fun SwitchRow(title: String, hint: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 15.sp, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (hint != null) Text(hint, fontSize = 12.sp, color = colors.onSurfaceVariant)
        }
        val pal = LocalPalette.current
        Switch(checked, null, enabled = enabled, colors = SwitchDefaults.colors(
            disabledCheckedTrackColor = pal.primary.copy(alpha = 0.45f),
            disabledCheckedThumbColor = colors.background,
            disabledCheckedBorderColor = pal.primary.copy(alpha = 0.45f),
        ))
    }
}


@Composable
private fun TelemetryCard(title: String, content: @Composable () -> Unit) {
    val pal = LocalPalette.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(pal.surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.signal)
        content()
    }
}

@Composable
private fun ValueGrid(rows: List<Pair<String, String>>) {
    val colors = MaterialTheme.colorScheme
    rows.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            pair.forEach { (k, v) ->
                Row(Modifier.weight(1f)) {
                    Text(k, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(v, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1)
                }
            }
            if (pair.size == 1) Box(Modifier.weight(1f))
        }
    }
}

internal fun lapTime(seconds: Float): String {
    val total = kotlin.math.round(seconds * 1000.0).toLong()
    return "%d:%02d.%03d".format(total / 60_000, total / 1000 % 60, total % 1000)
}



