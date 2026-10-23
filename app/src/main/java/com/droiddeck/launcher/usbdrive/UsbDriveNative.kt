package com.droiddeck.launcher.usbdrive

internal object UsbDriveNative {
    // The library is only built when libntfs-3g was prepared (tools/ntfs3g/build.sh).
    val available: Boolean by lazy { runCatching { System.loadLibrary("droiddeck_usbdrive") }.isSuccess }

    @JvmStatic external fun lastError(): String
    @JvmStatic external fun openDevice(fd: Int, iface: Int, epIn: Int, epOut: Int): Long
    @JvmStatic external fun closeDevice(handle: Long)
    @JvmStatic external fun reattachKernelDriver(fd: Int, iface: Int): Boolean
    @JvmStatic external fun describe(handle: Long): ByteArray?
    @JvmStatic external fun mount(handle: Long, start: Long, blocks: Long): Long
    @JvmStatic external fun unmount(volume: Long)
    @JvmStatic external fun volumeInfo(volume: Long): ByteArray?
    @JvmStatic external fun list(volume: Long, path: ByteArray): ByteArray?
    @JvmStatic external fun openFile(volume: Long, path: ByteArray): Long
    @JvmStatic external fun fileSize(file: Long): Long
    @JvmStatic external fun read(file: Long, offset: Long, buffer: ByteArray, start: Int, length: Int): Int
    @JvmStatic external fun closeFile(file: Long)
}
