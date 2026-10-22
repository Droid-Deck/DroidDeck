package com.droiddeck.launcher.glasses

import com.droiddeck.launcher.gpu.HeadPose
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VitureV2LinkTest {
    private val transport = FakeTransport()
    private val listener = RecordingListener()
    private var link: VitureV2Link? = null

    @After fun close() { link?.close() }

    @Test fun writeAndAckDoNotBecomeReadyWithoutAValidPose() {
        val link = start()
        transport.feed(ack())
        transport.feed(pose(1).also { it[8] = (it[8].toInt() xor 1).toByte() })
        transport.feed(pose(2, floatArrayOf(0f, 0f, 0f, 0f)))
        transport.feed(VitureV2Protocol.packet(VitureV2Protocol.EVENT_RAW, ByteArray(56)))
        transport.flush()
        assertFalse(link.ready)
        assertFalse(link.hasImu)
        assertFalse(link.trackingActive)
        assertEquals(0, listener.readyCount.get())
        assertTrue(listener.poses.isEmpty())
        assertEquals(2L, link.badFrames)

        val receiptTime = System.nanoTime()
        val valid = pose(3)
        transport.feed(valid, receiptTime)
        transport.flush()
        assertTrue(link.ready)
        assertTrue(link.hasImu)
        assertTrue(link.trackingActive)
        assertEquals(1, listener.readyCount.get())
        assertEquals(listOf(true), listener.tracking)
        assertEquals(1, listener.poses.size)
        assertEquals(receiptTime, listener.poses.single().timeNs)
        assertArrayEquals(valid, listener.poses.single().raw)
    }

    @Test fun duplicatesAndBackwardTicksAreDroppedWhileUnsignedWrapAdvances() {
        start()
        for (tick in listOf(0xFFFFFFFEL, 0xFFFFFFFEL, 0xFFFFFFFDL, 0L, 0L, 0xFFFFFFFFL, 1L)) {
            transport.feed(pose(tick))
        }
        transport.flush()
        assertEquals(3, listener.poses.size)
        assertEquals(1, listener.readyCount.get())
        assertEquals(listOf(true), listener.tracking)
        assertEquals(0L, link!!.badFrames)
    }

    @Test fun poseCollectedDuringBootstrapWriteDoesNotEstablishReadiness() {
        transport.collectDuringNextWrite(pose(1))
        val link = start()
        assertFalse(link.ready)
        assertFalse(link.trackingActive)
        assertTrue(listener.poses.isEmpty())

        transport.feed(pose(2))
        transport.flush()
        assertTrue(link.ready)
        assertTrue(link.trackingActive)
        assertEquals(1, listener.readyCount.get())
        assertEquals(1, listener.poses.size)
    }

    @Test fun poseCollectedDuringResumeWriteDoesNotRestartTracking() {
        val link = startReady(100)
        assertTrue(link.send(VitureProtocol.MSG_IMU, byteArrayOf(0)))
        transport.expectWrite(VitureV2Protocol.imuControl(VitureV2Protocol.STREAM_OFF))
        transport.flush()

        transport.collectDuringNextWrite(pose(101))
        assertTrue(link.send(VitureProtocol.MSG_IMU, byteArrayOf(1)))
        transport.expectWrite(VitureV2Protocol.imuControl(VitureV2Protocol.STREAM_POSE))
        transport.flush()
        assertFalse(link.trackingActive)
        assertEquals(1, listener.poses.size)

        transport.feed(pose(102))
        transport.flush()
        assertTrue(link.trackingActive)
        assertEquals(2, listener.poses.size)
        assertEquals(listOf(true, false, true), listener.tracking)
    }

    @Test fun stoppingAndRestartingRequiresANewPoseAndRejectsQueuedOldSamples() {
        val link = startReady(100)
        assertTrue(link.send(VitureProtocol.MSG_IMU, byteArrayOf(0)))
        assertFalse(link.trackingActive)
        transport.expectWrite(VitureV2Protocol.imuControl(VitureV2Protocol.STREAM_OFF))
        transport.feed(pose(101))
        transport.feed(ack())
        transport.flush()
        assertEquals(1, listener.poses.size)
        assertTrue(link.ready)
        assertFalse(link.trackingActive)

        val beforeRestart = System.nanoTime()
        assertTrue(link.send(VitureProtocol.MSG_IMU, byteArrayOf(1)))
        transport.expectWrite(VitureV2Protocol.imuControl(VitureV2Protocol.STREAM_POSE))
        transport.feed(pose(102), beforeRestart)
        transport.feed(ack())
        transport.flush()
        assertFalse(link.trackingActive)
        assertEquals(1, listener.poses.size)

        transport.feed(pose(0))
        transport.flush()
        assertTrue(link.trackingActive)
        assertEquals(2, listener.poses.size)
        assertEquals(1, listener.readyCount.get())
        assertEquals(listOf(true, false, true), listener.tracking)
    }

    @Test fun onReadyCanStopBootstrapBeforeTheFirstPoseIsForwarded() {
        val link = start()
        listener.whenReady = { assertTrue(link.send(VitureProtocol.MSG_IMU, byteArrayOf(0))) }
        transport.feed(pose(1))
        transport.expectWrite(VitureV2Protocol.imuControl(VitureV2Protocol.STREAM_OFF))
        transport.flush()
        assertEquals(1, listener.readyCount.get())
        assertTrue(link.ready)
        assertFalse(link.trackingActive)
        assertTrue(listener.poses.isEmpty())
        assertEquals(listOf(true, false), listener.tracking)
    }

    @Test fun rapidStopStartCannotAcceptThePreviousStreamsPendingRead() {
        val link = startReady()
        val held = transport.hold(pose(2))
        try {
            assertTrue(link.send(VitureProtocol.MSG_IMU, byteArrayOf(0)))
            assertTrue(link.send(VitureProtocol.MSG_IMU, byteArrayOf(1)))
        } finally {
            held.release.countDown()
        }
        transport.expectWrite(VitureV2Protocol.imuControl(VitureV2Protocol.STREAM_OFF))
        transport.expectWrite(VitureV2Protocol.imuControl(VitureV2Protocol.STREAM_POSE))
        transport.flush()
        assertFalse(link.trackingActive)
        assertEquals(1, listener.poses.size)
        assertEquals(listOf(true, false), listener.tracking)

        transport.feed(pose(3))
        transport.flush()
        assertTrue(link.trackingActive)
        assertEquals(2, listener.poses.size)
    }

    @Test fun onReadyCanCloseWithoutForwardingAPoseOrReportingLoss() {
        val link = start()
        listener.whenReady = link::close
        transport.feed(pose(1))
        transport.awaitClosed()
        assertFalse(link.ready)
        assertFalse(link.trackingActive)
        assertTrue(listener.poses.isEmpty())
        assertEquals(0, listener.lostCount.get())
        transport.expectWrite(VitureV2Protocol.imuControl(VitureV2Protocol.STREAM_OFF))
    }

    @Test fun unsupportedSettersNeverProduceWritesAndQueriesKeepGenericIds() {
        val link = startReady()
        for ((id, payload) in listOf(
            VitureProtocol.MSG_BRIGHTNESS to byteArrayOf(3),
            VitureProtocol.MSG_VOLUME to byteArrayOf(3),
            VitureProtocol.MSG_DISPLAY to byteArrayOf(0x32),
            VitureProtocol.MSG_FILM to byteArrayOf(1),
            VitureProtocol.MSG_IMU_RATE to byteArrayOf(3),
            VitureProtocol.MSG_IMU to byteArrayOf(2),
            VitureProtocol.MSG_IMU to byteArrayOf(),
            VitureProtocol.MSG_GET_VOLUME to byteArrayOf(1),
        )) assertFalse("msgId=$id", link.send(id, payload))
        assertTrue(link.send(VitureProtocol.MSG_GET_IMU, byteArrayOf()))
        transport.flush()
        assertTrue(transport.writes.isEmpty())

        val queries = listOf(
            Triple(VitureProtocol.MSG_GET_BRIGHTNESS, VitureV2Protocol.MSG_GET_BRIGHTNESS, 3),
            Triple(VitureProtocol.MSG_GET_VOLUME, VitureV2Protocol.MSG_GET_VOLUME, 5),
            Triple(VitureProtocol.MSG_GET_DISPLAY, VitureV2Protocol.MSG_GET_DISPLAY, 0x31),
        )
        for ((genericId, wireId, value) in queries) {
            assertTrue(link.send(genericId, byteArrayOf()))
            transport.expectWrite(VitureV2Protocol.packet(wireId))
            transport.feed(VitureV2Protocol.packet(wireId + VitureV2Protocol.RESPONSE_OFFSET,
                byteArrayOf(0, value.toByte())))
        }
        transport.feed(VitureV2Protocol.packet(VitureV2Protocol.MSG_GET_VOLUME + VitureV2Protocol.RESPONSE_OFFSET,
            byteArrayOf(1, 7)))
        transport.feed(VitureV2Protocol.packet(VitureV2Protocol.MSG_GET_VOLUME + VitureV2Protocol.RESPONSE_OFFSET,
            byteArrayOf(0, 7, 8)))
        transport.flush()
        assertEquals(queries.map { it.first to it.third }, listener.values)
    }

    @Test fun failureToWriteBootstrapClosesTransportAndReportsLoss() {
        transport.failNextWrite.set(true)
        val link = VitureV2Link(transport, listener).also { this.link = it }
        transport.awaitClosed()
        assertFalse(link.ready)
        assertFalse(link.hasImu)
        assertFalse(link.trackingActive)
        assertEquals(0, listener.readyCount.get())
        assertEquals(1, listener.lostCount.get())
        transport.expectWrite(VitureV2Protocol.imuControl(VitureV2Protocol.STREAM_POSE))
        transport.expectWrite(VitureV2Protocol.imuControl(VitureV2Protocol.STREAM_OFF))
    }

    @Test fun rejectedStreamAckClosesWithoutMarkingTrackingActive() {
        val link = start()
        transport.feed(ack(status = 7))
        transport.awaitClosed()
        assertFalse(link.ready)
        assertFalse(link.trackingActive)
        assertEquals(1, listener.lostCount.get())
        assertEquals(0, listener.readyCount.get())
        assertTrue(listener.tracking.isEmpty())
        transport.expectWrite(VitureV2Protocol.imuControl(VitureV2Protocol.STREAM_OFF))
    }

    @Test fun readFailureClearsTrackingAndClosesTransport() {
        val link = startReady()
        transport.disconnect()
        transport.awaitClosed()
        assertFalse(link.ready)
        assertFalse(link.trackingActive)
        assertEquals(listOf(true, false), listener.tracking)
        assertEquals(1, listener.lostCount.get())
        assertFalse(link.send(VitureProtocol.MSG_IMU, byteArrayOf(1)))
        transport.expectWrite(VitureV2Protocol.imuControl(VitureV2Protocol.STREAM_OFF))
    }

    @Test fun explicitCloseSendsOffOnceAndDoesNotReportLoss() {
        val link = startReady()
        link.close()
        transport.awaitClosed()
        assertFalse(link.ready)
        assertFalse(link.trackingActive)
        assertEquals(listOf(true, false), listener.tracking)
        assertEquals(0, listener.lostCount.get())
        assertEquals(1, transport.closeCount.get())
        transport.expectWrite(VitureV2Protocol.imuControl(VitureV2Protocol.STREAM_OFF))
        link.close()
        assertFalse(link.send(VitureProtocol.MSG_GET_BRIGHTNESS, byteArrayOf()))
        assertEquals(1, transport.closeCount.get())
        assertTrue(transport.writes.isEmpty())
    }

    @Test fun sharedPoseProducesTheSourceRightUpBackRelativeRotation() {
        startReady()
        val baseline = listener.poses.single().q
        val reference = HeadPose.recenter(HeadPose.fromGlasses(baseline[0], baseline[1], baseline[2], baseline[3]),
            centerView = true)
        val half = sqrt(0.5f)
        val rotations = listOf(
            floatArrayOf(half, half, 0f, 0f),
            floatArrayOf(half, 0f, half, 0f),
            floatArrayOf(half, 0f, 0f, half),
            floatArrayOf(0.5f, -0.5f, 0.5f, -0.5f),
        )
        rotations.forEachIndexed { index, q -> transport.feed(pose(index + 2L, q)) }
        transport.flush()
        for ((source, event) in rotations.zip(listener.poses.drop(1))) {
            val q = event.q
            val relative = HeadPose.relative(reference, HeadPose.fromGlasses(q[0], q[1], q[2], q[3]))
            assertArrayEquals(HeadPose.fromQuaternion(source[1], source[2], source[3], source[0]), relative, 1e-5f)
        }
        assertEquals(rotations.size + 1, listener.poses.size)
    }

    private fun start(): VitureV2Link = VitureV2Link(transport, listener).also {
        link = it
        transport.expectWrite(VitureV2Protocol.imuControl(VitureV2Protocol.STREAM_POSE))
        transport.flush()
    }

    private fun startReady(tick: Long = 1): VitureV2Link = start().also {
        transport.feed(pose(tick))
        transport.flush()
        assertTrue(it.ready)
    }

    private fun ack(status: Int = 0) = VitureV2Protocol.packet(
        VitureV2Protocol.MSG_IMU_CONTROL + VitureV2Protocol.RESPONSE_OFFSET, byteArrayOf(status.toByte()))

    private fun pose(tick: Long, wxyz: FloatArray = floatArrayOf(1f, 0f, 0f, 0f)): ByteArray {
        val payload = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x126).putInt(tick.toInt())
        wxyz.forEach(payload::putFloat)
        return VitureV2Protocol.packet(VitureV2Protocol.EVENT_POSE, payload.array())
    }

    private data class PoseEvent(val raw: ByteArray, val q: FloatArray, val timeNs: Long)

    private class RecordingListener : VitureV2Link.Listener {
        val readyCount = AtomicInteger()
        val lostCount = AtomicInteger()
        val tracking = CopyOnWriteArrayList<Boolean>()
        val values = CopyOnWriteArrayList<Pair<Int, Int>>()
        val poses = CopyOnWriteArrayList<PoseEvent>()
        @Volatile var whenReady: (() -> Unit)? = null
        override fun onReady() { readyCount.incrementAndGet(); whenReady?.invoke() }
        override fun onValue(msgId: Int, value: Int) { values += msgId to value }
        override fun onPose(raw: ByteArray, q: FloatArray, timeNs: Long) { poses += PoseEvent(raw.copyOf(), q.copyOf(), timeNs) }
        override fun onTracking(active: Boolean) { tracking += active }
        override fun onLost() { lostCount.incrementAndGet() }
    }

    private sealed interface ReadStep {
        data class Packet(val received: VitureV2Link.Received) : ReadStep
        data class Barrier(val reached: CountDownLatch) : ReadStep
        data class HeldPacket(
            val received: VitureV2Link.Received,
            val entered: CountDownLatch = CountDownLatch(1),
            val release: CountDownLatch = CountDownLatch(1),
        ) : ReadStep
        data object Disconnected : ReadStep
    }

    private class FakeTransport : VitureV2Link.Transport {
        val writes = LinkedBlockingQueue<ByteArray>(64)
        val closeCount = AtomicInteger()
        val failNextWrite = AtomicBoolean()
        private val collectedDuringWrite = AtomicReference<ByteArray?>()
        private val incoming = LinkedBlockingQueue<ReadStep>(128)
        private val closed = CountDownLatch(1)

        override fun write(packet: ByteArray): Boolean {
            check(writes.offer(packet.copyOf()))
            val success = !failNextWrite.getAndSet(false)
            if (success) collectedDuringWrite.getAndSet(null)?.let { feed(it, System.nanoTime()) }
            return success
        }

        override fun read(timeoutMs: Long): VitureV2Link.Received? = when (
            val step = incoming.poll(timeoutMs, TimeUnit.MILLISECONDS)
        ) {
            is ReadStep.Packet -> step.received
            is ReadStep.Barrier -> { step.reached.countDown(); null }
            is ReadStep.HeldPacket -> {
                step.entered.countDown()
                check(step.release.await(2, TimeUnit.SECONDS))
                step.received
            }
            ReadStep.Disconnected -> throw IOException("Device disconnected")
            null -> null
        }

        override fun close() { closeCount.incrementAndGet(); closed.countDown() }

        fun feed(packet: ByteArray, timeNs: Long = System.nanoTime()) {
            check(incoming.offer(ReadStep.Packet(VitureV2Link.Received(packet.copyOf(), timeNs))))
        }

        fun collectDuringNextWrite(packet: ByteArray) {
            check(collectedDuringWrite.compareAndSet(null, packet.copyOf()))
        }

        fun disconnect() { check(incoming.offer(ReadStep.Disconnected)) }

        fun hold(packet: ByteArray): ReadStep.HeldPacket {
            val held = ReadStep.HeldPacket(VitureV2Link.Received(packet.copyOf(), System.nanoTime()))
            check(incoming.offer(held))
            assertTrue("Worker did not enter the held read", held.entered.await(2, TimeUnit.SECONDS))
            return held
        }

        fun expectWrite(expected: ByteArray) {
            assertArrayEquals(expected, writes.poll(2, TimeUnit.SECONDS))
        }

        fun flush() {
            val reached = CountDownLatch(1)
            check(incoming.offer(ReadStep.Barrier(reached)))
            assertTrue("Worker did not consume queued reports", reached.await(2, TimeUnit.SECONDS))
        }

        fun awaitClosed() { assertTrue("Transport did not close", closed.await(2, TimeUnit.SECONDS)) }
    }
}
