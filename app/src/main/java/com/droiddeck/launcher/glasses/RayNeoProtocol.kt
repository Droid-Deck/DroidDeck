// Adapted from informalTechCode/RayNeo-Air-Series-3DoF-Scaffold (Apache-2.0).
// See docs/third-party/rayneo/ for license and protocol attribution.
package com.droiddeck.launcher.glasses

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.util.Arrays

private fun Byte.u(): Int = toInt() and 0xFF

data class RayNeoSensorSample(
    val accelMps2: FloatArray,
    val gyroDps: FloatArray,
    val temperatureC: Float,
    val magnet: FloatArray,
    val proximity: Float,
    val light: Float,
    val deviceTick100us: Long
)

data class RayNeoDeviceInfo(
    val boardId: Int,
    val sideBySideEnabled: Boolean
)

sealed interface RayNeoPacket {
    data class Sensor(val sample: RayNeoSensorSample) : RayNeoPacket
    data class Response(val cmd: Int, val raw: ByteArray) : RayNeoPacket
    data object Unknown : RayNeoPacket
}

data class RayNeoEndpointSelection(
    val usbInterface: UsbInterface,
    val inEndpoint: UsbEndpoint,
    val outEndpoint: UsbEndpoint
)

object RayNeoProtocol {
    const val VENDOR_ID = 0x1BBB
    const val PRODUCT_ID = 0xAF50
    // verncat/RayNeo-Air-3S-Pro-OpenVR PR #3 (MIT), tested on GT hardware.
    const val GT_VENDOR_ID = 0x3941
    private const val GT_CONTROL_INTERFACE = 5

    fun isGt(vid: Int, pid: Int) = vid == GT_VENDOR_ID && pid == PRODUCT_ID

    const val BOARD_AIR_4_PRO = 0x3A
    const val BOARD_GT = 0x40 // Observed on the user's non-Max GT.

    const val PKT_MAGIC = 0x99
    const val PKT_SENSOR = 0x65
    const val PKT_RESPONSE = 0xC8

    const val CMD_ACQUIRE_DEVICE_INFO = 0
    const val CMD_OPEN_IMU = 1
    const val CMD_CLOSE_IMU = 2
    const val CMD_SWITCH_TO_3D = 6
    const val CMD_SWITCH_TO_2D = 7

    private const val RESPONSE_CMD_OFFSET = 8

    private const val ACCEL_OFFSET = 4
    private const val GYRO_OFFSET = 16
    private const val TEMP_OFFSET = 28
    private const val MAG_X_OFFSET = 32
    private const val TICK_OFFSET = 40
    private const val PSENSOR_OFFSET = 44
    private const val LSENSOR_OFFSET = 48
    private const val MAG_Z_OFFSET = 52

    private const val DEVINFO_BOARD_ID_OFFSET = 21
    private const val DEVINFO_SIDE_BY_SIDE_OFFSET = 43

    fun buildCommandPacket(cmd: Int, arg: Int, packetSize: Int): ByteArray {
        val len = maxOf(packetSize, 64)
        val pkt = ByteArray(len)
        pkt[0] = 0x66
        pkt[1] = cmd.toByte()
        pkt[2] = arg.toByte()
        return pkt
    }

    fun classifyPacket(raw: ByteArray): RayNeoPacket {
        if (raw.size < 4 || raw[0].u() != PKT_MAGIC || raw[2].u() != raw.size) {
            return RayNeoPacket.Unknown
        }

        return when (raw[1].u()) {
            PKT_SENSOR -> {
                val sample = parseSensorSample(raw)
                if (sample != null) RayNeoPacket.Sensor(sample) else RayNeoPacket.Unknown
            }
            PKT_RESPONSE -> {
                if (raw.size <= RESPONSE_CMD_OFFSET) {
                    RayNeoPacket.Unknown
                } else {
                    RayNeoPacket.Response(raw[RESPONSE_CMD_OFFSET].u(), raw)
                }
            }
            else -> RayNeoPacket.Unknown
        }
    }

    fun parseDeviceInfo(raw: ByteArray): RayNeoDeviceInfo? {
        if (raw.size <= DEVINFO_SIDE_BY_SIDE_OFFSET || raw[2].u() != raw.size || raw[0].u() != PKT_MAGIC ||
            raw[1].u() != PKT_RESPONSE || raw[RESPONSE_CMD_OFFSET].u() != CMD_ACQUIRE_DEVICE_INFO) {
            return null
        }

        return RayNeoDeviceInfo(
            boardId = raw[DEVINFO_BOARD_ID_OFFSET].u(),
            sideBySideEnabled = raw[DEVINFO_SIDE_BY_SIDE_OFFSET].u() != 0
        )
    }

    fun selectEndpoints(device: UsbDevice): RayNeoEndpointSelection? {
        val gt = isGt(device.vendorId, device.productId)
        val gtCandidates = mutableListOf<RayNeoEndpointSelection>()
        var fallback: RayNeoEndpointSelection? = null

        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            var inEndpoint: UsbEndpoint? = null
            var outEndpoint: UsbEndpoint? = null

            for (j in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(j)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_INT &&
                    endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    continue
                }
                // Exclude small button reports from GT's protocol transport.
                if (gt && endpoint.maxPacketSize < 64) continue

                if (endpoint.direction == UsbConstants.USB_DIR_IN && inEndpoint == null) {
                    inEndpoint = endpoint
                } else if (endpoint.direction == UsbConstants.USB_DIR_OUT && outEndpoint == null) {
                    outEndpoint = endpoint
                }
            }

            if (inEndpoint != null && outEndpoint != null) {
                val selection = RayNeoEndpointSelection(usbInterface, inEndpoint, outEndpoint)
                if (gt) {
                    if (usbInterface.id == GT_CONTROL_INTERFACE) return selection
                    gtCandidates += selection
                    continue
                }
                if (inEndpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                    outEndpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT
                ) {
                    return selection
                }
                if (fallback == null) {
                    fallback = selection
                }
            }
        }

        // Interface numbers can change with firmware. Use a unique protocol-sized
        // pair when interface 5 is absent, never an arbitrary composite interface.
        return if (gt) gtCandidates.singleOrNull() else fallback
    }

    private fun parseSensorSample(raw: ByteArray): RayNeoSensorSample? {
        if (raw.size < MAG_Z_OFFSET + 4 ||
            (ACCEL_OFFSET until TEMP_OFFSET step 4).any { !readF32LE(raw, it).isFinite() }) {
            return null
        }

        return RayNeoSensorSample(
            accelMps2 = floatArrayOf(
                readF32LE(raw, ACCEL_OFFSET + 0),
                readF32LE(raw, ACCEL_OFFSET + 4),
                readF32LE(raw, ACCEL_OFFSET + 8)
            ),
            gyroDps = floatArrayOf(
                readF32LE(raw, GYRO_OFFSET + 0),
                readF32LE(raw, GYRO_OFFSET + 4),
                readF32LE(raw, GYRO_OFFSET + 8)
            ),
            temperatureC = readF32LE(raw, TEMP_OFFSET),
            magnet = floatArrayOf(
                readF32LE(raw, MAG_X_OFFSET),
                readF32LE(raw, MAG_X_OFFSET + 4),
                readF32LE(raw, MAG_Z_OFFSET)
            ),
            proximity = readF32LE(raw, PSENSOR_OFFSET),
            light = readF32LE(raw, LSENSOR_OFFSET),
            deviceTick100us = readU32LE(raw, TICK_OFFSET).toLong() and 0xffffffffL
        )
    }

    private fun readU32LE(raw: ByteArray, offset: Int): Int {
        val b0 = raw[offset].u()
        val b1 = raw[offset + 1].u()
        val b2 = raw[offset + 2].u()
        val b3 = raw[offset + 3].u()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun readF32LE(raw: ByteArray, offset: Int): Float {
        return Float.fromBits(readU32LE(raw, offset))
    }
}

class RayNeoPacketAssembler {
    private val stash = ByteArray(4096)
    private var len = 0

    fun append(data: ByteArray, count: Int) {
        require(count in 0..data.size)
        if (count <= 0) {
            return
        }

        if (count >= stash.size || len + count > stash.size) {
            len = 0
        }

        val copyLen = minOf(count, stash.size - len)
        System.arraycopy(data, 0, stash, len, copyLen)
        len += copyLen
    }

    fun nextPacket(): ByteArray? {
        var offset = 0
        while (offset + 4 <= len) {
            if (stash[offset].u() != RayNeoProtocol.PKT_MAGIC) {
                offset++
                continue
            }

            val packetLen = stash[offset + 2].u()
            if (packetLen < 4 || packetLen > 64 ||
                stash[offset + 1].u() !in listOf(RayNeoProtocol.PKT_SENSOR, RayNeoProtocol.PKT_RESPONSE)) {
                offset++
                continue
            }
            if (offset + packetLen > len) {
                break
            }

            val packet = Arrays.copyOfRange(stash, offset, offset + packetLen)
            compact(offset + packetLen)
            return packet
        }

        if (offset > 0) {
            compact(offset)
        }
        return null
    }

    private fun compact(consumed: Int) {
        if (consumed <= 0) {
            return
        }

        if (consumed >= len) {
            len = 0
            return
        }

        System.arraycopy(stash, consumed, stash, 0, len - consumed)
        len -= consumed
    }
}
