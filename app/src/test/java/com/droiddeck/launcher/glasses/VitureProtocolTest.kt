package com.droiddeck.launcher.glasses

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin

class VitureProtocolTest {
    private fun u8(b: Byte) = b.toInt() and 0xFF
    private fun u16(b: ByteArray, at: Int) = u8(b[at]) or (u8(b[at + 1]) shl 8)

    @Test fun documentedProductNamesCanOfferImportWithoutInventingAProductId() {
        for (name in listOf("One", "One Lite", "Lite", "Pro", "Pro 2", "Luma", "Luma Pro", "Luma Cyber", "Luma Ultra", "Beast", "Phantom Beast", "Phantom")) {
            assertTrue(name, VitureProtocol.isGlassesProductName("VITURE $name"))
            assertTrue(name, VitureProtocol.isGlassesProductName("VITURE $name XR GLASSES"))
        }
        assertTrue(VitureProtocol.isGlassesProductName("  viture Phantom   Beast glasses  "))
        assertNull(VitureProtocol.model(0xffff, "VITURE Phantom Beast XR GLASSES"))
        assertTrue(VitureProtocol.isGlassesProductName("VITURE Phantom XR Glasses"))
        assertNull(VitureProtocol.model(0xffff, "VITURE Phantom XR Glasses"))
    }

    @Test fun accessoriesAndUnbrandedNamesDoNotOfferGlassesImport() {
        for (name in listOf(null, "", "VITURE", "Phantom Beast", "VITURE Microphone", "VITURE Camera", "VITURE Mobile Dock",
            "VITURE Luma Ultra Camera", "VITURE Phantom Beast Microphone", "VITURE Phantom Camera",
            "VITURE Phantom Microphone", "VITURE Phantom XR Dock", "VITURE Beast Dock", "VITURE Unknown XR Glasses")) {
            assertFalse(name, VitureProtocol.isGlassesProductName(name))
        }
    }

    @Test fun verifiedPhantomUsbIdentityUsesTheSdkDependentBeastProfile() {
        for (name in listOf(null, "VITURE Phantom XR Glasses")) {
            val phantom = VitureProtocol.model(0x1221, name)!!
            assertEquals("VITURE Phantom Beast", phantom.name)
            assertEquals(VitureProtocol.Family.BEAST, phantom.family)
            assertTrue(phantom.needsSdk)
            assertEquals(15, phantom.volumeMax)
            assertEquals(58f, phantom.diagonalFovDeg, 0f)
        }
        assertNull(VitureProtocol.model(0x1222, "VITURE Phantom XR Glasses"))
    }

    @Test fun oneAndLiteHardwareLimitsApplyBeforeAndAfterSdkImport() {
        val one = listOf("One").map { VitureProtocol.sdkModel(it)!! } +
            listOf(0x1011, 0x1013, 0x1017).map { VitureProtocol.model(it, null)!! }
        val lite = listOf(VitureProtocol.sdkModel("Lite")!!) +
            listOf(0x1015, 0x101b).map { VitureProtocol.model(it, null)!! }
        for (model in one + lite) {
            assertEquals(listOf(60), model.rates2d)
            assertEquals(6, model.brightnessMax)
            assertEquals(7, model.volumeMax)
            assertEquals(43f, model.diagonalFovDeg, 0f)
            for (hz in listOf(60, 90, 120)) {
                assertEquals(0x31, model.mode(hz, false))
                assertEquals(0x32, model.mode(hz, true))
            }
            for (step in 1..3) assertEquals(0x31, model.modeForStep(step))
            assertEquals(0x32, model.modeForStep(4))
        }
        one.forEach { assertTrue(it.hasSunblock); assertFalse(it.lite) }
        lite.forEach { assertFalse(it.hasSunblock); assertTrue(it.lite) }
        assertFalse(VitureProtocol.model(0x1015, "VITURE One XR Glasses")!!.hasSunblock)
    }

    @Test fun namedLumaModelsUseTheirDocumentedOptics() {
        for ((name, fov) in mapOf("Luma" to 50f, "Luma Pro" to 52f, "Luma Cyber" to 52f, "Luma Ultra" to 52f)) {
            val sdk = VitureProtocol.sdkModel(name)!!
            assertEquals(name, fov, sdk.diagonalFovDeg, 0f)
            assertEquals(listOf(60, 90, 120), sdk.rates2d)
            val pid = if (name == "Luma Ultra") 0x1104 else 0x1131
            assertEquals(name, fov, VitureProtocol.model(pid, "VITURE $name XR Glasses")!!.diagonalFovDeg, 0f)
        }
        assertEquals(52f, VitureProtocol.model(0x1101, null)!!.diagonalFovDeg, 0f)
    }

    @Test fun missingLumaProductNamesUsePidOpticsConsistentWithTheSdk() {
        for ((pid, marketName) in mapOf(0x1121 to "Luma Pro", 0x1141 to "Luma Pro", 0x1151 to "Luma Pro", 0x1131 to "Luma")) {
            val sdk = VitureProtocol.sdkModel(marketName)!!
            for (productName in listOf(null, "", "  ")) {
                val model = VitureProtocol.model(pid, productName)!!
                assertEquals(sdk.name, model.name)
                assertEquals("PID ${pid.toString(16)}", sdk.diagonalFovDeg, model.diagonalFovDeg, 0f)
            }
        }
    }

    @Test fun explicitLumaProductNamesKeepPriorityOverPidOptics() {
        assertEquals(50f, VitureProtocol.model(0x1121, "VITURE Luma XR Glasses")!!.diagonalFovDeg, 0f)
        assertEquals(52f, VitureProtocol.model(0x1131, "  viture Luma Pro XR Glasses  ")!!.diagonalFovDeg, 0f)
        assertEquals(52f, VitureProtocol.model(0x1131, "VITURE Luma Cyber XR Glasses")!!.diagonalFovDeg, 0f)
        assertEquals(50f, VitureProtocol.model(0x1121, "VITURE Unknown XR Glasses")!!.diagonalFovDeg, 0f)
    }

    @Test fun otherSdkModelLimitsRemainDistinct() {
        for (name in listOf("Pro", "Pro 2", "Luma", "Luma Pro", "Luma Cyber", "Luma Ultra", "Beast", "Phantom Beast")) {
            val model = VitureProtocol.sdkModel(name)!!
            assertEquals(name, 8, model.brightnessMax)
            assertEquals(name, if (name.endsWith("Beast")) 15 else 8, model.volumeMax)
            assertEquals(name, name != "Pro 2", model.hasSunblock)
        }
        for (name in listOf("Pro", "Pro 2")) {
            val model = VitureProtocol.sdkModel(name)!!
            assertEquals(listOf(60, 90, 120), model.rates2d)
            assertEquals(0x33, model.mode(90, false))
            assertEquals(0x34, model.mode(120, false))
        }
    }

    @Test fun crcIsXmodem() {
        val check = "123456789".toByteArray()
        assertEquals(0x31C3, VitureProtocol.crc16(check, 0, check.size))
    }

    @Test fun packetLayout() {
        val p = VitureProtocol.packet(VitureProtocol.MSG_BRIGHTNESS, byteArrayOf(5, 0))
        assertEquals(VitureProtocol.PACKET_SIZE, p.size)
        assertEquals(0xFF, u8(p[0]))
        assertEquals(VitureProtocol.MAGIC_COMMAND, u8(p[1]))
        assertEquals(2 + 0x0C, u16(p, 4))
        assertEquals(VitureProtocol.MSG_BRIGHTNESS, u16(p, 14))
        assertEquals(5, u8(p[18]))
        assertEquals(VitureProtocol.crc16(p, 4, u16(p, 4) + 2), u16(p, 2))
        for (i in 6 until 14) assertEquals(0, u8(p[i]))
    }

    @Test fun parseRoundTripsAndChecksCrc() {
        val p = VitureProtocol.packet(VitureProtocol.MSG_DISPLAY, byteArrayOf(0x34))
        val f = VitureProtocol.parse(p, p.size)
        assertNotNull(f)
        assertEquals(VitureProtocol.MAGIC_COMMAND, f!!.magic)
        assertEquals(VitureProtocol.MSG_DISPLAY, f.msgId)
        assertArrayEquals(byteArrayOf(0x34), f.payload)
        assertTrue(f.crcOk)
        p[18] = 0x31
        val badCrc = VitureProtocol.parse(p, p.size)!!
        assertFalse(badCrc.crcOk)
        assertArrayEquals(byteArrayOf(0x31), badCrc.payload)
    }

    @Test fun parseAcceptsPaddingOutsideDeclaredFrame() {
        val payload = byteArrayOf(0, 0x32)
        val p = VitureProtocol.packet(VitureProtocol.MSG_DISPLAY, payload)
        val end = 18 + payload.size
        p.fill(0x5A, end)
        for (size in listOf(end, p.size)) {
            val frame = VitureProtocol.parse(p, size)!!
            assertArrayEquals(payload, frame.payload)
            assertTrue(frame.crcOk)
        }
    }

    @Test fun parseRejectsTruncatedDeclaredFrame() {
        val p = VitureProtocol.packet(VitureProtocol.MSG_DISPLAY, byteArrayOf(0, 0x32))
        val received = 19
        assertNull(VitureProtocol.parse(p, received))
        assertNull(VitureProtocol.parse(p.copyOf(received), received))
        p[4] = 0xFF.toByte()
        p[5] = 0xFF.toByte()
        assertNull(VitureProtocol.parse(p, p.size))
    }

    @Test fun parseRejectsInvalidReceiveSize() {
        val p = VitureProtocol.packet(VitureProtocol.MSG_DISPLAY, byteArrayOf(0, 0x32))
        assertNull(VitureProtocol.parse(p, p.size + 1))
        assertNull(VitureProtocol.parse(p, 17))
        assertNull(VitureProtocol.parse(ByteArray(0), 18))
        assertNull(VitureProtocol.parse(ByteArray(0), 0))
        assertNull(VitureProtocol.parse(p, -1))
    }

    @Test fun parseReadsRepliesAndRejectsJunk() {
        val reply = VitureProtocol.packet(VitureProtocol.MSG_GET_VOLUME, byteArrayOf(0, 5))
        reply[1] = VitureProtocol.MAGIC_REPORT.toByte()
        val f = VitureProtocol.parse(reply, reply.size)!!
        assertEquals(VitureProtocol.MAGIC_REPORT, f.magic)
        assertArrayEquals(byteArrayOf(0, 5), f.payload)
        assertNull(VitureProtocol.parse(ByteArray(64), 64))
        assertNull(VitureProtocol.parse(reply, 8))
        val wrongMagic = reply.copyOf().also { it[1] = 0x10 }
        assertNull(VitureProtocol.parse(wrongMagic, wrongMagic.size))
    }

    @Test fun modelsAndModes() {
        val pro = VitureProtocol.model(0x101d, "VITURE Pro XR Glasses")!!
        assertEquals("VITURE Pro XR", pro.name)
        assertEquals(VitureProtocol.Family.PRO, pro.family)
        assertTrue(pro.controllable)
        assertFalse(pro.sbs90)
        assertEquals(0x34, pro.mode(120, false))
        assertEquals(0x33, pro.mode(90, false))
        assertEquals(0x31, pro.mode(60, false))
        assertEquals(0x32, pro.mode(90, true))
        assertEquals(0x32, pro.mode(60, true))

        val luma = VitureProtocol.model(0x1131, null)!!
        assertEquals("VITURE Luma", luma.name)
        assertFalse(luma.sbs90)
        assertEquals(0x44, luma.mode(120, false))
        assertEquals(0x42, luma.mode(90, true))
        assertEquals(0x42, luma.mode(60, true))

        val ultra = VitureProtocol.model(0x1101, null)!!
        assertTrue(ultra.sbs90)
        assertEquals(0x45, ultra.mode(90, true))
        assertEquals(0x42, ultra.mode(60, true))

        assertTrue(VitureProtocol.model(0x1201, null)!!.controllable)
        assertTrue(VitureProtocol.model(0x1201, null)!!.needsSdk)
        assertNull(VitureProtocol.model(0x9999, "VITURE"))
        assertEquals("VITURE One", VitureProtocol.model(0x1011, "Something else")!!.name)
    }

    @Test fun imuPrefersTheQuaternion() {
        val half = Math.toRadians(30.0) / 2
        val payload = ByteBuffer.allocate(40)
            .putFloat(1f).putFloat(2f).putFloat(3f)
            .putFloat(0f).putFloat(0f)
            .putFloat(cos(half).toFloat()).putFloat(0f).putFloat(0f).putFloat(sin(half).toFloat())
            .array()
        val q = VitureProtocol.imuQuaternion(payload)!!
        assertArrayEquals(floatArrayOf(0f, 0f, sin(half).toFloat(), cos(half).toFloat()), q, 1e-5f)
    }

    @Test fun directPro2KeepsSdkOnlyFamiliesAndUnknownProductsIsolated() {
        val direct = VitureProtocol.model(0x1301, "VITURE Luma Ultra")!!
        assertEquals("VITURE Pro 2", direct.name)
        assertEquals(VitureProtocol.Family.PRO2, direct.family)
        assertFalse(direct.needsSdk)
        assertFalse(direct.hardwareControls)
        assertFalse(direct.supportsDisplayModeControl)
        assertFalse(direct.hasSunblock)
        assertEquals(0, direct.brightnessMax)
        assertEquals(0, direct.volumeMax)

        for (pid in listOf(0x1101, 0x1104, 0x1201, 0x1211, 0x1221)) {
            val model = VitureProtocol.model(pid, null)!!
            assertTrue(model.needsSdk)
            assertTrue(model.hardwareControls)
            assertTrue(model.supportsDisplayModeControl)
        }
        assertNull(VitureProtocol.model(0x1102, "VITURE Pro 2"))
        assertNull(VitureProtocol.model(0x1302, "VITURE Pro 2"))
        val sdk = VitureProtocol.sdkModel("Pro 2")!!
        assertTrue(sdk.needsSdk)
        assertTrue(sdk.hardwareControls)
        assertTrue(sdk.supportsDisplayModeControl)
    }

    @Test fun imuFallsBackToEuler() {
        val payload = ByteBuffer.allocate(12).putFloat(0f).putFloat(0f).putFloat(30f).array()
        val q = VitureProtocol.imuQuaternion(payload)!!
        val half = Math.toRadians(30.0) / 2
        assertArrayEquals(floatArrayOf(0f, 0f, sin(half).toFloat(), cos(half).toFloat()), q, 1e-5f)
        assertNull(VitureProtocol.imuQuaternion(ByteArray(8)))
        val nan = ByteBuffer.allocate(12).putFloat(Float.NaN).putFloat(0f).putFloat(0f).array()
        assertNull(VitureProtocol.imuQuaternion(nan))
    }
}
