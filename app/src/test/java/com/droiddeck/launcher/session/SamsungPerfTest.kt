package com.droiddeck.launcher.session

import com.droiddeck.launcher.session.SamsungPerf.Floor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SamsungPerfTest {
    private val cpu = intArrayOf(300000, 691200, 1190400, 1555200, 2016000)
    private val gpu = intArrayOf(220000, 350000, 490000, 600000, 680000)
    private val bus = intArrayOf(547000, 1017000, 1555000, 2092000, 3196000)

    /** A level table as Samsung's service sends it: 38 little-endian ints. */
    private fun table(maxTimeoutMs: Int, cpu: IntArray = this.cpu, gpu: IntArray = this.gpu, bus: IntArray = this.bus): ByteArray {
        val ints = intArrayOf(0, 0, 0, maxTimeoutMs) + cpu + gpu + bus + intArrayOf(5, 5, 5, maxTimeoutMs) + IntArray(15) { 3_000_000 }
        return ByteBuffer.allocate(ints.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { ints.forEach { putInt(it) } }.array()
    }

    @Test fun `the floors are read from the table, and nothing past it`() {
        val stream = ByteArrayInputStream(table(10000) + byteArrayOf(7))
        val levels = SamsungPerf.readLevels(stream)
        assertArrayEquals(cpu, levels.cpu)
        assertArrayEquals(gpu, levels.gpu)
        assertArrayEquals(bus, levels.bus)
        assertEquals(10000, levels.maxTimeoutMs)
        assertEquals(1, stream.available())
    }

    @Test fun `the middle level of each floor is held, with four cores online`() {
        val floors = SamsungPerf.floors(SamsungPerf.readLevels(ByteArrayInputStream(table(-1))))
        val hold = SamsungPerf.HOLD_MS
        assertEquals(
            listOf(
                Floor(SamsungPerf.CPU_MIN, 1190400, hold),
                Floor(SamsungPerf.CPU_CORES_MIN, 4, hold),
                Floor(SamsungPerf.GPU_MIN, 490000, hold),
                Floor(SamsungPerf.BUS_MIN, 1555000, hold),
            ),
            floors,
        )
    }

    @Test fun `a shorter limit from the phone shortens the hold`() {
        val floors = SamsungPerf.floors(SamsungPerf.readLevels(ByteArrayInputStream(table(3000))))
        assertEquals(setOf(3000), floors.map { it.timeoutMs }.toSet())
    }

    @Test fun `a resource the phone does not offer is left alone`() {
        val floors = SamsungPerf.floors(SamsungPerf.readLevels(ByteArrayInputStream(table(-1, gpu = IntArray(5) { -1 }))))
        assertEquals(listOf(SamsungPerf.CPU_MIN, SamsungPerf.CPU_CORES_MIN, SamsungPerf.BUS_MIN), floors.map { it.type })
    }

    @Test fun `the floors are renewed before Samsung's one-second hold runs out`() {
        assertEquals(500L, SamsungPerf.renewMs(SamsungPerf.HOLD_MS))
        assertEquals(500L, SamsungPerf.renewMs(1000))
        assertEquals(150L, SamsungPerf.renewMs(300))
        assertEquals(100L, SamsungPerf.renewMs(50))
    }

    @Test fun `a failed connection is tried again a few times, then at the next game`() {
        assertEquals(4, SamsungPerf.TRIES)
        assertEquals("trying again in 5 s", SamsungPerf.retryNote(1))
        assertEquals("trying again in 15 s", SamsungPerf.retryNote(2))
        assertEquals("trying again in 30 s", SamsungPerf.retryNote(3))
        assertEquals("no more tries until the next game", SamsungPerf.retryNote(4))
    }

    @Test fun `a failed try names the step it was on and the error`() {
        assertEquals(
            "failed while reading the service's level table (EOFException)",
            SamsungPerf.failure("reading the service's level table", EOFException()),
        )
        assertEquals(
            "failed while looking up Samsung's performance service (SecurityException: not allowed)",
            SamsungPerf.failure("looking up Samsung's performance service", InvocationTargetException(SecurityException("not allowed"))),
        )
    }

    @Test(expected = EOFException::class) fun `a short answer is refused`() {
        SamsungPerf.readLevels(ByteArrayInputStream(table(-1).copyOf(100)))
    }
}
