package com.droiddeck.launcher.glasses

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class XrealLinkTest {
    private class Input : InputStream() {
        private val bytes = LinkedBlockingQueue<Int>()
        @Volatile private var closed = false
        fun add(value: ByteArray) { value.forEach { bytes.add(it.toInt() and 255) } }
        override fun read(): Int = if (closed && bytes.isEmpty()) -1 else bytes.take()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val first = read()
            if (first < 0) return -1
            b[off] = first.toByte()
            var count = 1
            while (count < len) {
                val value = bytes.poll() ?: break
                if (value < 0) break
                b[off + count++] = value.toByte()
            }
            return count
        }
        override fun close() { closed = true; bytes.clear(); bytes.offer(-1) }
    }

    private class Channels : XrealChannels {
        override val controlInput = Input()
        override val reports = Input()
        val wrote = LinkedBlockingQueue<Int>()
        val closed = CountDownLatch(1)
        @Volatile var acknowledge = true
        override val controlOutput = object : OutputStream() {
            private val buffer = ByteArrayOutputStream()
            override fun write(b: Int) { buffer.write(b) }
            override fun flush() {
                val data = buffer.toByteArray(); buffer.reset()
                val request = XrealProtocol.read(data.inputStream())
                val id = ByteBuffer.wrap(request.body).int and Int.MAX_VALUE
                wrote.offer(request.command)
                if (acknowledge) controlInput.add(XrealProtocolTest.response(request.command, id,
                    if (request.command == XrealProtocol.GET_DISPLAY) byteArrayOf(0x22, 2, 0x10, 2) else byteArrayOf(0x22, 0)))
            }
        }
        override fun close() { controlInput.close(); reports.close(); closed.countDown() }
    }

    private class Listener : XrealLink.Listener {
        val ready = CountDownLatch(1)
        val poses = LinkedBlockingQueue<FloatArray>()
        val values = LinkedBlockingQueue<Pair<Int, Int>>()
        val errors = LinkedBlockingQueue<Boolean>()
        override fun onReady() { ready.countDown() }
        override fun onValue(message: Int, value: Int) { values.offer(message to value) }
        override fun onPose(raw: ByteArray, pose: FloatArray, timeNs: Long) { poses.offer(pose) }
        override fun onUnavailable(networkMissing: Boolean) { errors.offer(networkMissing) }
    }

    @Test fun trackingUsesSharedPoseAndCommandsAreAcknowledgedBeforePublishing() {
        val channels = Channels()
        val listener = Listener()
        val connector = object : XrealConnector {
            override fun connect() = channels
            override fun close() = channels.close()
        }
        val link = XrealLink(connector, listener)
        try {
            assertTrue(listener.ready.await(2, TimeUnit.SECONDS))
            assertEquals(VitureProtocol.MSG_GET_DISPLAY to 2, listener.values.poll(2, TimeUnit.SECONDS))
            assertTrue(link.send(VitureProtocol.MSG_IMU, byteArrayOf(1)))
            channels.reports.add(XrealProtocolTest.frame(XrealProtocol.REPORT, XrealProtocolTest.sensor()))
            assertNotNull(listener.poses.poll(2, TimeUnit.SECONDS))
            assertFalse(link.send(VitureProtocol.MSG_VOLUME, byteArrayOf(5)))
            assertFalse(link.send(VitureProtocol.MSG_DISPLAY, byteArrayOf(0x32)))
            assertTrue(link.send(VitureProtocol.MSG_BRIGHTNESS, byteArrayOf(6)))
            assertEquals(VitureProtocol.MSG_BRIGHTNESS to 6, listener.values.poll(2, TimeUnit.SECONDS))
        } finally { link.close() }
        assertFalse(link.ready)
        assertTrue(channels.closed.await(1, TimeUnit.SECONDS))
        assertFalse(link.send(VitureProtocol.MSG_IMU, byteArrayOf(1)))
    }

    @Test fun failedControlReadDisconnectsWithoutPublishingRequestedBrightness() {
        val channels = Channels()
        val listener = Listener()
        val link = XrealLink(object : XrealConnector {
            override fun connect() = channels
            override fun close() = channels.close()
        }, listener)
        try {
            assertTrue(listener.ready.await(2, TimeUnit.SECONDS))
            listener.values.clear()
            channels.acknowledge = false
            assertTrue(link.send(VitureProtocol.MSG_BRIGHTNESS, byteArrayOf(8)))
            channels.controlInput.close()
            assertEquals(false, listener.errors.poll(2, TimeUnit.SECONDS))
            assertFalse(link.ready)
            assertNull(listener.values.poll())
        } finally { link.close() }
    }

    @Test fun missingNetworkIsReportedAndCloseCancelsRetry() {
        val listener = Listener()
        val closed = AtomicBoolean()
        val link = XrealLink(object : XrealConnector {
            override fun connect(): XrealChannels = throw XrealNetworkMissing()
            override fun close() { closed.set(true) }
        }, listener)
        try {
            assertEquals(true, listener.errors.poll(2, TimeUnit.SECONDS))
            assertFalse(link.ready)
        } finally { link.close() }
        assertTrue(closed.get())
    }

    @Test fun closeDuringConnectCannotPublishReadyOrLeakSockets() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val channels = Channels()
        val listener = Listener()
        val link = XrealLink(object : XrealConnector {
            override fun connect(): XrealChannels {
                entered.countDown()
                release.await()
                return channels
            }
            override fun close() { release.countDown(); channels.close() }
        }, listener)
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        link.close()
        assertTrue(channels.closed.await(1, TimeUnit.SECONDS))
        assertFalse(listener.ready.await(100, TimeUnit.MILLISECONDS))
        assertTrue(listener.values.isEmpty())
    }
}
