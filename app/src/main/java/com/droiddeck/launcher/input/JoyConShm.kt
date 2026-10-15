package com.droiddeck.launcher.input

import android.util.Log
import com.droiddeck.launcher.glasses.Quaternion
import com.droiddeck.launcher.glasses.Vec3
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

internal class JoyConShm private constructor(
    private val file: RandomAccessFile,
    private val map: MappedByteBuffer,
    private val fence: () -> Unit,
) {
    class Hand(
        val connected: Boolean,
        val charging: Boolean = false,
        val buttons: Int = 0,
        val rotation: Quaternion = Quaternion.IDENTITY,
        val rate: Vec3 = Vec3(0f, 0f, 0f),
        val stickX: Float = 0f,
        val stickY: Float = 0f,
        val battery: Float = -1f,
        val recenter: Int = 0,
        val timeNs: Long = 0L,
    )

    private var seq = map.getInt(SEQ).let { if (it and 1 != 0) it + 1 else it }

    init {
        begin()
        map.putInt(MAGIC_AT, MAGIC)
        map.putInt(VERSION_AT, VERSION)
        for (at in HANDS until SIZE step 4) map.putInt(at, 0)
        end()
    }

    fun write(side: Int, hand: Hand) {
        val at = HANDS + side * HAND_SIZE
        begin()
        map.putInt(at, (if (hand.connected) CONNECTED else 0) or (if (hand.charging) CHARGING else 0))
        map.putInt(at + 4, hand.buttons)
        map.putFloat(at + 8, hand.rotation.w)
        map.putFloat(at + 12, hand.rotation.x)
        map.putFloat(at + 16, hand.rotation.y)
        map.putFloat(at + 20, hand.rotation.z)
        map.putFloat(at + 24, hand.rate.x)
        map.putFloat(at + 28, hand.rate.y)
        map.putFloat(at + 32, hand.rate.z)
        map.putFloat(at + 36, hand.stickX)
        map.putFloat(at + 40, hand.stickY)
        map.putFloat(at + 44, hand.battery)
        map.putInt(at + 48, hand.recenter)
        map.putLong(at + 56, hand.timeNs)
        end()
    }

    fun close() {
        write(JoyCon.LEFT, Hand(connected = false))
        write(JoyCon.RIGHT, Hand(connected = false))
        runCatching { file.close() }
    }

    private fun begin() {
        map.putInt(SEQ, ++seq)
        fence()
    }

    private fun end() {
        fence()
        map.putInt(SEQ, ++seq)
    }

    companion object {
        const val FILE = "droiddeck-joycon"
        const val SIZE = 176
        private const val MAGIC = 0x4E434A44
        private const val VERSION = 1
        private const val MAGIC_AT = 0
        private const val VERSION_AT = 4
        private const val SEQ = 8
        private const val HANDS = 16
        private const val HAND_SIZE = 80
        private const val CONNECTED = 1
        private const val CHARGING = 2

        fun open(path: File, fence: () -> Unit = FakeInputWriter::storeFence): JoyConShm? = runCatching {
            path.parentFile?.mkdirs()
            val file = RandomAccessFile(path, "rw")
            try {
                file.setLength(SIZE.toLong())
                val map = file.channel.map(FileChannel.MapMode.READ_WRITE, 0, SIZE.toLong())
                map.order(ByteOrder.LITTLE_ENDIAN)
                JoyConShm(file, map, fence)
            } catch (e: Exception) {
                file.close()
                throw e
            }
        }.onFailure { Log.w("JoyConVr", "Joy-Con pose file unavailable: $path", it) }.getOrNull()
    }
}
