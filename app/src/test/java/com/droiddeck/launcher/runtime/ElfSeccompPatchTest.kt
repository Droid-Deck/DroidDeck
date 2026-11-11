package com.droiddeck.launcher.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class ElfSeccompPatchTest {
    private fun elfWith(vararg words: Int): ByteArray {
        val data = ByteArray(0x200)
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0, 0x7f); b.put(1, 'E'.code.toByte()); b.put(2, 'L'.code.toByte()); b.put(3, 'F'.code.toByte())
        b.put(4, 2); b.put(5, 1)
        b.putShort(18, 183)
        b.putLong(32, 64)
        b.putShort(54, 56); b.putShort(56, 1)
        b.putInt(64, 1); b.putInt(68, 5)
        b.putLong(72, 0x100); b.putLong(96, 0x100)
        words.forEachIndexed { i, w -> b.putInt(0x100 + i * 4, w) }
        return data
    }

    @Test
    fun theSvcAfterEachBlockedCallIsAnsweredInPlace() {
        val svc = 0xd4000001.toInt()
        val data = elfWith(0xd2800c68.toInt(), 0xaa0003e1.toInt(), svc, 0xd28024a8.toInt(), svc, 0xd2803668.toInt(), svc)
        assertEquals(3, ElfSeccompPatch.patch(data))
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xd503201f.toInt(), b.getInt(0x108))
        assertEquals(0x928004a0.toInt(), b.getInt(0x110))
        assertEquals(0x928004a0.toInt(), b.getInt(0x118))
        assertEquals(0, ElfSeccompPatch.patch(data))
    }

    @Test
    fun aSecondRunLeavesTheNextCallAlone() {
        val svc = 0xd4000001.toInt()
        val exit = 0xd2800ba8.toInt()
        for (blocked in listOf(0xd2803668.toInt(), 0xd2800c68.toInt())) {
            val data = elfWith(blocked, svc, 0xaa1303e0.toInt(), exit, svc)
            assertEquals(1, ElfSeccompPatch.patch(data))
            assertEquals(0, ElfSeccompPatch.patch(data))
            assertEquals(svc, ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(0x110))
        }
    }

    @Test
    fun otherSystemCallsAreLeftAlone() {
        val svc = 0xd4000001.toInt()
        val data = elfWith(0xd2800ba8.toInt(), svc)
        assertEquals(0, ElfSeccompPatch.patch(data))
    }
}
