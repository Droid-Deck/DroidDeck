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
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameStatusFilesTest {
    @Test fun warThunderShowsNumbersOnlyDuringAMatch() {
        assertEquals(emptyList<Pair<String, String>>(), GameStatusFiles.warThunder("""{"valid": false}"""))
        val rows = GameStatusFiles.warThunder("""{"valid": true, "H, m": 1520, "TAS, km/h": 512.4, "name": "x"}""")
        assertEquals(listOf("H, m" to "1520", "TAS, km/h" to "512.4"), rows)
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
