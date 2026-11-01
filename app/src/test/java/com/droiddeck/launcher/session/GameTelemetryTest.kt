package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GameTelemetryTest {
    private fun packet(size: Int, fill: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()

    @Test fun codemastersFloatsBecomeADash() {
        val data = packet(264) {
            putFloat(7 * 4, 25f)          // m/s
            putFloat(29 * 4, 0.8f)
            putFloat(31 * 4, 0.1f)
            putFloat(33 * 4, 3f)
            putFloat(36 * 4, 1f)
            putFloat(37 * 4, 650f)        // rpm / 10
            putFloat(63 * 4, 800f)
            putFloat(45 * 4, 30f)
            putFloat(46 * 4, 60f)
        }
        val t = GameTelemetry.parse(GameTelemetry.CODEMASTERS_PORT, data, data.size)!!
        assertEquals(90f, t.speedKmh!!, 0.01f)
        assertEquals("3", t.gear)
        assertEquals(6500f, t.rpm!!, 0.01f)
        assertEquals(8000f, t.maxRpm!!, 0.01f)
        assertEquals(2, t.lap)
        assertEquals(0.5f, t.fuel!!, 0.001f)
    }

    // Values from a DiRT Showdown packet recorded on a Retroid Pocket Duo (extradata 3 still sends 152 bytes there).
    @Test fun codemastersShortPacketFromDirtShowdown() {
        val data = packet(152) {
            putFloat(1 * 4, 80.085f)
            putFloat(7 * 4, 10.739f)
            putFloat(29 * 4, 0.816f)
            putFloat(33 * 4, 1f)
            putFloat(37 * 4, 827.531f)
        }
        val t = GameTelemetry.parse(GameTelemetry.CODEMASTERS_PORT, data, data.size)!!
        assertEquals(38.66f, t.speedKmh!!, 0.01f)
        assertEquals("1", t.gear)
        assertEquals(8275.31f, t.rpm!!, 0.01f)
        assertEquals(0.816f, t.throttle!!, 0.001f)
        assertEquals(1, t.lap)
        assertEquals(80.085f, t.currentLapS!!, 0.001f)
        assertNull(t.maxRpm)
        assertNull(t.laps)
        assertNull(t.position)
        assertNull(t.fuel)
        assertNull(t.lastLapS)
    }

    @Test fun codemastersReverseAndNeutral() {
        fun gear(g: Float) = GameTelemetry.parse(GameTelemetry.CODEMASTERS_PORT, packet(264) { putFloat(33 * 4, g) }, 264)!!.gear
        assertEquals("N", gear(0f))
        assertEquals("R", gear(-1f))
        assertEquals("R", gear(10f))
    }

    @Test fun forzaHorizonDashSitsTwelveBytesLater() {
        val dash = 232 + 12
        val data = packet(324) {
            putInt(0, 1)
            putFloat(8, 8000f)
            putFloat(16, 5000f)
            putFloat(dash + 12, 50f)
            put(dash + 71, 255.toByte())
            put(dash + 75, 4)
            putShort(dash + 68, 2)
            put(dash + 70, 5)
        }
        val t = GameTelemetry.parse(GameTelemetry.FORZA_PORT, data, data.size)!!
        assertEquals(180f, t.speedKmh!!, 0.01f)
        assertEquals("4", t.gear)
        assertEquals(1f, t.throttle!!, 0.001f)
        assertEquals(3, t.lap)
        assertEquals(5, t.position)
    }

    @Test fun forzaMotorsportDashFollowsTheSled() {
        val data = packet(311) {
            putInt(0, 1)
            putFloat(232 + 12, 10f)
            put(232 + 75, 0)
        }
        val t = GameTelemetry.parse(GameTelemetry.FORZA_PORT, data, data.size)!!
        assertEquals(36f, t.speedKmh!!, 0.01f)
        assertEquals("R", t.gear)
    }

    @Test fun forzaInMenusHasNoDash() {
        val t = GameTelemetry.parse(GameTelemetry.FORZA_PORT, packet(324) {}, 324)!!
        assertNull(t.speedKmh)
    }

    @Test fun outGaugeGearsAreShiftedByOne() {
        val data = packet(96) {
            put(10, 3)
            putFloat(12, 20f)
            putFloat(16, 4000f)
        }
        val t = GameTelemetry.parse(GameTelemetry.OUTGAUGE_PORT, data, data.size)!!
        assertEquals("2", t.gear)
        assertEquals(72f, t.speedKmh!!, 0.01f)
        assertEquals(4000f, t.rpm!!, 0.01f)
    }

    @Test fun listensOnIpv4Loopback() {
        assertTrue(GameTelemetry.LISTEN_ADDRESS is java.net.Inet4Address)
        assertEquals("127.0.0.1", GameTelemetry.LISTEN_ADDRESS.hostAddress)
    }

    @Test fun shortPacketsAreIgnored() {
        assertNull(GameTelemetry.parse(GameTelemetry.CODEMASTERS_PORT, ByteArray(148), 148))
        assertNull(GameTelemetry.parse(GameTelemetry.OUTGAUGE_PORT, ByteArray(40), 40))
        assertNull(GameTelemetry.parse(1234, ByteArray(400), 400))
    }

    // DiRT Showdown's 38 floats already carry steering and both G-forces.
    @Test fun codemastersGForceAndSteering() {
        val data = packet(152) {
            putFloat(30 * 4, -0.5f)
            putFloat(33 * 4, 2f)
            putFloat(34 * 4, 1.2f)
            putFloat(35 * 4, -0.8f)
        }
        val t = GameTelemetry.parse(GameTelemetry.CODEMASTERS_PORT, data, data.size)!!
        assertEquals(-0.5f, t.steer!!, 1e-4f)
        assertEquals(1.2f, t.gLat!!, 1e-4f)
        assertEquals(-0.8f, t.gLon!!, 1e-4f)
    }

    @Test fun forzaReadsBestLapSteeringGForceAndTires() {
        val dash = 232
        val data = packet(311) {
            putInt(0, 1)
            putFloat(20, 9.81f)
            putFloat(28, -4.905f)
            putFloat(dash + 24, 212f)
            putFloat(dash + 28, 32f)
            putFloat(dash + 32, 194f)
            putFloat(dash + 36, 176f)
            putFloat(dash + 52, 81.5f)
            put(dash + 75, 3)
            put(dash + 76, (-127).toByte())
        }
        val t = GameTelemetry.parse(GameTelemetry.FORZA_PORT, data, data.size)!!
        assertEquals(1f, t.gLat!!, 1e-3f)
        assertEquals(-0.5f, t.gLon!!, 1e-3f)
        assertEquals(listOf(100f, 0f, 90f, 80f), t.tireTempsC!!.map { Math.round(it).toFloat() })
        assertEquals(81.5f, t.bestLapS!!, 1e-4f)
        assertEquals(-1f, t.steer!!, 1e-4f)
    }

    @Test fun forzaWithoutTireDataLeavesThemOut() {
        val data = packet(311) { putInt(0, 1); put(232 + 75, 2) }
        assertNull(GameTelemetry.parse(GameTelemetry.FORZA_PORT, data, data.size)!!.tireTempsC)
    }

    @Test fun outGaugeReadsTurboTemperaturesAndLights() {
        val data = packet(92) {
            put(10, 3)
            putFloat(20, 0.8f)
            putFloat(24, 92f)
            putFloat(36, 104f)
            putInt(40, GameTelemetry.DL_SHIFT or GameTelemetry.DL_ABS or GameTelemetry.DL_HANDBRAKE)
            putInt(44, GameTelemetry.DL_SHIFT or GameTelemetry.DL_ABS)
        }
        val t = GameTelemetry.parse(GameTelemetry.OUTGAUGE_PORT, data, data.size)!!
        assertEquals(0.8f, t.turboBar!!, 1e-4f)
        assertEquals(92f, t.engineTempC!!, 1e-4f)
        assertEquals(104f, t.oilTempC!!, 1e-4f)
        assertEquals(GameTelemetry.DL_SHIFT or GameTelemetry.DL_ABS, t.lights)
    }

    // BeamNG leaves some OutGauge fields at zero; those stay hidden.
    @Test fun outGaugeZeroFieldsStayHidden() {
        val t = GameTelemetry.parse(GameTelemetry.OUTGAUGE_PORT, packet(92) { put(10, 2) }, 92)!!
        assertNull(t.turboBar)
        assertNull(t.engineTempC)
        assertNull(t.lights)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameStatusFilesTest {
    @Test fun warThunderShowsNumbersOnlyDuringAMatch() {
        assertEquals(emptyList<Pair<String, String>>(), GameStatusFiles.warThunder("""{"valid": false}"""))
        val rows = GameStatusFiles.warThunder("""{"valid": true, "H, m": 1520, "TAS, km/h": 512.4, "name": "x"}""")
        assertEquals(listOf("H, m" to "1520", "TAS, km/h" to "512.4"), rows)
    }

    @Test fun warThunderKeepsEveryNumberForTheFlightLayout() {
        val json = buildString {
            append("{\"valid\": true")
            for (i in 0 until 20) append(", \"k$i\": $i")
            append(", \"IAS, km/h\": 512, \"throttle 1, %\": 110}")
        }
        val rows = GameStatusFiles.warThunder(json)
        assertEquals(22, rows.size)
        assertTrue(com.droiddeck.launcher.ui.FlightLabels.drawable(rows))
        assertEquals("110", rows.toMap()["throttle 1, %"])
    }

    @Test fun eliteAtTheMenusShowsNothing() {
        assertNull(GameStatusFiles.elite("""{"timestamp": "2026-10-31T02:00:00Z", "event": "Status", "Flags": 0}"""))
    }

    @Test fun eliteInFlight() {
        val e = GameStatusFiles.elite("""{"Flags": 16777240, "Pips": [4, 8, 0], "Fuel": {"FuelMain": 16.0, "FuelReservoir": 0.43},
            "Cargo": 2.0, "LegalState": "Clean", "Balance": 1234567}""")!!
        assertEquals(16.0, e.fuelMain!!, 0.001)
        assertEquals(listOf(4, 8, 0), e.pips)
        assertEquals("Clean", e.legal)
        assertEquals(1234567L, e.balance)
    }
}
