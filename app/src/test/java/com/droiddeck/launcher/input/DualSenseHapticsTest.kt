package com.droiddeck.launcher.input

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DualSenseHapticsTest {
    private fun crcOf(report: ByteArray): Long {
        val crc = java.util.zip.CRC32()
        crc.update(0xA2)
        crc.update(report, 0, report.size - 4)
        return crc.value
    }

    private fun storedCrc(report: ByteArray): Long =
        ByteBuffer.wrap(report, report.size - 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

    @Test fun aReportHasSAxensesLayout() {
        val samples = ByteArray(64) { (it - 32).toByte() }
        val r = DualSenseHaptics.buildReport(samples, counter = 7)
        assertEquals(142, r.size)
        assertEquals(0x32, r[0].toInt())
        assertEquals(0, r[1].toInt())
        // control packet 0x11, sized, 7 bytes: FE 00 00 00 00 FF counter
        assertArrayEquals(byteArrayOf(0x91.toByte(), 7, 0xFE.toByte(), 0, 0, 0, 0, 0xFF.toByte(), 7), r.copyOfRange(2, 11))
        // haptics packet 0x12, sized, 64 bytes
        assertEquals(0x92, r[11].toInt() and 0xFF)
        assertEquals(64, r[12].toInt())
        assertArrayEquals(samples, r.copyOfRange(13, 77))
        assertTrue(r.copyOfRange(77, 138).all { it.toInt() == 0 })
        assertEquals(crcOf(r), storedCrc(r))
    }

    @Test fun theCrcSeedMatchesSAxensesPrecomputedState() {
        // SAxense starts from ~0xEADA2D49, the CRC-32 state after the 0xA2 byte.
        val crc = java.util.zip.CRC32()
        crc.update(0xA2)
        assertEquals(0xEADA2D49L, crc.value)
    }

    private fun pcm(frames: Int, haptic: (Int) -> Pair<Int, Int>): ByteArray {
        val b = ByteBuffer.allocate(frames * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val (l, r) = haptic(i)
            b.putShort(1000).putShort(-1000).putShort(l.toShort()).putShort(r.toShort())
        }
        return b.array()
    }

    @Test fun hapticChannelsAreAveragedDownTo3kHz8Bit() {
        val reports = mutableListOf<ByteArray>()
        val encoder = DualSenseHaptics.Encoder { reports += it }
        // 512 frames at 48 kHz = 32 frames at 3 kHz = exactly one report; speaker channels are ignored
        encoder.feed(pcm(512) { i -> if (i % 2 == 0) 0x4000 to -0x4000 else 0x2000 to -0x2000 }, 512 * 8)
        assertEquals(1, reports.size)
        val samples = reports[0].copyOfRange(13, 77)
        for (f in 0 until 32) {
            assertEquals(0x30, samples[f * 2].toInt())
            assertEquals(-0x30, samples[f * 2 + 1].toInt())
        }
    }

    @Test fun framesSplitAcrossReadsAreKept() {
        val reports = mutableListOf<ByteArray>()
        val encoder = DualSenseHaptics.Encoder { reports += it }
        val data = pcm(512) { 0x7F00 to 0x7F00 }
        encoder.feed(data.copyOfRange(0, 1001), 1001)
        encoder.feed(data.copyOfRange(1001, data.size), data.size - 1001)
        assertEquals(1, reports.size)
        assertTrue(reports[0].copyOfRange(13, 77).all { it.toInt() == 0x7F })
    }

    @Test fun reportsStopAfterASecondOfSilenceAndResumeWithSignal() {
        val reports = mutableListOf<ByteArray>()
        val encoder = DualSenseHaptics.Encoder { reports += it }
        val silence = pcm(512) { 0 to 0 }
        repeat(200) { encoder.feed(silence, silence.size) }
        assertEquals(DualSenseHaptics.SILENT_REPORTS_BEFORE_PAUSE, reports.size)
        val buzz = pcm(512) { 0x4000 to 0x4000 }
        encoder.feed(buzz, buzz.size)
        assertEquals(DualSenseHaptics.SILENT_REPORTS_BEFORE_PAUSE + 1, reports.size)
        assertEquals("the counter keeps counting sent reports", DualSenseHaptics.SILENT_REPORTS_BEFORE_PAUSE, reports.last()[10].toInt() and 0xFF)
    }

    @Test fun oneReportEveryTenAndTwoThirdMilliseconds() {
        assertEquals(10_666_666L, DualSenseHaptics.REPORT_INTERVAL_NANOS)
        assertEquals(16, DualSenseHaptics.DECIMATION)
    }
}
