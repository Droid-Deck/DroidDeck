package com.droiddeck.launcher.input.steamcontroller

import java.io.ByteArrayOutputStream

class Sc2015PacketReassembler {
    private val buffer = ByteArrayOutputStream(64)
    private var expectedSequence = 0

    fun reset() {
        buffer.reset()
        expectedSequence = 0
    }

    fun onNotification(packet: ByteArray): ByteArray? {
        if (packet.isEmpty()) return null
        val header = packet[0].toInt() and 0xFF
        if (header and SEGMENT_DATA_FLAG == 0) {
            SteamControllerDebug.log { "2015 BLE ignored non-data segment header=0x%02x raw=[%s]".format(header, SteamControllerDebug.hex(packet)) }
            return null
        }
        val seq = header and SEGMENT_SEQ_MASK
        val isLast = header and SEGMENT_LAST_FLAG != 0

        if (seq != expectedSequence) {
            SteamControllerDebug.log { "2015 BLE fragment seq mismatch: got=$seq expected=$expectedSequence header=0x%02x".format(header) }
            reset()
            if (seq != 0) return null
        }

        if (seq == 0 && isLast) {
            return packet.copyOfRange(1, packet.size)
        }

        if (packet.size > 1) {
            buffer.write(packet, 1, packet.size - 1)
        }

        if (buffer.size() > MAX_REASSEMBLED_BYTES) {
            SteamControllerDebug.log { "2015 BLE reassembly overflow (${buffer.size()} bytes), resetting" }
            reset()
            return null
        }

        return if (isLast) {
            val complete = buffer.toByteArray()
            SteamControllerDebug.log { "2015 BLE reassembled ${seq + 1} fragments (${complete.size} bytes): [${SteamControllerDebug.hex(complete)}]" }
            reset()
            complete
        } else {
            expectedSequence = (seq + 1) and SEGMENT_SEQ_MASK
            null
        }
    }

    companion object {
        // BLE MTU on the 2015 controller is fixed at 23 bytes; bit 7 marks valid data and bit 6 marks the final segment.
        const val SEGMENT_DATA_FLAG = 0x80
        const val SEGMENT_LAST_FLAG = 0x40
        const val SEGMENT_SEQ_MASK = 0x0F
        private const val MAX_REASSEMBLED_BYTES = 128
    }
}
