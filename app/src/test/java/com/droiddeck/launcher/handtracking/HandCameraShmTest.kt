package com.droiddeck.launcher.handtracking

import com.droiddeck.launcher.glasses.Vec3
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class HandCameraShmTest {
    private lateinit var dir: File
    private lateinit var file: File
    private var fences = 0

    @Before fun setUp() {
        dir = Files.createTempDirectory("handcam-shm").toFile()
        file = File(dir, "run/${HandCameraShm.FILE}")
    }

    @After fun tearDown() { dir.deleteRecursively() }

    @Test fun anOpenedFileCarriesTheHeaderAndNothingSeen() {
        assertNotNull(HandCameraShm.open(file) { fences++ })
        val bytes = read()
        assertEquals(HandCameraShm.SIZE, bytes.capacity())
        assertEquals(568, HandCameraShm.SIZE)
        assertEquals(0x4D434844, bytes.getInt(0))
        assertEquals(2, bytes.getInt(4))
        assertEquals(2, bytes.getInt(8))
        for (at in 12 until HandCameraShm.SIZE step 4) assertEquals("byte $at", 0, bytes.getInt(at))
        assertEquals(2, fences)
    }

    @Test fun aFrameLandsWhereTheDriverReadsIt() {
        val shm = HandCameraShm.open(file) { fences++ }!!
        shm.write(123_456_789_012L, BodyFrame(arrayOf(Vec3(0.2f, -0.45f, -0.3f), null), 2.5f, 1.8f, 0.2f, true))
        val bytes = read()
        assertEquals(4, bytes.getInt(8))
        assertEquals(3, bytes.getInt(12))
        assertEquals(123_456_789_012L, bytes.getLong(16))
        assertEquals(1, bytes.getInt(24))
        assertEquals(2.5f, bytes.getFloat(28), 0f)
        assertEquals(1, bytes.getInt(32))
        assertEquals(0.2f, bytes.getFloat(36), 0f)
        assertEquals(-0.45f, bytes.getFloat(40), 0f)
        assertEquals(-0.3f, bytes.getFloat(44), 0f)
        assertEquals(0f, bytes.getFloat(48), 0f)
        assertEquals(0, bytes.getInt(300))
        assertEquals(0f, bytes.getFloat(304), 0f)
    }

    @Test fun fingerJointsAndTheBareHandsFlagLandWhereTheDriverReadsThem() {
        val shm = HandCameraShm.open(file) { fences++ }!!
        val joints = FloatArray(BodyPose.HAND_POINTS * 3) { it * 0.001f - 0.03f }
        shm.write(7L, BodyFrame(arrayOf(null, Vec3(0.1f, -0.4f, -0.35f)), 0f, 2f, 0.2f, true, joints = arrayOf(null, joints)), bareHands = true)
        val bytes = read()
        assertEquals(1 or 2 or 4, bytes.getInt(12))
        assertEquals(0, bytes.getInt(32))
        assertEquals(1 or 4, bytes.getInt(300))
        assertEquals(0.1f, bytes.getFloat(304), 0f)
        for (i in joints.indices) assertEquals("joint float $i", joints[i], bytes.getFloat(316 + i * 4), 0f)
        assertEquals(HandCameraShm.SIZE, 316 + joints.size * 4)
    }

    @Test fun aFrameWithoutAPersonOnlySaysTheCameraRuns() {
        val shm = HandCameraShm.open(file) { fences++ }!!
        shm.write(5L, BodyFrame(arrayOf(Vec3(1f, 1f, 1f), Vec3(1f, 1f, 1f)), null, 2f, 0.2f, true))
        shm.write(6L, null)
        val bytes = read()
        assertEquals(1, bytes.getInt(12))
        assertEquals(6L, bytes.getLong(16))
        assertEquals(2, bytes.getInt(24))
        assertEquals(0, bytes.getInt(32))
        assertEquals(0, bytes.getInt(300))
    }

    @Test fun closingClearsTheFrame() {
        val shm = HandCameraShm.open(file) { fences++ }!!
        shm.write(5L, BodyFrame(arrayOf(Vec3(1f, 1f, 1f), null), 0f, 2f, 0.2f, true))
        shm.close()
        val bytes = read()
        assertEquals(0x4D434844, bytes.getInt(0))
        assertEquals(0, bytes.getInt(8) and 1)
        for (at in 12 until HandCameraShm.SIZE step 4) assertEquals("byte $at", 0, bytes.getInt(at))
    }

    private fun read(): ByteBuffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
}
