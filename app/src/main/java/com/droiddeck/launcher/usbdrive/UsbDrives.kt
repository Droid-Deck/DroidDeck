package com.droiddeck.launcher.usbdrive

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import com.droiddeck.launcher.R
import java.io.File

internal object MassStorage {
    private const val SUBCLASS_SCSI = 0x06
    private const val PROTOCOL_BULK_ONLY = 0x50
    private const val PROTOCOL_UAS = 0x62

    data class Endpoints(val usbInterface: UsbInterface, val bulkIn: UsbEndpoint, val bulkOut: UsbEndpoint)

    fun isMassStorage(device: UsbDevice): Boolean =
        interfaces(device).any { it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE }

    fun select(device: UsbDevice): Endpoints? =
        interfaces(device)
            .filter {
                it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                    it.interfaceSubclass == SUBCLASS_SCSI &&
                    it.interfaceProtocol == PROTOCOL_BULK_ONLY
            }
            .firstNotNullOfOrNull(::bulkPair)

    fun onlyUas(device: UsbDevice): Boolean =
        select(device) == null && interfaces(device).any { it.interfaceProtocol == PROTOCOL_UAS }

    fun alternateSettings(device: UsbDevice, id: Int): Int = interfaces(device).count { it.id == id }

    private fun bulkPair(usbInterface: UsbInterface): Endpoints? {
        val bulk = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
            .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        val input = bulk.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: return null
        val output = bulk.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: return null
        return Endpoints(usbInterface, input, output)
    }

    private fun interfaces(device: UsbDevice): List<UsbInterface> =
        (0 until device.interfaceCount).map(device::getInterface)
}

object UsbDrives {
    private const val TAG = "UsbDrive"
    private const val ACTION_PERMISSION = "com.droiddeck.launcher.usbdrive.PERMISSION"

    val supported: Boolean get() = UsbDriveNative.available

    fun attached(context: Context): List<UsbDevice> {
        val usb = context.getSystemService(UsbManager::class.java) ?: return emptyList()
        return usb.deviceList.values.filter(MassStorage::isMassStorage).sortedBy { it.deviceName }
    }

    fun find(context: Context, deviceName: String): UsbDevice? =
        attached(context).firstOrNull { it.deviceName == deviceName }

    fun label(context: Context, device: UsbDevice): String {
        val product = runCatching { device.productName }.getOrNull()?.trim().orEmpty()
        val maker = runCatching { device.manufacturerName }.getOrNull()?.trim().orEmpty()
        val name = when {
            product.isEmpty() -> maker
            maker.isEmpty() || product.startsWith(maker, ignoreCase = true) -> product
            else -> "$maker $product"
        }
        return name.ifEmpty { context.getString(R.string.usbdrive_default_name) }
    }

    fun hasPermission(context: Context, device: UsbDevice): Boolean =
        context.getSystemService(UsbManager::class.java)?.hasPermission(device) == true

    fun requestPermission(context: Context, device: UsbDevice, onResult: (Boolean) -> Unit) {
        val app = context.applicationContext
        val usb = app.getSystemService(UsbManager::class.java) ?: return onResult(false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                runCatching { app.unregisterReceiver(this) }
                onResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        val filter = IntentFilter(ACTION_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(app, 0, Intent(ACTION_PERMISSION).setPackage(app.packageName), flags)
        runCatching { usb.requestPermission(device, pending) }.onFailure {
            runCatching { app.unregisterReceiver(receiver) }
            onResult(false)
        }
    }

    /**
     * Claiming a drive detaches Android's driver from the whole disk, taking down
     * any partition Android mounted from it. vold names partitions of USB disks
     * public:8,N (SD cards are major 179); the description check covers devices
     * whose app mount table hides /mnt/media_rw.
     */
    fun androidMayBeUsingUsbVolume(context: Context): Boolean {
        val mounted = runCatching {
            File("/proc/self/mounts").useLines { lines -> lines.any { it.startsWith("/dev/block/vold/public:8,") } }
        }.getOrDefault(false)
        if (mounted) return true
        val volumes = context.getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty()
        return volumes.any { volume ->
            volume.isRemovable &&
                (volume.state == Environment.MEDIA_MOUNTED || volume.state == Environment.MEDIA_MOUNTED_READ_ONLY) &&
                volume.getDescription(context)?.contains("USB", ignoreCase = true) == true
        }
    }

    /** Blocks on USB I/O; call off the main thread. */
    fun open(context: Context, device: UsbDevice): UsbDriveSession {
        if (!UsbDriveNative.available) throw UsbDriveException(context.getString(R.string.usbdrive_not_in_build))
        val endpoints = MassStorage.select(device) ?: throw UsbDriveException(
            context.getString(if (MassStorage.onlyUas(device)) R.string.usbdrive_uas_only else R.string.usbdrive_not_storage),
        )
        val usb = context.getSystemService(UsbManager::class.java)
            ?: throw UsbDriveException(context.getString(R.string.usbdrive_open_failed))
        val connection = usb.openDevice(device) ?: throw UsbDriveException(context.getString(R.string.usbdrive_open_failed))
        val iface = endpoints.usbInterface

        fun abandon(message: String): Nothing {
            connection.releaseInterface(iface)
            UsbDriveNative.reattachKernelDriver(connection.fileDescriptor, iface.id)
            connection.close()
            throw UsbDriveException(message)
        }

        if (!connection.claimInterface(iface, true)) {
            connection.close()
            throw UsbDriveException(context.getString(R.string.usbdrive_claim_failed))
        }
        // UAS-capable bridges (JMicron, ASMedia, Realtek) offer bulk-only and UAS
        // as alternate settings. Unbinding the kernel driver normally resets the
        // interface to the bulk-only one already, and a JMicron 152d:0580 reads
        // fine without this call, so a refusal is not fatal.
        if (MassStorage.alternateSettings(device, iface.id) > 1 && !connection.setInterface(iface)) {
            Log.w(TAG, "selecting the bulk-only alternate setting failed; trying the current one")
        }
        val handle = UsbDriveNative.openDevice(
            connection.fileDescriptor, iface.id, endpoints.bulkIn.address, endpoints.bulkOut.address,
        )
        if (handle == 0L) abandon(UsbDriveNative.lastError())
        val described = UsbDriveNative.describe(handle)
        if (described == null) {
            val error = UsbDriveNative.lastError()
            UsbDriveNative.closeDevice(handle)
            abandon(error)
        }
        return UsbDriveSession(device, UsbDriveJson.disk(described), connection, iface, handle)
    }
}
