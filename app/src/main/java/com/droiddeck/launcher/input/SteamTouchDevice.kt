package com.droiddeck.launcher.input

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class SteamTouchDevice private constructor(private val buffer: ByteBuffer) {

    data class State(
        val opened: Int,
        val appId: Int,
        val actionSet: Int,
        val layers: List<Int>,
        val actionSeq: Int,
    )

    private val report = ByteArray(REPORT_BYTES)
    private var reportSeq = 0L

    @Synchronized
    fun setControls(buttons: Long, sticks: ShortArray, pads: ShortArray) {
        val next = report.copyOf()
        for (i in 0 until 8) next[i] = (buttons ushr (8 * i)).toByte()
        for (i in 0 until 4) putShort(next, 8 + 2 * i, sticks[i])
        for (i in 0 until 6) putShort(next, 16 + 2 * i, pads[i])
        publish(next)
    }

    @Synchronized
    fun setMotion(accel: ShortArray, gyro: ShortArray) {
        if (!plugged || !gyroRequested || !motionEnabled) return
        val next = report.copyOf()
        for (i in 0 until 3) {
            putShort(next, 28 + 2 * i, accel[i])
            putShort(next, 34 + 2 * i, (gyro[i] * 2).coerceIn(-32767, 32767).toShort())
        }
        publish(next)
    }

    val gyroRequested get() = buffer.getInt(OFF_SETTING30) != 0

    @Volatile var motionEnabled = true
        set(value) {
            field = value
            if (!value) synchronized(this) {
                val next = report.copyOf()
                for (i in 28 until 40) next[i] = 0
                publish(next)
            }
        }

    private fun putShort(into: ByteArray, at: Int, value: Short) {
        into[at] = value.toInt().toByte()
        into[at + 1] = (value.toInt() shr 8).toByte()
    }

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
        private const val MAGIC = 0x31484354
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
        private const val OFF_SETTING30 = 132
        private const val OFF_ACTION_SEQ = 136

        @Volatile
        var current: SteamTouchDevice? = null
            private set

        fun ringFile(fakeInputDir: File) = File(fakeInputDir, "touchctl")

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
