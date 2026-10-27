package com.droiddeck.launcher.input

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

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

    /**
     * Converts 4-channel s16le PCM haptic frames (channels 3-4 at byte offsets +4/+6) into
     * DualSense Left/Right motor rumble intensities when direct 4-channel USB audio or
     * Bluetooth 0x32 haptics is unavailable.
     */
    class RumbleConverter {
        var leftMotor: Int = 0
            private set
        var rightMotor: Int = 0
            private set
        private var lpL: Float = 0f
        private var lpR: Float = 0f

        fun reset() {
            leftMotor = 0
            rightMotor = 0
            lpL = 0f
            lpR = 0f
        }

        fun processFrame(pcm: ByteArray, length: Int, channels: Int = 4, gain: Float = 1f) {
            val stride = channels * 2
            val validLen = minOf(length, pcm.size)
            if (channels < 4 || validLen < stride) {
                leftMotor = 0
                rightMotor = 0
                return
            }
            val frameCount = validLen / stride
            var sumLpLSq = 0f
            var maxLpL = 0f
            var sumHpRSq = 0f
            var maxHpR = 0f
            var sumHpLSq = 0f
            var maxHpL = 0f
            var sumLpRSq = 0f
            var maxLpR = 0f

            for (i in 0 until frameCount) {
                val base = i * stride
                val rawL = ((pcm[base + 4].toInt() and 0xFF) or (pcm[base + 5].toInt() shl 8)).toShort() / 32768f
                val rawR = ((pcm[base + 6].toInt() and 0xFF) or (pcm[base + 7].toInt() shl 8)).toShort() / 32768f

                lpL += LP_ALPHA * (rawL - lpL)
                lpR += LP_ALPHA * (rawR - lpR)
                val curLpL = lpL
                val curLpR = lpR
                val curHpL = rawL - curLpL
                val curHpR = rawR - curLpR

                sumLpLSq += curLpL * curLpL
                val absLpL = abs(curLpL)
                if (absLpL > maxLpL) maxLpL = absLpL

                sumHpLSq += curHpL * curHpL
                val absHpL = abs(curHpL)
                if (absHpL > maxHpL) maxHpL = absHpL

                sumHpRSq += curHpR * curHpR
                val absHpR = abs(curHpR)
                if (absHpR > maxHpR) maxHpR = absHpR

                sumLpRSq += curLpR * curLpR
                val absLpR = abs(curLpR)
                if (absLpR > maxLpR) maxLpR = absLpR
            }

            val invN = 1f / frameCount.toFloat()
            val rmsLpL = sqrt(sumLpLSq * invN)
            val rmsHpL = sqrt(sumHpLSq * invN)
            val rmsHpR = sqrt(sumHpRSq * invN)
            val rmsLpR = sqrt(sumLpRSq * invN)

            val totalL = rmsLpL + rmsHpL
            val totalR = rmsLpR + rmsHpR
            val total = totalL + totalR
            val rightRatio = if (total > 0.001f) totalR / total else 0.5f

            var rightEnv = (rmsHpR * 0.25f + maxHpR * 0.75f) * PRE_GAIN +
                    (rmsLpR * 0.65f + maxLpR * 0.35f) * PRE_GAIN * 0.30f
            var leftEnv = (rmsLpL * 0.65f + maxLpL * 0.35f) * PRE_GAIN +
                    (rmsHpL * 0.40f + maxHpL * 0.60f) * PRE_GAIN * 0.20f

            val balanceScale = if (rightRatio > 0.5f) {
                1f - (rightRatio - 0.5f) * 2f * 0.70f
            } else {
                1f
            }
            leftEnv *= balanceScale

            if (leftEnv < ENERGY_THRESHOLD) leftEnv = 0f
            if (rightEnv < ENERGY_THRESHOLD) rightEnv = 0f

            leftEnv = leftEnv.coerceIn(0f, 1f).pow(0.45f)
            rightEnv = rightEnv.coerceIn(0f, 1f).pow(0.35f)

            val rawLeft = (leftEnv * 128f * gain).toInt()
            val rawRight = (rightEnv * 255f * gain).toInt()

            leftMotor = if (rawLeft > 0) max(rawLeft, MOTOR_FLOOR).coerceIn(0, 128) else 0
            rightMotor = if (rawRight > 0) max(rawRight, MOTOR_FLOOR).coerceIn(0, 255) else 0
        }

        companion object {
            const val ENERGY_THRESHOLD = 0.005f
            const val PRE_GAIN = 2.5f
            const val MOTOR_FLOOR = 15
            const val LP_ALPHA = 0.0196f
        }
    }

    private const val FRAME_BYTES = 8
    /** About one second of silence; after that the pad is left alone until the game drives the actuators again. */
    const val SILENT_REPORTS_BEFORE_PAUSE = 94
}
