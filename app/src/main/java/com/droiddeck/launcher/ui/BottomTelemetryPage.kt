package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.hud.HudMetrics
import com.droiddeck.launcher.hud.MangoReadings
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.GameStatusFiles
import com.droiddeck.launcher.session.GameTelemetry
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.Telemetry
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
private const val METRICS_MS = 1_000L
private const val THUNDER_MS = 500L
private const val THUNDER_RETRY_MS = 3_000L
private const val ELITE_MS = 1_000L
private const val ELITE_SEARCH_MS = 10_000L

/** What arrives on one telemetry port; written only by that port's listener. */
private class PortTraffic {
    @Volatile var packets = 0L
    @Volatile var length = 0
    @Volatile var readable = true
}

private data class UnknownTraffic(val port: Int, val packets: Long, val length: Int)

@Composable
fun BottomTelemetryPage(fps: () -> Float?) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val latest = remember { AtomicReference<Pair<Long, Telemetry>?>(null) }
    var race by remember { mutableStateOf<Telemetry?>(null) }
    var fpsNow by remember { mutableStateOf<Float?>(null) }
    var device by remember { mutableStateOf<HudMetrics.Snapshot?>(null) }
    var thunder by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var elite by remember { mutableStateOf<GameStatusFiles.Elite?>(null) }
    var busyPorts by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val traffic = remember { GameTelemetry.PORTS.associateWith { PortTraffic() } }
    var unknown by remember { mutableStateOf<List<UnknownTraffic>>(emptyList()) }

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
            fpsNow = fps()
            unknown = traffic.filter { (_, t) -> t.packets > 0 && !t.readable }.map { (port, t) -> UnknownTraffic(port, t.packets, t.length) }
            delay(DRAW_MS)
        }
    }
    LaunchedEffect(Unit) {
        val metrics = HudMetrics(context).apply { setMangoReadings(MangoReadings(File(context.filesDir, "session"), LinuxRuntime.rootDir(context))) }
        while (isActive) {
            device = withContext(Dispatchers.IO) { runCatching { metrics.snapshot() }.getOrNull() }
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DeviceRow(fpsNow, device)
        race?.let { RaceDash(it) }
        if (thunder.isNotEmpty()) TelemetryCard(stringResource(R.string.bottom_telemetry_war_thunder)) { ValueGrid(thunder) }
        elite?.let { e ->
            TelemetryCard(stringResource(R.string.bottom_telemetry_elite)) {
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
        if (race == null && thunder.isEmpty() && elite == null) {
            Text(stringResource(R.string.bottom_telemetry_help, GameTelemetry.CODEMASTERS_PORT, GameTelemetry.FORZA_PORT, GameTelemetry.OUTGAUGE_PORT),
                fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        }
        for (u in unknown) {
            Text(stringResource(R.string.bottom_telemetry_unknown_format, u.port, u.packets, u.length), fontSize = 13.sp,
                color = colors.error, modifier = Modifier.padding(horizontal = 4.dp))
        }
        if (busyPorts.isNotEmpty()) {
            Text(stringResource(R.string.bottom_telemetry_port_busy, busyPorts.sorted().joinToString()), fontSize = 13.sp,
                color = colors.error, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
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

@Composable
private fun DeviceRow(fps: Float?, s: HudMetrics.Snapshot?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TelemetryTile(stringResource(R.string.bottom_telemetry_fps), fps?.takeIf { it > 0f }?.let { "%.0f".format(it) } ?: "–", Modifier.weight(1f))
        TelemetryTile(stringResource(R.string.bottom_telemetry_cpu), listOfNotNull(s?.cpuPercent?.let { "$it%" }, s?.cpuTempC?.let { "$it°" })
            .joinToString(" ").ifEmpty { "–" }, Modifier.weight(1f))
        TelemetryTile(stringResource(R.string.bottom_telemetry_gpu), listOfNotNull(s?.gpuPercent?.let { "$it%" }, s?.gpuTempC?.let { "$it°" })
            .joinToString(" ").ifEmpty { "–" }, Modifier.weight(1f))
        TelemetryTile(stringResource(R.string.bottom_telemetry_battery), listOfNotNull(s?.battery?.percent?.let { "$it%" },
            s?.battery?.watts?.takeIf { it > 0f }?.let { "%.1fW".format(it) }).joinToString(" ").ifEmpty { "–" }, Modifier.weight(1f))
    }
}

@Composable
private fun TelemetryTile(label: String, value: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(LocalPalette.current.surface).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(label, fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1)
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

@Composable
private fun RaceDash(t: Telemetry) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    TelemetryCard(t.source) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(t.gear ?: "–", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = pal.signal)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t.speedKmh?.let { "%.0f km/h".format(it) } ?: "–", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
                val rpm = t.rpm
                if (rpm != null) {
                    val max = t.maxRpm
                    Text(if (max != null) "%.0f / %.0f rpm".format(rpm, max) else "%.0f rpm".format(rpm), fontSize = 14.sp, color = colors.onSurfaceVariant)
                    if (max != null) Bar((rpm / max).coerceIn(0f, 1f), if (rpm / max > 0.92f) colors.error else pal.signal)
                }
            }
            Row(Modifier.height(72.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                t.throttle?.let { Pedal(it, pal.good) }
                t.brake?.let { Pedal(it, colors.error) }
            }
        }
        ValueGrid(listOfNotNull(
            t.lap?.let { lap -> stringResource(R.string.bottom_telemetry_lap) to (t.laps?.let { "$lap / $it" } ?: "$lap") },
            t.position?.let { stringResource(R.string.bottom_telemetry_position) to "P$it" },
            t.currentLapS?.let { stringResource(R.string.bottom_telemetry_this_lap) to lapTime(it) },
            t.lastLapS?.let { stringResource(R.string.bottom_telemetry_last_lap) to lapTime(it) },
            t.fuel?.let { stringResource(R.string.bottom_telemetry_fuel) to "%.0f%%".format(it * 100) },
        ))
    }
}

@Composable
private fun Bar(fraction: Float, color: Color) {
    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(LocalPalette.current.line)) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color))
    }
}

@Composable
private fun Pedal(fraction: Float, color: Color) {
    Box(Modifier.width(14.dp).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(LocalPalette.current.line),
        contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(fraction.coerceIn(0f, 1f)).background(color))
    }
}
