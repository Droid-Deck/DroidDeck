package com.droiddeck.launcher.handtracking

import android.util.Log
import com.droiddeck.launcher.glasses.Vec3
import com.droiddeck.launcher.input.FakeInputWriter
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

internal class HandCameraShm private constructor(
    private val file: RandomAccessFile,
    private val map: MappedByteBuffer,
    private val fence: () -> Unit,
) {
    private var seq = map.getInt(SEQ).let { if (it and 1 != 0) it + 1 else it }
    private var frame = 0

    init {
        begin()
        map.putInt(MAGIC_AT, MAGIC)
        map.putInt(VERSION_AT, VERSION)
        for (at in FLAGS until SIZE step 4) map.putInt(at, 0)
        end()
    }

    fun write(timeNs: Long, body: BodyFrame?, bareHands: Boolean = false) {
        begin()
        val face = body?.faceYaw
        map.putInt(FLAGS, RUNNING or (if (face != null) FACE else 0) or (if (bareHands) BARE_HANDS else 0))
        map.putLong(TIME, timeNs)
        map.putInt(FRAME, ++frame)
        map.putFloat(FACE_YAW, face ?: 0f)
        for (side in 0..1) {
            val at = HANDS + side * HAND_SIZE
            val hand: Vec3? = body?.hands?.getOrNull(side)
            val joints = if (hand != null) body?.joints?.getOrNull(side)?.takeIf { it.size == JOINT_FLOATS } else null
            val seen = if (body?.estimated?.getOrNull(side) == true) SEEN or ESTIMATED else SEEN
            map.putInt(at, if (hand == null) 0 else if (joints != null) seen or JOINTS else seen)
            map.putFloat(at + 4, hand?.x ?: 0f)
            map.putFloat(at + 8, hand?.y ?: 0f)
            map.putFloat(at + 12, hand?.z ?: 0f)
            for (i in 0 until JOINT_FLOATS) map.putFloat(at + JOINTS_AT + i * 4, joints?.get(i) ?: 0f)
        }
        end()
    }

    fun close() {
        begin()
        for (at in FLAGS until SIZE step 4) map.putInt(at, 0)
        end()
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
        const val FILE = "droiddeck-handcam"
        const val SIZE = 568
        private const val MAGIC = 0x4D434844
        private const val VERSION = 2
        private const val MAGIC_AT = 0
        private const val VERSION_AT = 4
        private const val SEQ = 8
        private const val FLAGS = 12
        private const val TIME = 16
        private const val FRAME = 24
        private const val FACE_YAW = 28
        private const val HANDS = 32
        private const val HAND_SIZE = 268
        private const val JOINTS_AT = 16
        private const val JOINT_FLOATS = BodyPose.HAND_POINTS * 3
        private const val RUNNING = 1
        private const val FACE = 2
        private const val BARE_HANDS = 4
        private const val SEEN = 1
        private const val ESTIMATED = 2
        private const val JOINTS = 4

        fun open(path: File, fence: () -> Unit = FakeInputWriter::storeFence): HandCameraShm? = runCatching {
            path.parentFile?.mkdirs()
            val file = RandomAccessFile(path, "rw")
            try {
                file.setLength(SIZE.toLong())
                val map = file.channel.map(FileChannel.MapMode.READ_WRITE, 0, SIZE.toLong())
                map.order(ByteOrder.LITTLE_ENDIAN)
                HandCameraShm(file, map, fence)
            } catch (e: Exception) {
                file.close()
                throw e
            }
        }.onFailure { Log.w("HandCamera", "hand camera file unavailable: $path", it) }.getOrNull()
    }
}
