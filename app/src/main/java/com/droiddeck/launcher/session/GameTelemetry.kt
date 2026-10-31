package com.droiddeck.launcher.session

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One reading from a racing game's telemetry. Fractions are 0..1; null means the game does not send it. */
data class Telemetry(
    val source: String,
    val speedKmh: Float? = null,
    val rpm: Float? = null,
    val maxRpm: Float? = null,
    val gear: String? = null,
    val throttle: Float? = null,
    val brake: Float? = null,
    val lap: Int? = null,
    val laps: Int? = null,
    val position: Int? = null,
    val fuel: Float? = null,
    val currentLapS: Float? = null,
    val lastLapS: Float? = null,
    val bestLapS: Float? = null,
    /** G-force as the game sends it (lateral, longitudinal) and steering from -1 (left) to 1 (right). */
    val gLat: Float? = null,
    val gLon: Float? = null,
    val steer: Float? = null,
    /** Front left, front right, rear left, rear right. */
    val tireTempsC: List<Float>? = null,
    val turboBar: Float? = null,
    val engineTempC: Float? = null,
    val oilTempC: Float? = null,
    /** OutGauge dashboard lights that are on, as GameTelemetry.DL_* bits. */
    val lights: Int? = null,
)

/**
 * Racing telemetry games send over UDP to 127.0.0.1. Each format has its own port, the one the game or
 * its common tools use by default:
 * - [CODEMASTERS_PORT]: DiRT Rally 1/2, DiRT 4, GRID ("extradata 3" float array), DiRT Showdown (152-byte packet).
 * - [FORZA_PORT]: Forza Motorsport 7/2023 and Forza Horizon 4/5 "Data Out" (Sled and Dash).
 * - [OUTGAUGE_PORT]: BeamNG.drive and Live for Speed OutGauge.
 */
object GameTelemetry {
    const val CODEMASTERS_PORT = 20777
    const val FORZA_PORT = 5300
    const val OUTGAUGE_PORT = 4444
    val PORTS = listOf(CODEMASTERS_PORT, FORZA_PORT, OUTGAUGE_PORT)

    // Games send to 127.0.0.1; on Android InetAddress.getLoopbackAddress() is ::1, which IPv4 packets never reach.
    val LISTEN_ADDRESS: java.net.InetAddress = java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    fun parse(port: Int, data: ByteArray, length: Int): Telemetry? = runCatching {
        val buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        when (port) {
            CODEMASTERS_PORT -> codemasters(buf, length)
            FORZA_PORT -> forza(buf, length)
            OUTGAUGE_PORT -> outGauge(buf, length)
            else -> null
        }
    }.getOrNull()

    private const val MPS_TO_KMH = 3.6f
    private const val GRAVITY = 9.81f

    // OutGauge DashLights / ShowLights bits (Live for Speed InSim; BeamNG uses the same values).
    const val DL_SHIFT = 1
    const val DL_HANDBRAKE = 4
    const val DL_PITSPEED = 8
    const val DL_TC = 16
    const val DL_ABS = 1024

    // Float indexes of the Codemasters packet; engine rpm values are sent divided by 10. Older games
    // (DiRT Showdown) send only the first 38 floats, so lap count, position, fuel and max rpm are missing there.
    private const val CM_LAP_TIME = 1
    private const val CM_SPEED = 7
    private const val CM_THROTTLE = 29
    private const val CM_STEER = 30
    private const val CM_BRAKE = 31
    private const val CM_GEAR = 33
    private const val CM_G_LAT = 34
    private const val CM_G_LON = 35
    private const val CM_LAP = 36
    private const val CM_RPM = 37
    private const val CM_POSITION = 39
    private const val CM_FUEL = 45
    private const val CM_FUEL_CAPACITY = 46
    private const val CM_LAPS = 60
    private const val CM_LAST_LAP = 62
    private const val CM_MAX_RPM = 63
    private const val CM_MIN_FLOATS = 38

    internal fun codemasters(buf: ByteBuffer, length: Int): Telemetry? {
        if (length < CM_MIN_FLOATS * 4) return null
        val floats = length / 4
        fun f(i: Int) = if (i < floats) buf.getFloat(i * 4) else 0f
        val gear = f(CM_GEAR).toInt()
        val capacity = f(CM_FUEL_CAPACITY)
        return Telemetry(
            source = "Codemasters",
            speedKmh = f(CM_SPEED) * MPS_TO_KMH,
            rpm = f(CM_RPM) * 10f,
            maxRpm = (f(CM_MAX_RPM) * 10f).takeIf { it > 0f },
            // Neutral is 0; DiRT Rally 2.0 reports reverse as 10, older games as -1.
            gear = when {
                gear == 0 -> "N"
                gear < 0 || gear == 10 -> "R"
                else -> gear.toString()
            },
            throttle = f(CM_THROTTLE).coerceIn(0f, 1f),
            brake = f(CM_BRAKE).coerceIn(0f, 1f),
            lap = f(CM_LAP).toInt() + 1,
            laps = f(CM_LAPS).toInt().takeIf { it > 0 },
            position = f(CM_POSITION).toInt().takeIf { it > 0 },
            fuel = if (capacity > 0f) (f(CM_FUEL) / capacity).coerceIn(0f, 1f) else null,
            currentLapS = f(CM_LAP_TIME).takeIf { it > 0f },
            lastLapS = f(CM_LAST_LAP).takeIf { it > 0f },
            gLat = f(CM_G_LAT),
            gLon = f(CM_G_LON),
            steer = f(CM_STEER).coerceIn(-1f, 1f),
        )
    }

    // Forza "Data Out": Sled is 232 bytes; the Dash block follows it (FM7 311, FM 2023 331). Horizon 4/5 send
    // 324 bytes with 12 extra bytes before the Dash block.
    private const val FORZA_SLED = 232
    private const val FORZA_FM7 = 311
    private const val FORZA_HORIZON = 324
    private const val FORZA_FM2023 = 331
    private const val HORIZON_SHIFT = 12

    internal fun forza(buf: ByteBuffer, length: Int): Telemetry? {
        if (length < FORZA_SLED) return null
        val racing = buf.getInt(0) != 0
        val maxRpm = buf.getFloat(8)
        val rpm = buf.getFloat(16)
        val dash = when (length) {
            FORZA_FM7, FORZA_FM2023 -> FORZA_SLED
            FORZA_HORIZON -> FORZA_SLED + HORIZON_SHIFT
            else -> -1
        }
        if (dash < 0) return Telemetry(source = "Forza", rpm = rpm, maxRpm = maxRpm.takeIf { it > 0f })
        if (!racing) return Telemetry(source = "Forza")
        val gear = buf.get(dash + 75).toInt() and 0xFF
        // Tire temperatures are read as Fahrenheit, as community Data Out parsers do; not checked on a device.
        val tiresF = (0 until 4).map { buf.getFloat(dash + 24 + it * 4) }
        return Telemetry(
            source = "Forza",
            speedKmh = buf.getFloat(dash + 12) * MPS_TO_KMH,
            rpm = rpm,
            maxRpm = maxRpm.takeIf { it > 0f },
            gear = if (gear == 0) "R" else gear.toString(),
            throttle = (buf.get(dash + 71).toInt() and 0xFF) / 255f,
            brake = (buf.get(dash + 72).toInt() and 0xFF) / 255f,
            lap = (buf.getShort(dash + 68).toInt() and 0xFFFF) + 1,
            position = (buf.get(dash + 70).toInt() and 0xFF).takeIf { it > 0 },
            fuel = buf.getFloat(dash + 44).coerceIn(0f, 1f),
            currentLapS = buf.getFloat(dash + 60).takeIf { it > 0f },
            lastLapS = buf.getFloat(dash + 56).takeIf { it > 0f },
            bestLapS = buf.getFloat(dash + 52).takeIf { it > 0f },
            gLat = buf.getFloat(20) / GRAVITY,
            gLon = buf.getFloat(28) / GRAVITY,
            steer = (buf.get(dash + 76).toFloat() / 127f).coerceIn(-1f, 1f),
            tireTempsC = tiresF.takeIf { f -> f.any { it > 0f } }?.map { (it - 32f) * 5f / 9f },
        )
    }

    // OutGauge: 92 bytes, or 96 with the optional id at the end.
    private const val OUTGAUGE_SIZE = 92

    internal fun outGauge(buf: ByteBuffer, length: Int): Telemetry? {
        if (length < OUTGAUGE_SIZE) return null
        val gear = buf.get(10).toInt() and 0xFF
        return Telemetry(
            source = "OutGauge",
            speedKmh = buf.getFloat(12) * MPS_TO_KMH,
            rpm = buf.getFloat(16),
            gear = when (gear) {
                0 -> "R"
                1 -> "N"
                else -> (gear - 1).toString()
            },
            throttle = buf.getFloat(48).coerceIn(0f, 1f),
            brake = buf.getFloat(52).coerceIn(0f, 1f),
            fuel = buf.getFloat(28).coerceIn(0f, 1f),
            turboBar = buf.getFloat(20).takeIf { it != 0f },
            engineTempC = buf.getFloat(24).takeIf { it != 0f },
            oilTempC = buf.getFloat(36).takeIf { it != 0f },
            lights = buf.getInt(44).takeIf { buf.getInt(40) != 0 },
        )
    }
}

/** Games that publish their state locally without any setup: War Thunder's web page and Elite Dangerous' Status.json. */
object GameStatusFiles {
    const val WAR_THUNDER_STATE = "http://127.0.0.1:8111/state"
    private const val ELITE_APP = "359320"
    private const val ELITE_STATUS = "pfx/drive_c/users/steamuser/Saved Games/Frontier Developments/Elite Dangerous/Status.json"
    const val WAR_THUNDER_ROWS = 12

    /** War Thunder's own labels (for example "TAS, km/h") with their values, while a match runs. */
    internal fun warThunder(json: String): List<Pair<String, String>> {
        val state = org.json.JSONObject(json)
        if (!state.optBoolean("valid", false)) return emptyList()
        return state.keys().asSequence().filter { it != "valid" }.mapNotNull { key ->
            val value = state.opt(key) as? Number ?: return@mapNotNull null
            key to if (value.toDouble() % 1.0 == 0.0) value.toLong().toString() else "%.1f".format(java.util.Locale.US, value.toDouble())
        }.toList()
    }

    fun eliteStatusFile(runtimeRoot: java.io.File, mounts: Map<String, java.io.File>): java.io.File? {
        val libraries = SteamDownloadMonitor.manifests(runtimeRoot, mounts).mapNotNull { it.parentFile } +
            SteamDownloadMonitor.STEAM_HOMES.map { java.io.File(runtimeRoot, "$it/steamapps") }
        return libraries.distinct().map { java.io.File(it, "compatdata/$ELITE_APP/$ELITE_STATUS") }.firstOrNull { it.isFile }
    }

    data class Elite(val fuelMain: Double?, val fuelReserve: Double?, val cargo: Double?, val pips: List<Int>?, val legal: String?, val balance: Long?)

    /** Null while Elite is at its menus (Status.json then carries only the timestamp and flags). */
    internal fun elite(json: String): Elite? {
        val s = org.json.JSONObject(json)
        val fuel = s.optJSONObject("Fuel")
        val pips = s.optJSONArray("Pips")?.let { a -> (0 until a.length()).map { a.optInt(it) } }
        if (fuel == null && pips == null && !s.has("Cargo") && !s.has("Balance")) return null
        return Elite(
            fuelMain = fuel?.optDouble("FuelMain")?.takeIf { !it.isNaN() },
            fuelReserve = fuel?.optDouble("FuelReservoir")?.takeIf { !it.isNaN() },
            cargo = s.optDouble("Cargo").takeIf { !it.isNaN() },
            pips = pips,
            legal = s.optString("LegalState").takeIf { it.isNotEmpty() },
            balance = if (s.has("Balance")) s.optLong("Balance") else null,
        )
    }
}
