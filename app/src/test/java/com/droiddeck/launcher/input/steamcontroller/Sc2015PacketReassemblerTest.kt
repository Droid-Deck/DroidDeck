package com.droiddeck.launcher.input.steamcontroller

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Sc2015PacketReassemblerTest {
    @Test
    fun singleFramePacketStripsHeaderAndReturnsPayload() {
        val reassembler = Sc2015PacketReassembler()
        val raw = byteArrayOf(
            0xC0.toByte(),
            0x14, 0x02,
            0x80.toByte(), 0x00, 0x00,
            0x10, 0x20
        )
        val payload = reassembler.onNotification(raw)
        assertArrayEquals(
            byteArrayOf(0x14, 0x02, 0x80.toByte(), 0x00, 0x00, 0x10, 0x20),
            payload
        )
    }

    @Test
    fun multiFragmentPacketReassemblesInOrder() {
        val reassembler = Sc2015PacketReassembler()
        val frag0 = byteArrayOf(0x80.toByte(), 0x14, 0x0F, 0x01, 0x02, 0x03)
        val frag1 = byteArrayOf(0x81.toByte(), 0x04, 0x05, 0x06, 0x07)
        val frag2 = byteArrayOf(0xC2.toByte(), 0x08, 0x09)

        assertNull(reassembler.onNotification(frag0))
        assertNull(reassembler.onNotification(frag1))
        val complete = reassembler.onNotification(frag2)

        assertArrayEquals(
            byteArrayOf(0x14, 0x0F, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09),
            complete
        )
    }

    @Test
    fun sequenceMismatchDropsCorruptedFragmentsAndRecoversOnNextStart() {
        val reassembler = Sc2015PacketReassembler()
        assertNull(reassembler.onNotification(byteArrayOf(0x80.toByte(), 0x01, 0x02)))
        assertNull(reassembler.onNotification(byteArrayOf(0xC2.toByte(), 0x03, 0x04)))

        val recovered = reassembler.onNotification(byteArrayOf(0xC0.toByte(), 0xAA.toByte(), 0xBB.toByte()))
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), recovered)
    }

    @Test
    fun newStartFrameDuringIncompleteSequenceResetsAndProcessesCleanly() {
        val reassembler = Sc2015PacketReassembler()
        assertNull(reassembler.onNotification(byteArrayOf(0x80.toByte(), 0x01, 0x02)))

        val single = reassembler.onNotification(byteArrayOf(0xC0.toByte(), 0x10, 0x20))
        assertArrayEquals(byteArrayOf(0x10, 0x20), single)
    }

    @Test
    fun nonDataHeaderIsIgnored() {
        val reassembler = Sc2015PacketReassembler()
        assertNull(reassembler.onNotification(byteArrayOf(0x01, 0x02, 0x03)))
    }
}
