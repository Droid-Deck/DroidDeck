package com.droiddeck.launcher.usbdrive

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.util.Log
import java.io.Closeable
import java.io.IOException

class UsbDriveException(message: String) : IOException(message)

class UsbDriveSession internal constructor(
    val device: UsbDevice,
    val disk: UsbDiskInfo,
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private var handle: Long,
) : Closeable {
    private val lock = Any()
    private val volumes = mutableListOf<NtfsVolume>()

    val isOpen: Boolean get() = synchronized(lock) { handle != 0L }

    fun mount(partition: UsbPartition): NtfsVolume = synchronized(lock) {
        check(handle != 0L) { "drive closed" }
        if (!partition.isNtfs) throw UsbDriveException("partition ${partition.index} is not NTFS")
        val volume = UsbDriveNative.mount(handle, partition.start, partition.blocks)
        if (volume == 0L) throw UsbDriveException(UsbDriveNative.lastError())
        NtfsVolume(lock, volume, partition) { closed -> volumes.remove(closed) }.also { volumes += it }
    }

    override fun close() {
        synchronized(lock) {
            if (handle == 0L) return
            volumes.toList().forEach { it.close() }
            UsbDriveNative.closeDevice(handle)
            handle = 0L
            connection.releaseInterface(usbInterface)
            if (!UsbDriveNative.reattachKernelDriver(connection.fileDescriptor, usbInterface.id)) {
                Log.w(TAG, "${disk.name} stays disconnected from Android until it is replugged")
            }
            connection.close()
        }
    }

    private companion object {
        const val TAG = "UsbDrive"
    }
}

class NtfsVolume internal constructor(
    private val lock: Any,
    private var handle: Long,
    val partition: UsbPartition,
    private val onClose: (NtfsVolume) -> Unit,
) : Closeable, NtfsSource {

    fun info(): NtfsVolumeInfo = synchronized(lock) {
        val bytes = UsbDriveNative.volumeInfo(live()) ?: throw UsbDriveException(UsbDriveNative.lastError())
        UsbDriveJson.volume(bytes)
    }

    override fun list(path: String): List<NtfsEntry> = synchronized(lock) {
        val bytes = UsbDriveNative.list(live(), path.toByteArray(Charsets.UTF_8))
            ?: throw UsbDriveException(UsbDriveNative.lastError())
        UsbDriveJson.entries(bytes)
    }

    override fun open(path: String): NtfsFile = synchronized(lock) {
        val file = UsbDriveNative.openFile(live(), path.toByteArray(Charsets.UTF_8))
        if (file == 0L) throw UsbDriveException(UsbDriveNative.lastError())
        NtfsFile(lock, file, UsbDriveNative.fileSize(file))
    }

    override fun close() {
        synchronized(lock) {
            if (handle == 0L) return
            UsbDriveNative.unmount(handle)
            handle = 0L
            onClose(this)
        }
    }

    private fun live(): Long {
        if (handle == 0L) throw UsbDriveException("the drive was disconnected")
        return handle
    }
}

class NtfsFile internal constructor(
    private val lock: Any,
    private var handle: Long,
    override val size: Long,
) : NtfsReadable {

    /** Returns the number of bytes read, 0 at the end of the file. */
    override fun read(offset: Long, buffer: ByteArray, start: Int, length: Int): Int = synchronized(lock) {
        if (handle == 0L) throw UsbDriveException("file closed")
        val got = UsbDriveNative.read(handle, offset, buffer, start, length)
        if (got < 0) throw UsbDriveException(UsbDriveNative.lastError())
        got
    }

    override fun close() {
        synchronized(lock) {
            if (handle == 0L) return
            UsbDriveNative.closeFile(handle)
            handle = 0L
        }
    }
}
