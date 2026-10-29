package com.droiddeck.launcher.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.input.DualSenseHaptics
import com.droiddeck.launcher.input.DualSenseManager
import java.io.File
import java.io.InputStream

/**
 * Drains the 4-channel "Wireless Controller" PulseAudio pipe sink and plays it on a USB-connected DualSense:
 * channels 1-2 drive the speaker or headset jack, channels 3-4 the haptic actuators. Without a wired pad the
 * haptic channels go to a Bluetooth pad as 0x32 reports and the speaker channels are dropped, so the sink
 * never stalls.
 */
class DualSenseAudioPump(private val fifo: File) : SessionPart() {
    @Volatile private var running = false
    @Volatile private var input: InputStream? = null
    @Volatile private var fallbackActive = false
    private var thread: Thread? = null
    private var loggedBluetooth = false
    private var btReportAccepted = true
    private var nextBtEmitNs = 0L
    private val rumbleFallback = DualSenseHaptics.RumbleConverter()
    private val bluetooth = DualSenseHaptics.Encoder { report ->
        val nowNs = System.nanoTime()
        if (nextBtEmitNs > nowNs) {
            val waitNs = nextBtEmitNs - nowNs
            if (waitNs <= DualSenseHaptics.REPORT_INTERVAL_NANOS * 4) {
                java.util.concurrent.TimeUnit.NANOSECONDS.sleep(waitNs)
            }
        }
        nextBtEmitNs = maxOf(System.nanoTime(), nextBtEmitNs) + DualSenseHaptics.REPORT_INTERVAL_NANOS
        val sent = DualSenseManager.activeManager?.sendBluetoothReport(report) == true
        btReportAccepted = sent
        if (sent && !loggedBluetooth) {
            loggedBluetooth = true
            Log.i(TAG, "dualsense audio: no wired pad, sending haptics as Bluetooth reports (speaker stays silent)")
        }
    }

    override fun start() {
        stop()
        running = true
        thread = Thread({ pump() }, "dualsense-audio").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        runCatching { input?.close() }
        input = null
        thread?.interrupt()
        thread = null
        clearFallbackRumble()
    }

    private fun clearFallbackRumble() {
        rumbleFallback.reset()
        if (fallbackActive) {
            fallbackActive = false
            DualSenseManager.activeManager?.sendHapticRumbleFallback(0, 0)
        }
    }

    private fun pump() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {}
        val audio = app()?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val chunk = ByteArray(CHUNK_BYTES)
        var carry = 0
        var track: AudioTrack? = null
        var nextLookup = 0L
        var loggedMissing = false
        try {
            while (running && !fifo.exists()) Thread.sleep(100)
            if (!running) return
            val descriptor = ParcelFileDescriptor.open(fifo, ParcelFileDescriptor.MODE_READ_WRITE)
            val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            input = stream
            while (running) {
                val n = stream.read(chunk, carry, chunk.size - carry)
                if (n <= 0) break
                val now = SystemClock.uptimeMillis()
                if (audio != null && now >= nextLookup) {
                    nextLookup = now + LOOKUP_INTERVAL_MS
                    val device = findPad(audio)
                    if (device == null && track != null) {
                        Log.i(TAG, "dualsense audio: wired pad disconnected; releasing audio track")
                        runCatching { track.stop() }
                        track.release()
                        track = null
                    }
                    if (track == null) {
                        if (device != null) {
                            track = openTrack(device)
                            Log.i(TAG, "dualsense audio: playing on ${device.productName} (id ${device.id})" +
                                if (track == null) " failed: no 4-channel track" else "")
                            loggedMissing = false
                        } else if (!loggedMissing) {
                            Log.i(TAG, "dualsense audio: no wired DualSense audio device; haptics go to a Bluetooth pad if there is one")
                            loggedMissing = true
                        }
                    }
                }
                carry = alignFrames(chunk, carry, n) { buf, whole ->
                    val t = track
                    if (t == null) {
                        bluetooth.feed(buf, whole)
                        if (!btReportAccepted) {
                            rumbleFallback.processFrame(buf, whole, CHANNELS)
                            val left = rumbleFallback.leftMotor
                            val right = rumbleFallback.rightMotor
                            if (left > 0 || right > 0 || fallbackActive) {
                                fallbackActive = left > 0 || right > 0
                                DualSenseManager.activeManager?.sendHapticRumbleFallback(left, right)
                            }
                        } else {
                            clearFallbackRumble()
                        }
                    } else {
                        clearFallbackRumble()
                        if (t.write(buf, 0, whole) < 0) {
                            Log.w(TAG, "dualsense audio: the pad's audio track failed; looking for it again")
                            t.release()
                            track = null
                        }
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (running) Log.w(TAG, "dualsense audio pump stopped", e)
        } finally {
            clearFallbackRumble()
            track?.let { runCatching { it.stop() }; it.release() }
        }
    }

    private fun findPad(audio: AudioManager): AudioDeviceInfo? =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            isDualSenseOutput(it.type, it.productName?.toString())
        }

    private fun openTrack(device: AudioDeviceInfo): AudioTrack? = runCatching {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelIndexMask(0xF)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_QUAD, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_BYTES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.setPreferredDevice(device); it.play() }
    }.onFailure { Log.w(TAG, "dualsense audio: could not open a 4-channel track", it) }.getOrNull()

    companion object {
        private const val TAG = "DualSenseAudio"
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 4
        const val FRAME_BYTES = CHANNELS * 2
        const val CHUNK_BYTES = SAMPLE_RATE / 100 * FRAME_BYTES
        private const val LOOKUP_INTERVAL_MS = 2000L

        private val USB_TYPES = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET)

        fun isDualSenseOutput(type: Int, productName: String?): Boolean {
            if (type !in USB_TYPES) return false
            val name = productName.orEmpty()
            return name.contains("DualSense", ignoreCase = true) || name.contains("Wireless Controller", ignoreCase = true)
        }

        internal fun alignFrames(
            chunk: ByteArray,
            carry: Int,
            read: Int,
            onWholeFrames: (ByteArray, Int) -> Unit,
        ): Int {
            val total = carry + read
            val whole = total - total % FRAME_BYTES
            if (whole > 0) onWholeFrames(chunk, whole)
            val rem = total - whole
            if (rem > 0 && whole > 0) System.arraycopy(chunk, whole, chunk, 0, rem)
            return rem
        }
    }
}
