package com.droiddeck.launcher.session

import android.content.Context
import android.hardware.lights.Light
import android.hardware.lights.LightsManager
import android.hardware.lights.LightsRequest
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.CombinedVibration
import android.os.VibratorManager
import android.view.InputDevice
import com.droiddeck.launcher.input.DualSense
import com.droiddeck.launcher.input.DualSenseManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.InputDeviceBuilder
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    shadows = [
        DualSenseOutputBridgeTest.ShadowUsbConnection::class,
        DualSenseOutputBridgeTest.TestableServerSocket::class,
        DualSenseOutputBridgeTest.TestableClientSocket::class
    ]
)
class DualSenseOutputBridgeTest {
    private lateinit var context: Context
    private lateinit var tempDir: File
    private lateinit var bridge: DualSenseOutputBridge

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        tempDir = File(context.cacheDir, "test-bridge-${System.nanoTime()}").apply { mkdirs() }
        SessionState.fakeInputDir = tempDir
        bridge = DualSenseOutputBridge()

        TestableServerSocket.reset()
        TestableClientSocket.reset()
        DualSenseManager.activeManager = null
    }

    @After
    fun tearDown() {
        bridge.stop()
        DualSenseManager.activeManager = null
        SessionState.fakeInputDir = null
        tempDir.deleteRecursively()
        TestableServerSocket.reset()
        TestableClientSocket.reset()
    }

    private fun inputDevice(
        pid: Int = DualSense.PRODUCT_DUALSENSE,
        vid: Int = DualSense.VENDOR_SONY,
        name: String = "DualSense",
        id: Int = 10
    ): InputDevice {
        val dev = InputDeviceBuilder.newBuilder()
            .setId(id)
            .setName(name)
            .setDescriptor("test-ds-$id")
            .setVendorId(vid)
            .setProductId(pid)
            .setSources(InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK)
            .build()
        val im = context.getSystemService(Context.INPUT_SERVICE) as android.hardware.input.InputManager
        org.robolectric.Shadows.shadowOf(im).addInputDevice(dev)
        return dev
    }

    // -------------------------------------------------------------------------
    // 1. Black Lightbar Fix Verification (Critical Orchestrator / DSX Scanner Fix)
    // -------------------------------------------------------------------------

    @Test
    fun testBlackLightbarFixSetsEnable2LedBit() {
        // When requesting a black/off lightbar (r=0, g=0, b=0), enable2 & 0x04 MUST be set
        // so the physical DualSense turns off its LEDs rather than staying stuck on the previous color.
        val report = DualSense.buildOutputReport(
            leftMotor = 0,
            rightMotor = 0,
            r = 0,
            g = 0,
            b = 0,
            micLed = 0,
            playerLeds = 0
        )

        assertEquals(DualSense.REPORT_ID_OUTPUT, report[0])
        val enable2 = report[2].toInt() and 0xFF
        assertTrue("Expected 0x04 (AllowLedColor) to be set for r=0,g=0,b=0 black lightbar", (enable2 and 0x04) != 0)
        assertEquals(0.toByte(), report[45]) // R
        assertEquals(0.toByte(), report[46]) // G
        assertEquals(0.toByte(), report[47]) // B

        // With explicit updateLightbar = false and r=0,g=0,b=0, enable2 & 0x04 remains 0
        val reportNoUpdate = DualSense.buildOutputReport(
            r = 0, g = 0, b = 0,
            updateLightbar = false
        )
        val enable2NoUpdate = reportNoUpdate[2].toInt() and 0xFF
        assertEquals(0, enable2NoUpdate and 0x04)
    }

    // -------------------------------------------------------------------------
    // 2. 48-Byte Output Report Validation and Field Layout
    // -------------------------------------------------------------------------

    @Test
    fun testOutputReportRejectionOfInvalidPackets() {
        val outStream = ByteArrayOutputStream()
        bridge.hostBridgeOutputStream = outStream

        // 1. Empty report
        assertFalse(bridge.dispatchOutputReport(ByteArray(0)))
        assertEquals(0, outStream.size())

        // 2. Too short (< 48 bytes)
        assertFalse(bridge.dispatchOutputReport(ByteArray(47).apply { this[0] = 0x02 }))
        assertEquals(0, outStream.size())

        // 3. Wrong Report ID (e.g. 0x01 Input Report)
        assertFalse(bridge.dispatchOutputReport(ByteArray(48).apply { this[0] = 0x01 }))
        assertEquals(0, outStream.size())

        // 4. Valid 48-byte report starting with 0x02
        val valid = ByteArray(48).apply { this[0] = DualSense.REPORT_ID_OUTPUT }
        assertTrue(bridge.dispatchOutputReport(valid))
        assertEquals(48, outStream.size())

        // 5. Oversized (> 48 bytes)
        assertFalse(bridge.dispatchOutputReport(ByteArray(49).apply { this[0] = DualSense.REPORT_ID_OUTPUT }))
        assertEquals(48, outStream.size()) // unchanged
    }

    @Test
    fun testOutputReportFieldLayout() {
        val r2Effect = byteArrayOf(0x02, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
        val l2Effect = byteArrayOf(0x01, 15, 25, 35, 45, 55, 65, 75, 85, 95, 105)

        val report = DualSense.buildOutputReport(
            leftMotor = 250,
            rightMotor = 125,
            r = 255,
            g = 128,
            b = 64,
            micLed = 1,
            playerLeds = 0x04,
            rightTriggerEffect = r2Effect,
            leftTriggerEffect = l2Effect,
            isEdge = true,
            disableEdgeFnSwitching = true
        )

        assertEquals(48, report.size)
        assertEquals(DualSense.REPORT_ID_OUTPUT, report[0])
        val enable1 = report[1].toInt() and 0xFF
        assertEquals(0x01 or 0x02 or 0x04 or 0x08, enable1)

        val enable2 = report[2].toInt() and 0xFF
        assertEquals(0x01 or 0x04 or 0x10 or 0x40, enable2)

        assertEquals(125.toByte(), report[3]) // Right weak motor
        assertEquals(250.toByte(), report[4]) // Left strong motor
        assertEquals(1.toByte(), report[9])   // Mic LED solid

        // Trigger effect blocks
        val r2Slice = report.copyOfRange(11, 22)
        val l2Slice = report.copyOfRange(22, 33)
        assertTrue(r2Effect.contentEquals(r2Slice))
        assertTrue(l2Effect.contentEquals(l2Slice))

        // DualSense Edge flags
        assertEquals(0xC7.toByte(), report[39]) // EnableBits3 on Edge
        assertEquals(0x80.toByte(), report[41]) // Disable Edge FN switching

        assertEquals(0x02.toByte(), report[42]) // Custom RGB lightbar setup
        assertEquals(0x04.toByte(), report[44]) // Player 1 LED
        assertEquals(255.toByte(), report[45])  // Red
        assertEquals(128.toByte(), report[46])  // Green
        assertEquals(64.toByte(), report[47])   // Blue
    }

    // -------------------------------------------------------------------------
    // 3. Forwarding to Host Bridge TCP OutputStream
    // -------------------------------------------------------------------------

    @Test
    fun testForwardingToHostBridgeTcpOutputStreamDirect() {
        val outStream = ByteArrayOutputStream()
        bridge.hostBridgeOutputStream = outStream

        val report = DualSense.buildOutputReport(
            leftMotor = 180,
            rightMotor = 90,
            r = 10,
            g = 20,
            b = 30
        )

        val handled = bridge.dispatchOutputReport(report)
        assertTrue(handled)
        assertEquals(48, outStream.size())
        val written = outStream.toByteArray()
        assertTrue(report.contentEquals(written))
    }

    @Test
    fun testForwardingThroughDualSenseManagerTcpOutputStream() {
        val manager = DualSenseManager(context)
        DualSenseManager.activeManager = manager

        val outStream = ByteArrayOutputStream()
        manager.tcpOutputStream = outStream

        val report = DualSense.buildOutputReport(
            leftMotor = 100,
            rightMotor = 50,
            r = 255,
            g = 0,
            b = 128
        )

        // Dispatch via bridge should route through activeManager
        val handled = bridge.dispatchOutputReport(report)
        assertTrue(handled)
        assertEquals(48, outStream.size())
        assertTrue(report.contentEquals(outStream.toByteArray()))

        manager.stop()
    }

    // -------------------------------------------------------------------------
    // 4. Forwarding to USB UsbDeviceConnection
    // -------------------------------------------------------------------------

    @Test
    fun testForwardingToUsbBulkTransferDirect() {
        val conn = Shadow.newInstanceOf(UsbDeviceConnection::class.java)
        val ep = Shadow.newInstanceOf(UsbEndpoint::class.java)
        val shadowConn = Shadow.extract<ShadowUsbConnection>(conn)

        bridge.usbConnection = conn
        bridge.usbOutEndpoint = ep

        val report = DualSense.buildOutputReport(
            leftMotor = 200,
            rightMotor = 100,
            r = 0,
            g = 255,
            b = 255
        )

        val handled = bridge.dispatchOutputReport(report)
        assertTrue(handled)
        assertEquals(ep, shadowConn.lastBulkEndpoint)
        assertNotNull(shadowConn.lastBulkBuffer)
        assertEquals(48, shadowConn.lastBulkLength)
        assertTrue(report.contentEquals(shadowConn.lastBulkBuffer!!))
    }

    @Test
    fun testForwardingToUsbControlTransferDirect() {
        val conn = Shadow.newInstanceOf(UsbDeviceConnection::class.java)
        val intf = Shadow.newInstanceOf(UsbInterface::class.java)
        ReflectionHelpers.setField(intf, "mId", 3)
        val shadowConn = Shadow.extract<ShadowUsbConnection>(conn)

        bridge.usbConnection = conn
        bridge.usbOutEndpoint = null
        bridge.usbInterface = intf

        val report = DualSense.buildOutputReport(
            leftMotor = 150,
            rightMotor = 75,
            r = 100,
            g = 150,
            b = 200
        )

        val handled = bridge.dispatchOutputReport(report)
        assertTrue(handled)
        assertEquals(0x21, shadowConn.lastControlRequestType)
        assertEquals(0x09, shadowConn.lastControlRequest)
        assertEquals(0x0202, shadowConn.lastControlValue)
        assertEquals(3, shadowConn.lastControlIndex)
        assertNotNull(shadowConn.lastControlBuffer)
        assertTrue(report.contentEquals(shadowConn.lastControlBuffer!!))
    }

    @Test
    fun testForwardingThroughDualSenseManagerUsb() {
        val manager = DualSenseManager(context)
        DualSenseManager.activeManager = manager

        val conn = Shadow.newInstanceOf(UsbDeviceConnection::class.java)
        val ep = Shadow.newInstanceOf(UsbEndpoint::class.java)
        val shadowConn = Shadow.extract<ShadowUsbConnection>(conn)

        manager.usbConnection = conn
        manager.usbEndpointOut = ep

        val report = DualSense.buildOutputReport(r = 50, g = 60, b = 70)
        val handled = bridge.dispatchOutputReport(report)
        assertTrue(handled)
        assertEquals(48, shadowConn.lastBulkLength)
        assertTrue(report.contentEquals(shadowConn.lastBulkBuffer!!))

        manager.stop()
    }

    // -------------------------------------------------------------------------
    // 5. Reading from ds_output.bin (Record header & raw formats)
    // -------------------------------------------------------------------------

    @Test
    fun testReadReportFromFileWith64ByteHeaderRecord() {
        val file = File(tempDir, DualSenseOutputBridge.FILE_NAME)
        val report = DualSense.buildOutputReport(
            leftMotor = 255,
            rightMotor = 128,
            r = 12,
            g = 34,
            b = 56
        )

        val record = ByteArray(64)
        val bb = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0, 42) // seq
        bb.putInt(4, 48) // copy_len
        System.arraycopy(report, 0, record, 8, 48)

        RandomAccessFile(file, "rw").use { raf ->
            raf.write(record)
        }

        val read = bridge.readReportFromFile(file)
        assertNotNull(read)
        assertEquals(48, read!!.size)
        assertTrue(report.contentEquals(read))

        // Poll method
        val outStream = ByteArrayOutputStream()
        bridge.hostBridgeOutputStream = outStream
        val polled = bridge.pollDsOutputFile()
        assertNotNull(polled)
        assertEquals(48, outStream.size())
        assertTrue(report.contentEquals(outStream.toByteArray()))
    }

    @Test
    fun testReadReportFromFileRaw48Bytes() {
        val file = File(tempDir, DualSenseOutputBridge.FILE_NAME)
        val report = DualSense.buildOutputReport(
            leftMotor = 80,
            rightMotor = 40,
            r = 200,
            g = 100,
            b = 50
        )

        RandomAccessFile(file, "rw").use { raf ->
            raf.write(report)
        }

        val read = bridge.readReportFromFile(file)
        assertNotNull(read)
        assertEquals(48, read!!.size)
        assertTrue(report.contentEquals(read))
    }

    @Test
    fun testReadReportFromCorruptOrShortFileReturnsNull() {
        val file = File(tempDir, "corrupt.bin")
        RandomAccessFile(file, "rw").use { raf ->
            raf.write(ByteArray(30)) // too short
        }
        assertNull(bridge.readReportFromFile(file))

        val nonExistent = File(tempDir, "does_not_exist.bin")
        assertNull(bridge.readReportFromFile(nonExistent))
    }

    // -------------------------------------------------------------------------
    // 6. Android 12+ (API 31+) InputDevice LightsManager & VibratorManager Fallback
    // -------------------------------------------------------------------------

    @Test
    fun testInputDeviceLightsManagerFallback() {
        val device = inputDevice(id = 15)
        var receivedReport: ByteArray? = null
        var receivedDevice: InputDevice? = null

        bridge.lightsDispatcher = { report, dev ->
            receivedReport = report
            receivedDevice = dev
            true
        }
        bridge.osInputDeviceId = 15

        val report = DualSense.buildOutputReport(
            r = 0,
            g = 128,
            b = 255
        )

        val handled = bridge.dispatchOutputReport(report)
        assertTrue(handled)
        assertNotNull(receivedReport)
        assertEquals(device.id, receivedDevice?.id)
        val enable2 = receivedReport!![2].toInt() and 0xFF
        assertTrue((enable2 and 0x04) != 0)
        assertEquals(0.toByte(), receivedReport!![45])
        assertEquals(128.toByte(), receivedReport!![46])
        assertEquals(255.toByte(), receivedReport!![47])
    }

    @Test
    fun testInputDeviceVibratorManagerFallbackRumble() {
        val device = inputDevice(id = 20)
        var lastRumbleLeft = -1
        var lastRumbleRight = -1
        var wasCancelled = false

        bridge.vibratorDispatcher = { report, dev ->
            val left = report[4].toInt() and 0xFF
            val right = report[3].toInt() and 0xFF
            if (left == 0 && right == 0) {
                wasCancelled = true
            } else {
                lastRumbleLeft = left
                lastRumbleRight = right
            }
            true
        }
        bridge.osInputDeviceId = 20

        val report = DualSense.buildOutputReport(
            leftMotor = 220,
            rightMotor = 110,
            updateLightbar = false
        )

        val handled = bridge.dispatchOutputReport(report)
        assertTrue(handled)
        assertEquals(220, lastRumbleLeft)
        assertEquals(110, lastRumbleRight)
        assertFalse(wasCancelled)

        // Zero rumble triggers cancel()
        val stopReport = DualSense.buildOutputReport(
            leftMotor = 0,
            rightMotor = 0,
            updateLightbar = false
        )
        // Force valid_flag0 with rumble enabled but motors 0
        stopReport[1] = 0x03
        val stopHandled = bridge.dispatchOutputReport(stopReport)
        assertTrue(stopHandled)
        assertTrue(wasCancelled)
    }

    // -------------------------------------------------------------------------
    // 7. LocalServerSocket Lifecycle & Thread Verification
    // -------------------------------------------------------------------------

    @Test
    fun testLocalServerSocketLifecycle() {
        assertNull(DualSenseOutputBridge.activeBridge)
        bridge.start()
        assertEquals(bridge, DualSenseOutputBridge.activeBridge)

        bridge.stop()
        assertNull(DualSenseOutputBridge.activeBridge)
        assertTrue(TestableServerSocket.closeCalled)
    }

    @Test
    fun testLocalServerSocketReceivesAndDispatchesReport() {
        val outStream = ByteArrayOutputStream()
        bridge.hostBridgeOutputStream = outStream

        val report = DualSense.buildOutputReport(
            leftMotor = 77,
            rightMotor = 88,
            r = 99,
            g = 111,
            b = 222
        )

        TestableClientSocket.streamData = report
        TestableServerSocket.incomingSocket = Shadow.newInstanceOf(LocalSocket::class.java)

        bridge.start()

        // Wait up to 1 second for socket reader thread to dispatch
        val deadline = System.currentTimeMillis() + 1000
        while (System.currentTimeMillis() < deadline && outStream.size() < 48) {
            Thread.sleep(20)
        }

        assertEquals(48, outStream.size())
        assertTrue(report.contentEquals(outStream.toByteArray()))

        bridge.stop()
    }

    // -------------------------------------------------------------------------
    // 8. Empirical Stress & Concurrency Challenges (Challenger M2_2)
    // -------------------------------------------------------------------------

    @Test
    fun testHighRateConcurrentDispatching50Threads() {
        val totalThreads = 50
        val dispatchesPerThread = 100
        val receivedReports = Collections.synchronizedList(mutableListOf<ByteArray>())

        val stream = object : OutputStream() {
            override fun write(b: Int) {
                // Not used
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                val reportCopy = b.copyOfRange(off, off + len)
                receivedReports.add(reportCopy)
            }
        }
        bridge.hostBridgeOutputStream = stream

        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(totalThreads)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())

        for (i in 0 until totalThreads) {
            Thread {
                try {
                    startLatch.await()
                    for (j in 0 until dispatchesPerThread) {
                        val report = DualSense.buildOutputReport(
                            leftMotor = (i * 5) % 256,
                            rightMotor = (j * 2) % 256,
                            r = i % 256,
                            g = j % 256,
                            b = (i + j) % 256
                        )
                        val handled = bridge.dispatchOutputReport(report)
                        assertTrue(handled)
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    doneLatch.countDown()
                }
            }.start()
        }

        startLatch.countDown()
        assertTrue("Timed out waiting for 50 dispatch threads", doneLatch.await(10, TimeUnit.SECONDS))
        assertTrue("Expected 0 errors during concurrent dispatch: $errors", errors.isEmpty())
        assertEquals(totalThreads * dispatchesPerThread, receivedReports.size)
        for (report in receivedReports) {
            assertEquals(DualSense.REPORT_SIZE_OUTPUT, report.size)
            assertEquals(DualSense.REPORT_ID_OUTPUT, report[0])
        }
    }

    @Test
    fun testConcurrentDispatchToInputDevice50Threads() {
        inputDevice(id = 30)
        bridge.osInputDeviceId = 30

        val totalThreads = 50
        val dispatchesPerThread = 50
        val dispatchCount = AtomicInteger(0)

        bridge.lightsDispatcher = { _, _ ->
            dispatchCount.incrementAndGet()
            true
        }
        bridge.vibratorDispatcher = { _, _ ->
            true
        }

        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(totalThreads)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())

        for (i in 0 until totalThreads) {
            Thread {
                try {
                    startLatch.await()
                    for (j in 0 until dispatchesPerThread) {
                        val report = DualSense.buildOutputReport(
                            leftMotor = 100,
                            rightMotor = 50,
                            r = 10,
                            g = 20,
                            b = 30
                        )
                        bridge.dispatchOutputReport(report)
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    doneLatch.countDown()
                }
            }.start()
        }

        startLatch.countDown()
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS))
        assertTrue("Errors during concurrent input device dispatch: $errors", errors.isEmpty())
        assertEquals(totalThreads * dispatchesPerThread, dispatchCount.get())
    }

    @Test
    fun testConcurrentTcpStreamWriteVsClose() {
        val totalThreads = 25
        val closed = AtomicInteger(0)
        val mockStream = object : OutputStream() {
            @Volatile var isClosed = false
            override fun write(b: Int) {
                if (isClosed) throw IOException("Socket closed")
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (isClosed) throw IOException("Socket closed")
                Thread.sleep(1) // simulate network I/O
            }
            override fun close() {
                isClosed = true
                closed.incrementAndGet()
            }
        }
        bridge.hostBridgeOutputStream = mockStream

        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(totalThreads)
        val uncaughtErrors = Collections.synchronizedList(mutableListOf<Throwable>())

        for (i in 0 until totalThreads) {
            Thread {
                try {
                    startLatch.await()
                    for (j in 0 until 50) {
                        val report = DualSense.buildOutputReport(r = 1, g = 2, b = 3)
                        bridge.dispatchOutputReport(report)
                    }
                } catch (t: Throwable) {
                    uncaughtErrors.add(t)
                } finally {
                    doneLatch.countDown()
                }
            }.start()
        }

        startLatch.countDown()
        Thread.sleep(15)
        mockStream.close()

        assertTrue(doneLatch.await(10, TimeUnit.SECONDS))
        assertTrue("Uncaught exceptions during concurrent write vs close: $uncaughtErrors", uncaughtErrors.isEmpty())
        assertNull(bridge.hostBridgeOutputStream)
    }

    @Test
    fun testPollingFileWatcherRapidWritesAndDeletions() {
        val file = File(tempDir, DualSenseOutputBridge.FILE_NAME)
        bridge.start()

        val running = AtomicInteger(1)
        val writeCount = AtomicInteger(0)
        val deleteCount = AtomicInteger(0)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())

        // Writer thread
        val writer = Thread {
            var seq = 1
            while (running.get() == 1 && seq <= 100) {
                try {
                    val record = ByteArray(64)
                    val bb = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
                    bb.putInt(0, seq)
                    bb.putInt(4, 48)
                    val report = DualSense.buildOutputReport(r = seq % 256, g = 0, b = 0)
                    System.arraycopy(report, 0, record, 8, 48)
                    RandomAccessFile(file, "rw").use { raf ->
                        raf.setLength(0)
                        raf.write(record)
                    }
                    writeCount.incrementAndGet()
                    seq++
                    Thread.sleep(2)
                } catch (e: IOException) {
                    // Concurrent read/delete lock jitter is expected
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }

        // Deleter / truncater thread
        val deleter = Thread {
            while (running.get() == 1) {
                try {
                    Thread.sleep(15)
                    if (file.exists()) {
                        file.delete()
                        deleteCount.incrementAndGet()
                    }
                } catch (t: Throwable) {
                    // file deletion jitter is expected
                }
            }
        }

        writer.start()
        deleter.start()

        writer.join(5000)
        running.set(0)
        deleter.join(2000)

        bridge.stop()
        assertTrue("Writer errors: $errors", errors.isEmpty())
        assertTrue("Writes succeeded", writeCount.get() > 0)
    }

    @Test
    fun testCorruptPartialWriteRaceConditionInReadReportFromFile() {
        val file = File(tempDir, "partial_corrupt.bin")

        // Construct a 50-byte partial write of a 64-byte record where seq = 2 (low byte == 0x02):
        // Bytes 0..3: seq = 2
        // Bytes 4..7: copy_len = 48
        // Bytes 8..49: partial report bytes
        val partialRecord = ByteArray(50)
        val bb = ByteBuffer.wrap(partialRecord).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0, 2) // seq = 2 -> partialRecord[0] == 0x02 (REPORT_ID_OUTPUT)!
        bb.putInt(4, 48) // copy_len = 48
        for (i in 8 until 50) {
            partialRecord[i] = (i * 3).toByte()
        }
        partialRecord[8] = DualSense.REPORT_ID_OUTPUT

        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.write(partialRecord)
        }

        val result = bridge.readReportFromFile(file)
        assertNull("Partial 50-byte record must be rejected / ignored until complete", result)

        // Verify that once the full 64-byte record is written, it succeeds
        val fullRecord = ByteArray(64)
        System.arraycopy(partialRecord, 0, fullRecord, 0, 50)
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.write(fullRecord)
        }
        val fullResult = bridge.readReportFromFile(file)
        assertNotNull("Complete 64-byte record must be accepted", fullResult)
        assertEquals(48, fullResult!!.size)
    }

    @Test
    fun testLifecycleRacesRapidStartStop50Cycles() {
        for (i in 0 until 50) {
            val b = DualSenseOutputBridge()
            b.start()
            assertEquals(b, DualSenseOutputBridge.activeBridge)
            b.stop()
            assertNull(DualSenseOutputBridge.activeBridge)
        }
    }

    @Test
    fun testLifecycleRacesMultipleConcurrentStarts() {
        val totalThreads = 20
        val b = DualSenseOutputBridge()
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(totalThreads)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())

        for (i in 0 until totalThreads) {
            Thread {
                try {
                    startLatch.await()
                    b.start()
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    doneLatch.countDown()
                }
            }.start()
        }

        startLatch.countDown()
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS))
        assertTrue("Errors during concurrent start: $errors", errors.isEmpty())
        assertEquals(b, DualSenseOutputBridge.activeBridge)

        b.stop()
        assertNull(DualSenseOutputBridge.activeBridge)
    }

    @Test
    fun testServerThreadShutdownWithConnectedClient() {
        val blockingStream = object : InputStream() {
            @Volatile var isClosed = false
            override fun read(): Int {
                while (!isClosed) {
                    try {
                        Thread.sleep(20)
                    } catch (e: InterruptedException) {
                        // blocking stream simulates socket read wait
                    }
                }
                return -1
            }
            override fun close() {
                isClosed = true
            }
        }

        TestableClientSocket.customInputStream = blockingStream
        TestableServerSocket.incomingSocket = Shadow.newInstanceOf(LocalSocket::class.java)

        val b = DualSenseOutputBridge()
        b.start()

        val serverThreadField = DualSenseOutputBridge::class.java.getDeclaredField("serverThread")
        serverThreadField.isAccessible = true
        val thread = serverThreadField.get(b) as? Thread
        assertNotNull("Expected serverThread to be spawned", thread)
        assertTrue("Expected serverThread to be alive", thread!!.isAlive)

        Thread.sleep(50)

        // Stop the bridge: closes activeClientSocket, unblocking readFully and shutting down serverThread promptly
        b.stop()

        thread.join(1000)
        assertFalse("serverThread must terminate cleanly after stop() without socket leak", thread.isAlive)
        assertTrue("Client stream should be closed by bridge.stop()", blockingStream.isClosed)
    }

    // -------------------------------------------------------------------------
    // 9. Boundary & Error Handling Stress Tests (Challenger M2_1)
    // -------------------------------------------------------------------------

    @Test
    fun testBoundaryMalformedPacketsTruncatedAndEmpty() {
        val outStream = ByteArrayOutputStream()
        bridge.hostBridgeOutputStream = outStream

        // 1. Empty array (0 bytes)
        assertFalse(bridge.dispatchOutputReport(ByteArray(0)))

        // 2. 1-byte array with report ID 0x02
        assertFalse(bridge.dispatchOutputReport(byteArrayOf(0x02)))

        // 3. 10-byte array with report ID 0x02
        assertFalse(bridge.dispatchOutputReport(ByteArray(10).apply { this[0] = 0x02 }))

        // 4. 47-byte array (1 byte short of REPORT_SIZE)
        assertFalse(bridge.dispatchOutputReport(ByteArray(47).apply { this[0] = 0x02 }))

        // Verify nothing was written to output stream
        assertEquals(0, outStream.size())
    }

    @Test
    fun testBoundaryMalformedPacketsWrongReportIds() {
        val outStream = ByteArrayOutputStream()
        bridge.hostBridgeOutputStream = outStream

        val wrongIds = byteArrayOf(0x00, 0x01, 0x05, 0x31, 0x80.toByte(), 0xFF.toByte())
        for (id in wrongIds) {
            val report = ByteArray(48).apply { this[0] = id }
            assertFalse("Report with ID $id should be rejected", bridge.dispatchOutputReport(report))
        }
        assertEquals(0, outStream.size())
    }

    @Test
    fun testBoundaryOversizedArraysHandling() {
        val outStream = ByteArrayOutputStream()
        bridge.hostBridgeOutputStream = outStream

        val oversized49 = ByteArray(49).apply {
            this[0] = DualSense.REPORT_ID_OUTPUT
            this[48] = 0x7F
        }
        val oversized64 = ByteArray(64).apply {
            this[0] = DualSense.REPORT_ID_OUTPUT
            this[63] = 0x55
        }
        val oversized1024 = ByteArray(1024).apply {
            this[0] = DualSense.REPORT_ID_OUTPUT
        }

        // Remediated: Exact 48-byte validation strictly rejects packets with size != 48
        assertFalse("49-byte oversized report must be rejected", bridge.dispatchOutputReport(oversized49))
        assertEquals(0, outStream.size())

        assertFalse("64-byte oversized report must be rejected", bridge.dispatchOutputReport(oversized64))
        assertEquals(0, outStream.size())

        assertFalse("1024-byte oversized report must be rejected", bridge.dispatchOutputReport(oversized1024))
        assertEquals(0, outStream.size())
    }

    @Test
    fun testBrokenSocketClientDisconnectingMidReport() {
        val outStream = ByteArrayOutputStream()
        bridge.hostBridgeOutputStream = outStream

        // Client 1 sends only 15 bytes then disconnects (EOF)
        val partialReport = ByteArray(15) { (it + 1).toByte() }
        partialReport[0] = DualSense.REPORT_ID_OUTPUT

        val client1 = Shadow.newInstanceOf(LocalSocket::class.java)
        Shadow.extract<TestableClientSocket>(client1).instanceInputStream = ByteArrayInputStream(partialReport)

        // Client 2 sends a complete 48-byte report
        val validReport = DualSense.buildOutputReport(r = 50, g = 60, b = 70)
        val client2 = Shadow.newInstanceOf(LocalSocket::class.java)
        Shadow.extract<TestableClientSocket>(client2).instanceInputStream = ByteArrayInputStream(validReport)

        TestableServerSocket.incomingQueue.add(client1)
        TestableServerSocket.incomingQueue.add(client2)

        bridge.start()

        val deadline = System.currentTimeMillis() + 1500
        while (System.currentTimeMillis() < deadline && outStream.size() < 48) {
            Thread.sleep(20)
        }

        bridge.stop()

        // Verify: Client 1's partial report was NOT dispatched (no corruption)
        // Exactly 48 bytes from Client 2 were dispatched
        assertEquals(48, outStream.size())
        assertTrue(validReport.contentEquals(outStream.toByteArray()))
    }

    @Test
    fun testBrokenSocketRapidConnectDisconnectBursts() {
        val outStream = ByteArrayOutputStream()
        bridge.hostBridgeOutputStream = outStream

        val expectedReports = mutableListOf<ByteArray>()

        // 10 clients: burst of empty connects, partial connects, and valid reports
        for (i in 0 until 10) {
            val client = Shadow.newInstanceOf(LocalSocket::class.java)
            val stream: InputStream = when (i) {
                0, 3, 6, 9 -> ByteArrayInputStream(ByteArray(0)) // Immediate disconnect
                2, 5 -> ByteArrayInputStream(ByteArray(20) { 0x02 }) // Partial disconnect
                else -> {
                    val rep = DualSense.buildOutputReport(leftMotor = i * 20, r = i * 10)
                    expectedReports.add(rep)
                    ByteArrayInputStream(rep)
                }
            }
            Shadow.extract<TestableClientSocket>(client).instanceInputStream = stream
            TestableServerSocket.incomingQueue.add(client)
        }

        bridge.start()

        val expectedTotalBytes = expectedReports.size * 48
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline && outStream.size() < expectedTotalBytes) {
            Thread.sleep(25)
        }

        bridge.stop()

        assertEquals(expectedTotalBytes, outStream.size())
        val writtenBytes = outStream.toByteArray()
        for (idx in expectedReports.indices) {
            val chunk = writtenBytes.copyOfRange(idx * 48, (idx + 1) * 48)
            assertTrue("Report $idx matches", expectedReports[idx].contentEquals(chunk))
        }
    }

    @Test
    fun testBrokenSocketClientThrowingIoException() {
        val outStream = ByteArrayOutputStream()
        bridge.hostBridgeOutputStream = outStream

        val throwingStream = object : InputStream() {
            private var bytesRead = 0
            override fun read(): Int {
                if (bytesRead >= 10) {
                    throw IOException("Simulated network stream reset / connection reset by peer")
                }
                bytesRead++
                return 0x02
            }
        }

        val client1 = Shadow.newInstanceOf(LocalSocket::class.java)
        Shadow.extract<TestableClientSocket>(client1).instanceInputStream = throwingStream

        val validReport = DualSense.buildOutputReport(leftMotor = 120, rightMotor = 60)
        val client2 = Shadow.newInstanceOf(LocalSocket::class.java)
        Shadow.extract<TestableClientSocket>(client2).instanceInputStream = ByteArrayInputStream(validReport)

        TestableServerSocket.incomingQueue.add(client1)
        TestableServerSocket.incomingQueue.add(client2)

        bridge.start()

        val deadline = System.currentTimeMillis() + 1500
        while (System.currentTimeMillis() < deadline && outStream.size() < 48) {
            Thread.sleep(20)
        }

        bridge.stop()

        assertEquals(48, outStream.size())
        assertTrue(validReport.contentEquals(outStream.toByteArray()))
    }

    @Test
    fun testNullOutputStreamAndUsbDeviceConnectionHandling() {
        bridge.hostBridgeOutputStream = null
        bridge.usbConnection = null
        bridge.usbOutEndpoint = null
        bridge.usbInterface = null
        DualSenseManager.activeManager = null
        bridge.osInputDeviceId = -1

        val report = DualSense.buildOutputReport(r = 10, g = 20, b = 30)
        val handled = bridge.dispatchOutputReport(report)
        assertFalse("Must return false cleanly when all transports are null", handled)
    }

    @Test
    fun testThrowingHostBridgeOutputStreamGracefulFallback() {
        val throwingStream = object : OutputStream() {
            override fun write(b: Int) {
                throw IOException("Simulated broken TCP pipe to host bridge")
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                throw IOException("Simulated broken TCP pipe to host bridge")
            }
        }
        bridge.hostBridgeOutputStream = throwingStream

        // Configure USB fallback
        val conn = Shadow.newInstanceOf(UsbDeviceConnection::class.java)
        val ep = Shadow.newInstanceOf(UsbEndpoint::class.java)
        val shadowConn = Shadow.extract<ShadowUsbConnection>(conn)
        bridge.usbConnection = conn
        bridge.usbOutEndpoint = ep

        val report = DualSense.buildOutputReport(r = 15, g = 25, b = 35)

        val handled = bridge.dispatchOutputReport(report)
        assertTrue("Should succeed via USB fallback", handled)
        assertNull("Host bridge stream should be reset to null after throwing", bridge.hostBridgeOutputStream)
        assertEquals(ep, shadowConn.lastBulkEndpoint)
        assertEquals(48, shadowConn.lastBulkLength)
        assertTrue(report.contentEquals(shadowConn.lastBulkBuffer!!))
    }

    @Test
    fun testThrowingUsbDeviceConnectionBulkTransfer() {
        val conn = Shadow.newInstanceOf(UsbDeviceConnection::class.java)
        val ep = Shadow.newInstanceOf(UsbEndpoint::class.java)
        val shadowConn = Shadow.extract<ShadowUsbConnection>(conn)
        shadowConn.throwOnBulkTransfer = true

        bridge.hostBridgeOutputStream = null
        bridge.usbConnection = conn
        bridge.usbOutEndpoint = ep

        // Configure fallback InputDevice
        val dev = inputDevice(id = 45)
        bridge.osInputDeviceId = 45
        var fallbackReceived = false
        bridge.lightsDispatcher = { _, _ ->
            fallbackReceived = true
            true
        }

        val report = DualSense.buildOutputReport(r = 100, g = 150, b = 200)

        var thrownException: Throwable? = null
        val handled = try {
            bridge.dispatchOutputReport(report)
        } catch (t: Throwable) {
            thrownException = t
            false
        }

        // Remediated: UsbDeviceConnection exception must be caught and gracefully fall back to InputDevice
        assertNull("dispatchOutputReport must catch USB bulk transfer exception", thrownException)
        assertTrue("Output report should be handled by fallback InputDevice", handled)
        assertTrue("Fallback to InputDevice must be triggered when USB transfer fails", fallbackReceived)
        assertNull("usbConnection must be reset to null after USB transfer failure", bridge.usbConnection)
    }

    @Test
    fun testThrowingUsbDeviceConnectionControlTransfer() {
        val conn = Shadow.newInstanceOf(UsbDeviceConnection::class.java)
        val intf = Shadow.newInstanceOf(UsbInterface::class.java)
        ReflectionHelpers.setField(intf, "mId", 2)
        val shadowConn = Shadow.extract<ShadowUsbConnection>(conn)
        shadowConn.throwOnControlTransfer = true

        bridge.hostBridgeOutputStream = null
        bridge.usbConnection = conn
        bridge.usbOutEndpoint = null
        bridge.usbInterface = intf

        val dev = inputDevice(id = 46)
        bridge.osInputDeviceId = 46
        var fallbackReceived = false
        bridge.lightsDispatcher = { _, _ ->
            fallbackReceived = true
            true
        }

        val report = DualSense.buildOutputReport(r = 100, g = 150, b = 200)

        var thrownException: Throwable? = null
        val handled = try {
            bridge.dispatchOutputReport(report)
        } catch (t: Throwable) {
            thrownException = t
            false
        }

        // Remediated: Control transfer exception must be caught and gracefully fall back to InputDevice
        assertNull("dispatchOutputReport must catch USB control transfer exception", thrownException)
        assertTrue("Output report should be handled by fallback InputDevice", handled)
        assertTrue("Fallback to InputDevice must be triggered when USB control transfer fails", fallbackReceived)
        assertNull("usbConnection must be reset to null after USB control transfer failure", bridge.usbConnection)
    }

    @Test
    fun testThrowingUsbDeviceConnectionInDualSenseManagerHandledGracefully() {
        val manager = DualSenseManager(context)
        DualSenseManager.activeManager = manager

        val conn = Shadow.newInstanceOf(UsbDeviceConnection::class.java)
        val ep = Shadow.newInstanceOf(UsbEndpoint::class.java)
        val shadowConn = Shadow.extract<ShadowUsbConnection>(conn)
        shadowConn.throwOnBulkTransfer = true

        manager.usbConnection = conn
        manager.usbEndpointOut = ep

        val report = DualSense.buildOutputReport(r = 50, g = 60, b = 70)
        // DualSenseManager wraps bulkTransfer in try-catch and returns false
        val result = manager.sendOutputReport(report)
        assertFalse("DualSenseManager should catch exception and return false", result)

        manager.stop()
    }

    @Test
    fun testBoundaryExtremeRumbleValues() {
        val device = inputDevice(id = 50)
        bridge.osInputDeviceId = 50

        var lastLeft = -1
        var lastRight = -1
        var cancelCalled = false

        bridge.vibratorDispatcher = { rep, _ ->
            val left = rep[4].toInt() and 0xFF
            val right = rep[3].toInt() and 0xFF
            if (left == 0 && right == 0) {
                cancelCalled = true
            } else {
                lastLeft = left
                lastRight = right
            }
            true
        }

        // 1. Max rumble: left = 255, right = 255
        val maxReport = DualSense.buildOutputReport(leftMotor = 255, rightMotor = 255, updateLightbar = false)
        assertEquals((-1).toByte(), maxReport[3])
        assertEquals((-1).toByte(), maxReport[4])
        val maxHandled = bridge.dispatchOutputReport(maxReport)
        assertTrue(maxHandled)
        assertEquals(255, lastLeft)
        assertEquals(255, lastRight)

        // 2. Asymmetric rumble: left = 255, right = 0
        val leftOnlyReport = DualSense.buildOutputReport(leftMotor = 255, rightMotor = 0, updateLightbar = false)
        val leftHandled = bridge.dispatchOutputReport(leftOnlyReport)
        assertTrue(leftHandled)
        assertEquals(255, lastLeft)
        assertEquals(0, lastRight)

        // 3. Asymmetric rumble: left = 0, right = 255
        val rightOnlyReport = DualSense.buildOutputReport(leftMotor = 0, rightMotor = 255, updateLightbar = false)
        val rightHandled = bridge.dispatchOutputReport(rightOnlyReport)
        assertTrue(rightHandled)
        assertEquals(0, lastLeft)
        assertEquals(255, lastRight)

        // 4. Zero rumble: left = 0, right = 0 (with enable0 rumble bit set)
        val zeroReport = DualSense.buildOutputReport(leftMotor = 0, rightMotor = 0, updateLightbar = false).apply {
            this[1] = 0x03 // Force valid_flag0 rumble enabled
        }
        val zeroHandled = bridge.dispatchOutputReport(zeroReport)
        assertTrue(zeroHandled)
        assertTrue(cancelCalled)

        // 5. Raw negative byte inputs: -1 (0xFF = 255) and -128 (0x80 = 128)
        val rawNegReport = ByteArray(48).apply {
            this[0] = DualSense.REPORT_ID_OUTPUT
            this[1] = 0x03 // Rumble enable
            this[3] = (-1).toByte()   // 255 weak motor
            this[4] = (-128).toByte() // 128 strong motor
        }
        val rawHandled = bridge.dispatchOutputReport(rawNegReport)
        assertTrue(rawHandled)
        assertEquals(128, lastLeft)
        assertEquals(255, lastRight)
    }

    @Test
    fun testBoundaryExtremeLedValues() {
        val device = inputDevice(id = 55)
        bridge.osInputDeviceId = 55

        var lastColor = 0

        bridge.lightsDispatcher = { rep, _ ->
            val r = rep[45].toInt() and 0xFF
            val g = rep[46].toInt() and 0xFF
            val b = rep[47].toInt() and 0xFF
            lastColor = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            true
        }

        // 1. Full Black (0, 0, 0)
        val blackReport = DualSense.buildOutputReport(r = 0, g = 0, b = 0)
        assertTrue("AllowLedColor bit must be set for black lightbar", (blackReport[2].toInt() and 0x04) != 0)
        val blackHandled = bridge.dispatchOutputReport(blackReport)
        assertTrue(blackHandled)
        assertEquals("Black color must be 0xFF000000", 0xFF000000.toInt(), lastColor)

        // 2. Full White (255, 255, 255)
        val whiteReport = DualSense.buildOutputReport(r = 255, g = 255, b = 255)
        assertTrue("AllowLedColor bit must be set for white lightbar", (whiteReport[2].toInt() and 0x04) != 0)
        assertEquals((-1).toByte(), whiteReport[45])
        assertEquals((-1).toByte(), whiteReport[46])
        assertEquals((-1).toByte(), whiteReport[47])
        val whiteHandled = bridge.dispatchOutputReport(whiteReport)
        assertTrue(whiteHandled)
        assertEquals("White color must be 0xFFFFFFFF (-1)", -1, lastColor)

        // 3. Primary Red (255, 0, 0)
        val redReport = DualSense.buildOutputReport(r = 255, g = 0, b = 0)
        bridge.dispatchOutputReport(redReport)
        assertEquals("Red color must be 0xFFFF0000", 0xFFFF0000.toInt(), lastColor)

        // 4. Primary Green (0, 255, 0)
        val greenReport = DualSense.buildOutputReport(r = 0, g = 255, b = 0)
        bridge.dispatchOutputReport(greenReport)
        assertEquals("Green color must be 0xFF00FF00", 0xFF00FF00.toInt(), lastColor)

        // 5. Primary Blue (0, 0, 255)
        val blueReport = DualSense.buildOutputReport(r = 0, g = 0, b = 255)
        bridge.dispatchOutputReport(blueReport)
        assertEquals("Blue color must be 0xFF0000FF", 0xFF0000FF.toInt(), lastColor)

        // 6. Raw negative signed bytes: -128 (128) and -1 (255)
        val rawNegReport = ByteArray(48).apply {
            this[0] = DualSense.REPORT_ID_OUTPUT
            this[2] = 0x04 // AllowLedColor
            this[45] = (-1).toByte()   // 255
            this[46] = (-128).toByte() // 128
            this[47] = 0.toByte()      // 0
        }
        val rawHandled = bridge.dispatchOutputReport(rawNegReport)
        assertTrue(rawHandled)
        assertEquals("Color must convert negative bytes via unsigned masking", 0xFFFF8000.toInt(), lastColor)

        // 7. Mic LED extreme values
        val micMaxReport = DualSense.buildOutputReport(micLed = 255)
        assertEquals((-1).toByte(), micMaxReport[9])
        assertTrue((micMaxReport[2].toInt() and 0x01) != 0)

        // 8. Player LEDs extreme values
        val playerMaxReport = DualSense.buildOutputReport(playerLeds = 0xFF)
        assertEquals((-1).toByte(), playerMaxReport[44])
        assertTrue((playerMaxReport[2].toInt() and 0x10) != 0)
    }

    // -------------------------------------------------------------------------
    // 10. Milestone 2 Remediation Verification Tests
    // -------------------------------------------------------------------------

    @Test
    fun testRemediationExact48BytePacketValidation() {
        val outStream = ByteArrayOutputStream()
        bridge.hostBridgeOutputStream = outStream

        // Packets of incorrect lengths must be rejected
        val testSizes = listOf(0, 1, 16, 47, 49, 64, 78, 128)
        for (sz in testSizes) {
            val pkt = ByteArray(sz)
            if (sz > 0) pkt[0] = DualSense.REPORT_ID_OUTPUT
            assertFalse("Packet of size $sz must be rejected", bridge.dispatchOutputReport(pkt))
        }
        assertEquals("No invalid packets should be written", 0, outStream.size())

        // Exact 48-byte report with 0x02 must be accepted
        val valid48 = DualSense.buildOutputReport(r = 10, g = 20, b = 30)
        assertTrue("Exact 48-byte report must be accepted", bridge.dispatchOutputReport(valid48))
        assertEquals(48, outStream.size())
    }

    @Test
    fun testRemediationPartialFileLengthsRejected() {
        // Files with lengths in 49..55 must be rejected as partial records
        for (len in 49..55) {
            val file = File(tempDir, "partial_$len.bin")
            val data = ByteArray(len) { (it + 1).toByte() }
            data[0] = 0x02 // simulate seq low byte = 0x02
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0)
                raf.write(data)
            }
            assertNull("File of partial length $len must return null", bridge.readReportFromFile(file))
        }

        // File of exact length 48 must succeed as raw report
        val raw48File = File(tempDir, "raw_48.bin")
        val rawReport = DualSense.buildOutputReport(r = 5, g = 15, b = 25)
        RandomAccessFile(raw48File, "rw").use { raf ->
            raf.setLength(0)
            raf.write(rawReport)
        }
        val readRaw = bridge.readReportFromFile(raw48File)
        assertNotNull("Exact 48-byte raw report must succeed", readRaw)
        assertEquals(48, readRaw!!.size)
        assertTrue(rawReport.contentEquals(readRaw))
    }

    @Test
    fun testRemediationActiveClientSocketTrackedAndCleared() {
        val client = Shadow.newInstanceOf(LocalSocket::class.java)
        val validReport = DualSense.buildOutputReport(r = 1, g = 2, b = 3)
        Shadow.extract<TestableClientSocket>(client).instanceInputStream = ByteArrayInputStream(validReport)

        TestableServerSocket.incomingSocket = client

        val outStream = ByteArrayOutputStream()
        bridge.hostBridgeOutputStream = outStream

        bridge.start()

        val deadline = System.currentTimeMillis() + 1000
        while (System.currentTimeMillis() < deadline && outStream.size() < 48) {
            Thread.sleep(20)
        }

        bridge.stop()

        assertEquals(48, outStream.size())
        assertTrue(TestableClientSocket.closed)
    }

    // -------------------------------------------------------------------------
    // Shadows for Robolectric Testing
    // -------------------------------------------------------------------------

    @Implements(UsbDeviceConnection::class)
    class ShadowUsbConnection {
        var throwOnBulkTransfer = false
        var throwOnControlTransfer = false
        var lastBulkEndpoint: UsbEndpoint? = null
        var lastBulkBuffer: ByteArray? = null
        var lastBulkLength: Int = 0
        var lastControlRequestType: Int = 0
        var lastControlRequest: Int = 0
        var lastControlValue: Int = 0
        var lastControlIndex: Int = 0
        var lastControlBuffer: ByteArray? = null

        @Implementation
        fun bulkTransfer(endpoint: UsbEndpoint?, buffer: ByteArray?, length: Int, timeout: Int): Int {
            if (throwOnBulkTransfer) {
                throw RuntimeException("Simulated native USB bulk pipe broken")
            }
            lastBulkEndpoint = endpoint
            lastBulkBuffer = buffer?.copyOf(length)
            lastBulkLength = length
            return length
        }

        @Implementation
        fun controlTransfer(
            requestType: Int,
            request: Int,
            value: Int,
            index: Int,
            buffer: ByteArray?,
            length: Int,
            timeout: Int
        ): Int {
            if (throwOnControlTransfer) {
                throw RuntimeException("Simulated native USB control transfer broken")
            }
            lastControlRequestType = requestType
            lastControlRequest = request
            lastControlValue = value
            lastControlIndex = index
            lastControlBuffer = buffer?.copyOf(length)
            return length
        }
    }

    @Implements(LocalServerSocket::class)
    class TestableServerSocket {
        companion object {
            var incomingSocket: LocalSocket? = null
            val incomingQueue = java.util.concurrent.ConcurrentLinkedQueue<LocalSocket>()
            var closeCalled = false
            fun reset() {
                incomingSocket = null
                incomingQueue.clear()
                closeCalled = false
            }
        }

        @Implementation
        fun __constructor__(name: String) = Unit

        @Implementation
        fun accept(): LocalSocket {
            val queued = incomingQueue.poll()
            if (queued != null) return queued
            val s = incomingSocket
            if (s != null) {
                incomingSocket = null
                return s
            }
            throw IOException("Socket closed / no incoming socket")
        }

        @Implementation
        fun close() {
            closeCalled = true
        }
    }

    @Implements(LocalSocket::class)
    class TestableClientSocket {
        var instanceInputStream: InputStream? = null

        companion object {
            var streamData: ByteArray? = null
            var customInputStream: InputStream? = null
            var closed = false
            fun reset() {
                streamData = null
                customInputStream = null
                closed = false
            }
        }

        @Implementation
        fun getInputStream(): InputStream {
            val inst = instanceInputStream
            if (inst != null) return inst
            val custom = customInputStream
            if (custom != null) return custom
            val d = streamData ?: ByteArray(0)
            return ByteArrayInputStream(d)
        }

        @Implementation
        fun close() {
            closed = true
            try {
                instanceInputStream?.close()
                customInputStream?.close()
            } catch (_: Exception) {}
        }
    }
}

