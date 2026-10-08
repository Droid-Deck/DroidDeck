package com.droiddeck.launcher.input

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Steam's touch controller, as the app's side of it.
 *
 * libfakeinput offers the Steam client a HID device with the ids of Steam Link's touch controls
 * (0000:11fb), and the client builds its own touch controller for it: Steam Input's Mobile Touch
 * type, with the game's touch config, action sets, community configs and cloud sync all Steam's
 * (docs/development/steam-touch-controller.md). This class is the file the two share
 * (fakeinput_steam.cpp: TouchRingFile): the app writes the device's 40-byte input report and
 * whether it is plugged in; libfakeinput writes back what the client told the device - the app and
 * action set it is on, its layers, rumble.
 */
class SteamTouchDevice private constructor(private val buffer: ByteBuffer) {

    /** What the client last told the device. */
    data class State(
        val opened: Int,
        val appId: Int,
        val actionSet: Int,
        val layers: List<Int>,
        val actionSeq: Int,
    )

    // The input report (Steam Link's CVirtualController, docs/development/steam-touch-controller.md):
    // bytes 0-7 buttons, 8-15 sticks, 16-27 trackpads, 28-33 accelerometer, 34-39 gyro.
    private val report = ByteArray(REPORT_BYTES)
    private var reportSeq = 0L

    /** The controls part of the report: button bits, sticks (LX, LY, RX, RY, already in the report's
     *  units) and trackpads (centre, left, right: x, y each). */
    @Synchronized
    fun setControls(buttons: Long, sticks: ShortArray, pads: ShortArray) {
        val next = report.copyOf()
        for (i in 0 until 8) next[i] = (buttons ushr (8 * i)).toByte()
        for (i in 0 until 4) putShort(next, 8 + 2 * i, sticks[i])
        for (i in 0 until 6) putShort(next, 16 + 2 * i, pads[i])
        publish(next)
    }

    /** Motion in the Deck's axes and units (PadMotion), which the touch report shares. */
    @Synchronized
    fun setMotion(accel: ShortArray, gyro: ShortArray) {
        if (!plugged) return
        val next = report.copyOf()
        for (i in 0 until 3) {
            putShort(next, 28 + 2 * i, accel[i])
            putShort(next, 34 + 2 * i, gyro[i])
        }
        publish(next)
    }

    private fun putShort(into: ByteArray, at: Int, value: Short) {
        into[at] = value.toInt().toByte()
        into[at + 1] = (value.toInt() shr 8).toByte()
    }

    /** Writes the report under the seqlock libfakeinput reads it with. */
    private fun publish(next: ByteArray) {
        if (next.contentEquals(report)) return
        next.copyInto(report)
        buffer.putLong(OFF_REPORT_SEQ, ++reportSeq)
        for (i in 0 until REPORT_BYTES) buffer.put(OFF_REPORT + i, report[i])
        buffer.putLong(OFF_REPORT_SEQ, ++reportSeq)
    }

    var plugged: Boolean
        get() = buffer.getInt(OFF_PLUGGED) != 0
        set(value) {
            if (value == plugged) return
            if (!value) synchronized(this) { publish(ByteArray(REPORT_BYTES)) }
            buffer.putInt(OFF_PLUGGED, if (value) 1 else 0)
            Log.i(TAG, "steam touch: " + if (value) "plugged in" else "unplugged")
        }

    fun setBattery(percent: Int) = buffer.putInt(OFF_BATTERY, percent.coerceIn(0, 100))

    fun state(): State {
        repeat(8) {
            val seq = buffer.getLong(OFF_STATE_SEQ)
            if (seq and 1L != 0L) return@repeat
            val count = buffer.getInt(OFF_LAYER_COUNT).coerceIn(0, 8)
            val read = State(
                opened = buffer.getInt(OFF_OPENED),
                appId = buffer.getInt(OFF_APPID),
                actionSet = buffer.getInt(OFF_ACTION_SET),
                layers = (0 until count).map { buffer.getInt(OFF_LAYERS + 4 * it) },
                actionSeq = buffer.getInt(OFF_ACTION_SEQ),
            )
            if (seq == buffer.getLong(OFF_STATE_SEQ)) return read
        }
        return State(0, 0, 0, emptyList(), -1)
    }

    companion object {
        private const val TAG = "SteamTouch"
        const val REPORT_BYTES = 40
        private const val SIZE = 256
        private const val MAGIC = 0x31484354 // TCH1
        private const val OFF_REPORT_SEQ = 8
        private const val OFF_REPORT = 16
        private const val OFF_PLUGGED = 56
        private const val OFF_BATTERY = 60
        private const val OFF_STATE_SEQ = 64
        private const val OFF_OPENED = 72
        private const val OFF_APPID = 76
        private const val OFF_ACTION_SET = 80
        private const val OFF_LAYER_COUNT = 84
        private const val OFF_LAYERS = 88
        private const val OFF_ACTION_SEQ = 136

        @Volatile
        var current: SteamTouchDevice? = null
            private set

        /** The shared file in the session's pad directory, made fresh each session. */
        fun ringFile(fakeInputDir: File) = File(fakeInputDir, "touchctl")

        /** Creates the file for a new session (unplugged) and maps it. */
        fun prepare(fakeInputDir: File): File? = try {
            val file = ringFile(fakeInputDir)
            file.delete()
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(SIZE.toLong())
                val map = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, SIZE.toLong()).order(ByteOrder.LITTLE_ENDIAN)
                map.putInt(4, 1)
                map.putInt(0, MAGIC)
                current = SteamTouchDevice(map)
            }
            file.setReadable(true, false)
            file.setWritable(true, false)
            file
        } catch (e: Exception) {
            Log.w(TAG, "steam touch: no shared file: $e")
            current = null
            null
        }

        fun release() {
            current?.plugged = false
            current = null
        }
    }
}
