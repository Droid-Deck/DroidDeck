package com.droiddeck.launcher.input

/**
 * Bluetooth DualSense haptics: report 0x32 carries a control block (0x11) and 32 frames of signed 8-bit stereo
 * at 3 kHz for the two actuators (0x12), sealed with the same 0xA2-seeded CRC-32 as report 0x31. Layout follows
 * egormanga/SAxense, which drives the pad this way from Linux hidraw.
 */
object DualSenseHaptics {
    const val REPORT_ID: Byte = 0x32
    /** Report id plus SAxense's 141-byte body (137 bytes of packets, then the CRC). */
    const val REPORT_SIZE = 142
    const val SAMPLE_BYTES = 64
    const val SAMPLE_RATE = 3000
    const val SOURCE_RATE = 48000
    const val DECIMATION = SOURCE_RATE / SAMPLE_RATE
    /** One report holds 32 stereo frames, so the pad expects one every 64 / (3000 * 2) s. */
    const val REPORT_INTERVAL_NANOS = 1_000_000_000L * SAMPLE_BYTES / (SAMPLE_RATE * 2)

    private const val CONTROL_OFFSET = 2
    private const val CONTROL_LENGTH = 7
    private const val SAMPLES_OFFSET = CONTROL_OFFSET + 2 + CONTROL_LENGTH + 2
    private const val CRC_OFFSET = REPORT_SIZE - 4

    fun buildReport(samples: ByteArray, counter: Int, seq: Int = 0): ByteArray {
        require(samples.size == SAMPLE_BYTES) { "need $SAMPLE_BYTES haptic bytes, got ${samples.size}" }
        val r = ByteArray(REPORT_SIZE)
        r[0] = REPORT_ID
        r[1] = ((seq and 0x0F) shl 4).toByte()
        r[CONTROL_OFFSET] = (0x11 or 0x80).toByte()
        r[CONTROL_OFFSET + 1] = CONTROL_LENGTH.toByte()
        r[CONTROL_OFFSET + 2] = 0xFE.toByte()
        r[CONTROL_OFFSET + 7] = 0xFF.toByte()
        r[CONTROL_OFFSET + 8] = counter.toByte()
        r[SAMPLES_OFFSET - 2] = (0x12 or 0x80).toByte()
        r[SAMPLES_OFFSET - 1] = SAMPLE_BYTES.toByte()
        System.arraycopy(samples, 0, r, SAMPLES_OFFSET, SAMPLE_BYTES)
        val crc = java.util.zip.CRC32()
        crc.update(0xA2)
        crc.update(r, 0, CRC_OFFSET)
        val v = crc.value
        r[CRC_OFFSET] = (v and 0xFF).toByte()
        r[CRC_OFFSET + 1] = ((v shr 8) and 0xFF).toByte()
        r[CRC_OFFSET + 2] = ((v shr 16) and 0xFF).toByte()
        r[CRC_OFFSET + 3] = ((v shr 24) and 0xFF).toByte()
        return r
    }

    /**
     * Turns the 4-channel 48 kHz s16le stream of the pad's audio sink into Bluetooth haptic reports: channels 3-4
     * are averaged over each 16-frame window (a box low-pass before decimating to 3 kHz) and reduced to 8 bits.
     */
    class Encoder(private val emit: (ByteArray) -> Unit) {
        private val samples = ByteArray(SAMPLE_BYTES)
        private var filled = 0
        private var sumLeft = 0
        private var sumRight = 0
        private var frames = 0
        private var counter = 0
        private var silentReports = 0
        private var carry = ByteArray(0)

        fun feed(pcm: ByteArray, length: Int) {
            val data = if (carry.isEmpty()) pcm else carry + pcm.copyOf(length)
            val total = if (carry.isEmpty()) length else data.size
            val whole = total - total % FRAME_BYTES
            var i = 0
            while (i < whole) {
                sumLeft += le16(data, i + 4)
                sumRight += le16(data, i + 6)
                if (++frames == DECIMATION) {
                    samples[filled++] = toS8(sumLeft / DECIMATION)
                    samples[filled++] = toS8(sumRight / DECIMATION)
                    sumLeft = 0; sumRight = 0; frames = 0
                    if (filled == SAMPLE_BYTES) flush()
                }
                i += FRAME_BYTES
            }
            carry = if (whole < total) data.copyOfRange(whole, total) else ByteArray(0)
        }

        private fun flush() {
            filled = 0
            val silent = samples.all { it.toInt() == 0 }
            silentReports = if (silent) silentReports + 1 else 0
            if (silentReports > SILENT_REPORTS_BEFORE_PAUSE) return
            emit(buildReport(samples.copyOf(), counter))
            counter = (counter + 1) and 0xFF
        }

        private fun le16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or (b[at + 1].toInt() shl 8)

        private fun toS8(v: Int): Byte = (v shr 8).coerceIn(-128, 127).toByte()
    }

    private const val FRAME_BYTES = 8
    /** About one second of silence; after that the pad is left alone until the game drives the actuators again. */
    const val SILENT_REPORTS_BEFORE_PAUSE = 94
}
