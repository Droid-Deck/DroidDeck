package com.droiddeck.launcher.input

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
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

    /**
     * Synthesizes 3 kHz signed 8-bit stereo DualSense haptic waveforms (32 stereo frames = 64 bytes)
     * from standard Left/Right motor rumble intensities, using dual phase-continuous oscillators
     * tuned to the heavy (30..45 Hz) and crisp (80..100 Hz) voice-coil actuator resonances.
     */
    class RumbleToHapticsSynthesizer(sampleRate: Int = SAMPLE_RATE) {
        private val dt = 1f / sampleRate.toFloat()
        private val alphaAttack = 1f - exp(-dt / TAU_ATTACK)
        private val alphaRelease = 1f - exp(-dt / TAU_RELEASE)
        private val alphaFreq = 1f - exp(-dt / TAU_FREQ)

        private var curLeftAmp = 0f
        private var curRightAmp = 0f
        private var curLeftFreq = BASE_LEFT_FREQ
        private var curRightFreq = BASE_RIGHT_FREQ
        private var leftPhase = 0f
        private var rightPhase = 0f
        private var counter = 0

        fun reset() {
            curLeftAmp = 0f
            curRightAmp = 0f
            curLeftFreq = BASE_LEFT_FREQ
            curRightFreq = BASE_RIGHT_FREQ
            leftPhase = 0f
            rightPhase = 0f
            counter = 0
        }

        fun nextSamples(leftMotor: Int, rightMotor: Int, gain: Float = 1f): ByteArray {
            val (leftTargetAmp, rightTargetAmp, leftTargetFreq, rightTargetFreq) =
                calculateTargets(leftMotor, rightMotor, gain)
            val out = ByteArray(SAMPLE_BYTES)
            val frames = SAMPLE_BYTES / 2
            for (i in 0 until frames) {
                val aL = if (leftTargetAmp > curLeftAmp) alphaAttack else alphaRelease
                val aR = if (rightTargetAmp > curRightAmp) alphaAttack else alphaRelease
                curLeftAmp += aL * (leftTargetAmp - curLeftAmp)
                curRightAmp += aR * (rightTargetAmp - curRightAmp)
                curLeftFreq += alphaFreq * (leftTargetFreq - curLeftFreq)
                curRightFreq += alphaFreq * (rightTargetFreq - curRightFreq)

                if (curLeftAmp < AMP_SILENCE_FLOOR && leftTargetAmp == 0f) {
                    curLeftAmp = 0f
                    leftPhase = 0f
                } else {
                    leftPhase = (leftPhase + TWO_PI * curLeftFreq * dt) % TWO_PI
                }

                if (curRightAmp < AMP_SILENCE_FLOOR && rightTargetAmp == 0f) {
                    curRightAmp = 0f
                    rightPhase = 0f
                } else {
                    rightPhase = (rightPhase + TWO_PI * curRightFreq * dt) % TWO_PI
                }

                val sampleL = sin(leftPhase) * curLeftAmp
                val sampleR = sin(rightPhase) * curRightAmp
                out[i * 2] = floatToInt8(sampleL)
                out[i * 2 + 1] = floatToInt8(sampleR)
            }
            return out
        }

        fun nextReport(leftMotor: Int, rightMotor: Int, seq: Int = 0, gain: Float = 1f): ByteArray {
            val samples = nextSamples(leftMotor, rightMotor, gain)
            val report = buildReport(samples, counter, seq)
            counter = (counter + 1) and 0xFF
            return report
        }

        companion object {
            const val BASE_LEFT_FREQ = 30f
            const val SPAN_LEFT_FREQ = 15f
            const val BASE_RIGHT_FREQ = 80f
            const val SPAN_RIGHT_FREQ = 20f
            const val LEFT_MAX_AMP = 0.82f
            const val RIGHT_MAX_AMP = 0.35f
            const val LEFT_GAMMA = 0.70f
            const val RIGHT_GAMMA = 0.75f
            const val TAU_ATTACK = 0.008f
            const val TAU_RELEASE = 0.055f
            const val TAU_FREQ = 0.030f
            const val AMP_SILENCE_FLOOR = 0.002f
            const val SOFT_LIMIT_CEILING = 0.98f
            private const val TWO_PI = (2.0 * PI).toFloat()

            data class Targets(
                val leftAmp: Float,
                val rightAmp: Float,
                val leftFreq: Float,
                val rightFreq: Float
            )

            fun calculateTargets(leftMotor: Int, rightMotor: Int, gain: Float = 1f): Targets {
                val leftNorm = (leftMotor.coerceIn(0, 255) / 255f)
                val rightNorm = (rightMotor.coerceIn(0, 255) / 255f)
                val g = gain.coerceIn(0f, 2f)
                val leftAmp = if (leftNorm > 0f) {
                    (LEFT_MAX_AMP * leftNorm.pow(LEFT_GAMMA) * g).coerceIn(0f, SOFT_LIMIT_CEILING)
                } else 0f
                val rightAmp = if (rightNorm > 0f) {
                    (RIGHT_MAX_AMP * rightNorm.pow(RIGHT_GAMMA) * g).coerceIn(0f, SOFT_LIMIT_CEILING)
                } else 0f
                val leftFreq = BASE_LEFT_FREQ + leftNorm * SPAN_LEFT_FREQ
                val rightFreq = BASE_RIGHT_FREQ + rightNorm * SPAN_RIGHT_FREQ
                return Targets(leftAmp, rightAmp, leftFreq, rightFreq)
            }

            fun floatToInt8(sample: Float): Byte {
                val clamped = sample.coerceIn(-1f, 1f)
                return (clamped * 127f).roundToInt().coerceIn(-128, 127).toByte()
            }
        }
    }

    private const val FRAME_BYTES = 8
    /** About one second of silence; after that the pad is left alone until the game drives the actuators again. */
    const val SILENT_REPORTS_BEFORE_PAUSE = 94
}
