package com.droiddeck.launcher.input

import com.droiddeck.launcher.glasses.Quaternion
import com.droiddeck.launcher.glasses.Vec3
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class JoyConShmTest {
    private lateinit var dir: File
    private lateinit var file: File
    private var fences = 0

    @Before fun setUp() {
        dir = Files.createTempDirectory("joycon-shm").toFile()
        file = File(dir, "run/${JoyConShm.FILE}")
    }

    @After fun tearDown() { dir.deleteRecursively() }

    @Test fun anOpenedFileCarriesTheHeaderAndNoHands() {
        assertNotNull(JoyConShm.open(file) { fences++ })
        val bytes = read()
        assertEquals(JoyConShm.SIZE, bytes.capacity())
        assertEquals(0x4E434A44, bytes.getInt(0))
        assertEquals(3, bytes.getInt(4))
        assertEquals(2, bytes.getInt(8))
        for (at in 16 until JoyConShm.SIZE step 4) assertEquals("byte $at", 0, bytes.getInt(at))
        assertEquals(2, fences)
    }

    @Test fun aHandLandsWhereTheDriverReadsIt() {
        val shm = JoyConShm.open(file) { fences++ }!!
        shm.write(JoyCon.RIGHT, JoyConShm.Hand(connected = true, charging = true,
            buttons = JoyCon.VR_TRIGGER or JoyCon.VR_A, rotation = Quaternion(0.5f, -0.5f, 0.25f, 0.75f),
            rate = Vec3(1f, -2f, 3f), displacement = Vec3(0.1f, 0.2f, -0.3f), velocity = Vec3(-1.5f, 0.5f, 2f), inertial = Vec3(4f, -5f, 6f), stickX = 0.25f, stickY = -0.5f, battery = 0.75f, recenter = 3, timeNs = 1_234_567_890_123L))
        val bytes = read()
        assertEquals(4, bytes.getInt(8))
        val at = 16 + 104
        assertEquals(3, bytes.getInt(at))
        assertEquals(JoyCon.VR_TRIGGER or JoyCon.VR_A, bytes.getInt(at + 4))
        assertArrayEquals(floatArrayOf(0.5f, -0.5f, 0.25f, 0.75f, 1f, -2f, 3f, 0.25f, -0.5f, 0.75f),
            FloatArray(10) { bytes.getFloat(at + 8 + it * 4) }, 0f)
        assertEquals(3, bytes.getInt(at + 48))
        assertEquals(1_234_567_890_123L, bytes.getLong(at + 56))
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, -0.3f, -1.5f, 0.5f, 2f, 4f, -5f, 6f), FloatArray(9) { bytes.getFloat(at + 64 + it * 4) }, 0f)
        assertEquals(224, JoyConShm.SIZE)
        assertEquals("the left hand was touched", 0, bytes.getInt(16))
        assertEquals(4, fences)
    }

    @Test fun closingDisconnectsBothHands() {
        val shm = JoyConShm.open(file) {}!!
        shm.write(JoyCon.LEFT, JoyConShm.Hand(connected = true))
        shm.write(JoyCon.RIGHT, JoyConShm.Hand(connected = true))
        shm.close()
        val bytes = read()
        assertEquals(0, bytes.getInt(16))
        assertEquals(0, bytes.getInt(16 + 104))
        assertEquals(0, bytes.getInt(8) and 1)
    }

    @Test fun reopeningKeepsTheSequenceMovingForward() {
        val first = JoyConShm.open(file) {}!!
        first.write(JoyCon.LEFT, JoyConShm.Hand(connected = true))
        assertEquals(4, read().getInt(8))
        first.close()
        val previous = read().getInt(8)
        assertNotNull(JoyConShm.open(file) {})
        assertTrue(read().getInt(8) > previous)
        java.io.RandomAccessFile(file, "rw").use { it.seek(8); it.write(byteArrayOf(7, 0, 0, 0)) }
        JoyConShm.open(file) {}!!
        val after = read().getInt(8)
        assertEquals(0, after and 1)
        assertTrue("$after after a torn write", after > 7)
    }

    @Test fun anEmptyFileStartsANewSequence() {
        file.parentFile!!.mkdirs()
        file.outputStream().use { }
        assertEquals(0L, file.length())
        assertNotNull(JoyConShm.open(file) {})
        assertEquals(2, read().getInt(8))
    }

    @Test fun anUnwritableLocationIsReportedAsMissing() {
        val blocker = File(dir, "blocker").apply { writeText("") }
        assertNull(JoyConShm.open(File(blocker, JoyConShm.FILE)) {})
    }

    private fun read(): ByteBuffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
}
