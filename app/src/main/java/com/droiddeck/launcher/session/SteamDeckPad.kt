package com.droiddeck.launcher.session

import android.content.Context
import android.system.Os
import android.util.Log
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File

object SteamDeckPad {
    private const val TAG = "SessionService"
    private const val MAJOR = 240
    private const val MINOR = 16
    private const val NODE = "hidraw$MINOR"
    private const val GUEST_DEVICES = "/sys/devices/droiddeck"
    private const val USB = "$GUEST_DEVICES/usb1"
    private const val INTERFACE = "$USB/1-1:1.2"
    private const val HID = "$INTERFACE/0003:28DE:1205.0001"
    private const val HIDRAW = "$HID/hidraw/$NODE"
    private const val SERIAL = "DROIDDECK0001"

    private const val TRITON_MINOR = 18
    private const val TRITON_HID = "$GUEST_DEVICES/bluetooth/0005:28DE:1303.0003"
    private const val TRITON_SERIAL = "DROIDDECKSC01"
    private const val TRITON_USB = "$GUEST_DEVICES/usb3"
    private const val TRITON_INTERFACE = "$TRITON_USB/3-1:1.0"
    private const val TRITON_WIRED_HID = "$TRITON_INTERFACE/0003:28DE:1302.0004"
    private const val SONY_DS4_MINOR = 20
    private const val SONY_DUALSENSE_MINOR = 21
    private const val SONY_DUALSENSE_EDGE_MINOR = 22
    private const val SC2015_MINOR = 23
    private val DS4_REPORT_DESCRIPTOR = intArrayOf(
        0x05, 0x01, 0x09, 0x05, 0xa1, 0x01, 0x06, 0x00, 0xff, 0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08,
        0x85, 0x01, 0x09, 0x20, 0x95, 0x3f, 0x81, 0x02, 0x85, 0x05, 0x09, 0x21, 0x95, 0x1f, 0x91, 0x02,
        0x85, 0x02, 0x09, 0x24, 0x95, 0x24, 0xb1, 0x02, 0x85, 0x12, 0x09, 0x25, 0x95, 0x0f, 0xb1, 0x02,
        0x85, 0xa3, 0x09, 0x26, 0x95, 0x30, 0xb1, 0x02, 0x85, 0x81, 0x09, 0x27, 0x95, 0x3f, 0xb1, 0x02,
        0xc0,
    ).map { it.toByte() }.toByteArray()
    private val DUALSENSE_REPORT_DESCRIPTOR = intArrayOf(
        0x05, 0x01, 0x09, 0x05, 0xa1, 0x01, 0x06, 0x00, 0xff, 0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08,
        0x85, 0x01, 0x09, 0x20, 0x95, 0x3f, 0x81, 0x02, 0x85, 0x02, 0x09, 0x21, 0x95, 0x2f, 0x91, 0x02,
        0x85, 0x05, 0x09, 0x22, 0x95, 0x28, 0xb1, 0x02, 0x85, 0x09, 0x09, 0x23, 0x95, 0x13, 0xb1, 0x02,
        0x85, 0x20, 0x09, 0x24, 0x95, 0x3f, 0xb1, 0x02, 0xc0,
    ).map { it.toByte() }.toByteArray()
    private const val TRITON_PAD_MINOR = 19

    private const val TOUCH_MINOR = 17
    private const val TOUCH_NODE = "hidraw$TOUCH_MINOR"
    private const val TOUCH_USB = "$GUEST_DEVICES/usb2"
    private const val TOUCH_INTERFACE = "$TOUCH_USB/2-1:1.0"
    private const val TOUCH_HID = "$TOUCH_INTERFACE/0003:0000:11FB.0002"
    private const val TOUCH_HIDRAW = "$TOUCH_HID/hidraw/$TOUCH_NODE"
    private const val TOUCH_SERIAL = "DROIDDECK0001"

    private val TOUCH_REPORT_DESCRIPTOR = intArrayOf(
        0x05, 0x01, 0x09, 0x05, 0xa1, 0x01,
        0x09, 0x02, 0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x28, 0x81, 0x02,
        0x09, 0x03, 0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x40, 0x91, 0x02,
        0x09, 0x04, 0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x40, 0xb1, 0x02, 0xc0,
    ).map { it.toByte() }.toByteArray()

    private val REPORT_DESCRIPTOR = intArrayOf(
        0x06, 0xff, 0xff, 0x09, 0x01, 0xa1, 0x01, 0x09, 0x02, 0x09, 0x03, 0x15, 0x00,
        0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x40, 0x81, 0x02, 0x09, 0x06, 0x09, 0x07,
        0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x40, 0xb1, 0x02, 0xc0,
    ).map { it.toByte() }.toByteArray()

    private val TRITON_REPORT_DESCRIPTOR = intArrayOf(
        0x06, 0x00, 0xff, 0x09, 0x01, 0xa1, 0x01,
        0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08,
        0x85, 0x45, 0x09, 0x02, 0x95, 0x2d, 0x81, 0x02,
        0x85, 0x47, 0x09, 0x03, 0x95, 0x2d, 0x81, 0x02,
        0x85, 0x80, 0x09, 0x04, 0x95, 0x09, 0x91, 0x02,
        0x85, 0x81, 0x09, 0x05, 0x95, 0x07, 0x91, 0x02,
        0x85, 0x82, 0x09, 0x06, 0x95, 0x03, 0x91, 0x02,
        0x85, 0x83, 0x09, 0x07, 0x95, 0x09, 0x91, 0x02,
        0x85, 0x84, 0x09, 0x08, 0x95, 0x08, 0x91, 0x02,
        0x85, 0x85, 0x09, 0x09, 0x95, 0x03, 0x91, 0x02,
        0x85, 0x01, 0x09, 0x0a, 0x95, 0x3f, 0xb1, 0x02,
        0xc0,
    ).map { it.toByte() }.toByteArray()

    private val TRITON_WIRED_REPORT_DESCRIPTOR = TRITON_REPORT_DESCRIPTOR.copyOf().also { it[15] = 0x42 }

    fun listingDir(sessionRoot: File) = File(sessionRoot, "sys/deck/listing")

    // sdl3bridge: where the session sysfs lives, for HookupBridge (Steam Native Hook-up) hot-plug
    @Volatile @JvmStatic var sysDevicesDir: File? = null
    @Volatile @JvmStatic var sysHidrawClassDir: File? = null
    @Volatile @JvmStatic var sysContext: Context? = null
    private val padNodes = mutableMapOf<Int, String>()
    @Volatile private var hidrawClassDir: File? = null

    @Synchronized
    fun showPad(padType: Int) {
        val dir = hidrawClassDir ?: return
        val shown = when (padType) {
            com.droiddeck.launcher.input.FakeInputWriter.PAD_TYPE_DECK -> MINOR
            com.droiddeck.launcher.input.FakeInputWriter.PAD_TYPE_STEAM -> TRITON_PAD_MINOR
            com.droiddeck.launcher.input.FakeInputWriter.PAD_TYPE_DS4 -> SONY_DS4_MINOR
            com.droiddeck.launcher.input.FakeInputWriter.PAD_TYPE_DUALSENSE -> SONY_DUALSENSE_MINOR
            com.droiddeck.launcher.input.FakeInputWriter.PAD_TYPE_DUALSENSE_EDGE -> SONY_DUALSENSE_EDGE_MINOR
            com.droiddeck.launcher.input.FakeInputWriter.PAD_TYPE_STEAM_2015 -> SC2015_MINOR
            else -> -1
        }
        for ((minor, target) in padNodes) {
            val link = File(dir, "hidraw$minor")
            try {
                if (minor == shown) {
                    if (!link.exists()) { link.delete(); Os.symlink(target, link.path) }
                } else {
                    link.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "pad: could not list /dev/hidraw$minor: $e")
            }
        }
    }

    fun prepare(context: Context, sessionRoot: File, touch: Boolean = false, triton: Boolean = false,
                padType: Int = com.droiddeck.launcher.input.FakeInputWriter.PAD_TYPE_DECK): List<String> {
        val base = File(sessionRoot, "sys/deck")
        val devices = File(base, "devices")
        val hidrawClass = File(base, "class-hidraw")
        return try {
            FileUtils.clear(base)
            fun dir(guest: String) = File(devices, guest.removePrefix("$GUEST_DEVICES/")).apply { mkdirs() }
            fun write(guest: String, name: String, text: String) = File(dir(guest), name).writeText(text)
            fun link(file: File, target: String) {
                file.parentFile?.mkdirs()
                file.delete()
                Os.symlink(target, file.path)
            }

            write(USB, "uevent", "DEVTYPE=usb_device\nPRODUCT=28de/1205/100\nTYPE=0/0/0\nBUSNUM=001\nDEVNUM=002\n")
            write(USB, "idVendor", "28de\n")
            write(USB, "idProduct", "1205\n")
            write(USB, "bcdDevice", "0100\n")
            write(USB, "manufacturer", "Valve Software\n")
            write(USB, "product", "Steam Deck Controller\n")
            write(USB, "serial", "$SERIAL\n")
            link(File(dir(USB), "subsystem"), "/sys/bus/usb")

            write(INTERFACE, "uevent", "DEVTYPE=usb_interface\nPRODUCT=28de/1205/100\nINTERFACE=3/0/0\n")
            write(INTERFACE, "bInterfaceNumber", "02\n")
            write(INTERFACE, "bInterfaceClass", "03\n")
            link(File(dir(INTERFACE), "subsystem"), "/sys/bus/usb")

            write(HID, "uevent", "DRIVER=hid-steam\nHID_ID=0003:000028DE:00001205\n" +
                "HID_NAME=Valve Software Steam Deck Controller\nHID_PHYS=usb-droiddeck-1/input2\n" +
                "HID_UNIQ=$SERIAL\nMODALIAS=hid:b0003g0001v000028DEp00001205\n")
            File(dir(HID), "report_descriptor").writeBytes(REPORT_DESCRIPTOR)
            link(File(dir(HID), "subsystem"), "/sys/bus/hid")

            write(HIDRAW, "uevent", "MAJOR=$MAJOR\nMINOR=$MINOR\nDEVNAME=$NODE\n")
            write(HIDRAW, "dev", "$MAJOR:$MINOR\n")
            link(File(dir(HIDRAW), "subsystem"), "/sys/class/hidraw")
            link(File(dir(HIDRAW), "device"), HID)

            hidrawClass.mkdirs()
            padNodes.clear()
            padNodes[MINOR] = HIDRAW

            val listing = listingDir(sessionRoot)
            for (name in listOf("sys/bus", "sys/class", "sys/devices", "class/hidraw", "bus")) File(listing, name).mkdirs()
            link(File(LinuxRuntime.liveDir(context), "drm/sys/$MAJOR:$MINOR"), HIDRAW)

            val udevData = File(LinuxRuntime.rootDir(context), "run/udev/data").apply { mkdirs() }
            File(udevData, "c$MAJOR:$MINOR").writeText("I:1\nE:ID_INPUT=1\nE:ID_INPUT_JOYSTICK=1\n")

            if (touch) {
                write(TOUCH_USB, "uevent", "DEVTYPE=usb_device\nPRODUCT=0/11fb/0\nTYPE=0/0/0\nBUSNUM=002\nDEVNUM=002\n")
                write(TOUCH_USB, "idVendor", "0000\n")
                write(TOUCH_USB, "idProduct", "11fb\n")
                write(TOUCH_USB, "bcdDevice", "0000\n")
                write(TOUCH_USB, "manufacturer", "Valve Software\n")
                write(TOUCH_USB, "product", "Mobile Touch Control\n")
                write(TOUCH_USB, "serial", "$TOUCH_SERIAL\n")
                link(File(dir(TOUCH_USB), "subsystem"), "/sys/bus/usb")
                write(TOUCH_INTERFACE, "uevent", "DEVTYPE=usb_interface\nPRODUCT=0/11fb/0\nINTERFACE=3/0/0\n")
                write(TOUCH_INTERFACE, "bInterfaceNumber", "00\n")
                write(TOUCH_INTERFACE, "bInterfaceClass", "03\n")
                link(File(dir(TOUCH_INTERFACE), "subsystem"), "/sys/bus/usb")
                write(TOUCH_HID, "uevent", "DRIVER=hid-generic\nHID_ID=0003:00000000:000011FB\n" +
                    "HID_NAME=Mobile Touch Control\nHID_PHYS=usb-droiddeck-2/input0\n" +
                    "HID_UNIQ=$TOUCH_SERIAL\nMODALIAS=hid:b0003g0001v00000000p000011FB\n")
                File(dir(TOUCH_HID), "report_descriptor").writeBytes(TOUCH_REPORT_DESCRIPTOR)
                link(File(dir(TOUCH_HID), "subsystem"), "/sys/bus/hid")
                write(TOUCH_HIDRAW, "uevent", "MAJOR=$MAJOR\nMINOR=$TOUCH_MINOR\nDEVNAME=$TOUCH_NODE\n")
                write(TOUCH_HIDRAW, "dev", "$MAJOR:$TOUCH_MINOR\n")
                link(File(dir(TOUCH_HIDRAW), "subsystem"), "/sys/class/hidraw")
                link(File(dir(TOUCH_HIDRAW), "device"), TOUCH_HID)
                link(File(hidrawClass, TOUCH_NODE), TOUCH_HIDRAW)
                link(File(LinuxRuntime.liveDir(context), "drm/sys/$MAJOR:$TOUCH_MINOR"), TOUCH_HIDRAW)
                File(udevData, "c$MAJOR:$TOUCH_MINOR").writeText("I:1\nE:ID_INPUT=1\nE:ID_INPUT_JOYSTICK=1\n")
                Log.i(TAG, "deck pad: /dev/$TOUCH_NODE described as Steam's touch controller (0000:11fb)")
            }

            fun describeTriton(wired: Boolean) {
                val hid = if (wired) TRITON_WIRED_HID else TRITON_HID
                val product = if (wired) "1302" else "1303"
                val bus = if (wired) "0003" else "0005"
                val minor = if (wired) TRITON_PAD_MINOR else TRITON_MINOR
                val node = "hidraw$minor"
                if (wired) {
                    write(TRITON_USB, "uevent", "DEVTYPE=usb_device\nPRODUCT=28de/1302/100\nTYPE=0/0/0\nBUSNUM=003\nDEVNUM=002\n")
                    write(TRITON_USB, "idVendor", "28de\n")
                    write(TRITON_USB, "idProduct", "1302\n")
                    write(TRITON_USB, "bcdDevice", "0100\n")
                    write(TRITON_USB, "manufacturer", "Valve Software\n")
                    write(TRITON_USB, "product", "Steam Controller\n")
                    write(TRITON_USB, "serial", "$TRITON_SERIAL\n")
                    link(File(dir(TRITON_USB), "subsystem"), "/sys/bus/usb")
                    write(TRITON_INTERFACE, "uevent", "DEVTYPE=usb_interface\nPRODUCT=28de/1302/100\nINTERFACE=3/0/0\n")
                    write(TRITON_INTERFACE, "bInterfaceNumber", "00\n")
                    write(TRITON_INTERFACE, "bInterfaceClass", "03\n")
                    link(File(dir(TRITON_INTERFACE), "subsystem"), "/sys/bus/usb")
                }
                write(hid, "uevent", "DRIVER=hid-generic\nHID_ID=$bus:000028DE:0000$product\n" +
                    "HID_NAME=Valve Software Steam Controller\n" +
                    "HID_PHYS=${if (wired) "usb-droiddeck-3/input0" else "bluetooth-droiddeck-1"}\n" +
                    "HID_UNIQ=$TRITON_SERIAL\nMODALIAS=hid:b${bus}g0001v000028DEp0000$product\n")
                File(dir(hid), "report_descriptor").writeBytes(if (wired) TRITON_WIRED_REPORT_DESCRIPTOR else TRITON_REPORT_DESCRIPTOR)
                link(File(dir(hid), "subsystem"), "/sys/bus/hid")
                val hidraw = "$hid/hidraw/$node"
                write(hidraw, "uevent", "MAJOR=$MAJOR\nMINOR=$minor\nDEVNAME=$node\n")
                write(hidraw, "dev", "$MAJOR:$minor\n")
                link(File(dir(hidraw), "subsystem"), "/sys/class/hidraw")
                link(File(dir(hidraw), "device"), hid)
                if (wired) padNodes[minor] = hidraw else link(File(hidrawClass, node), hidraw)
                link(File(LinuxRuntime.liveDir(context), "drm/sys/$MAJOR:$minor"), hidraw)
                File(udevData, "c$MAJOR:$minor").writeText("I:1\nE:ID_INPUT=1\nE:ID_INPUT_JOYSTICK=1\n")
                Log.i(TAG, "steam controller: /dev/$node described as Triton (28de:$product)")
            }
            fun describeSony(bus: Int, product: String, minor: Int, name: String, sonyDescriptor: ByteArray) {
                val node = "hidraw$minor"
                val usb = "$GUEST_DEVICES/usb$bus"
                val iface = "$usb/$bus-1:1.3"
                val hid = "$iface/0003:054C:${product.uppercase()}.000${bus + 1}"
                write(usb, "uevent", "DEVTYPE=usb_device\nPRODUCT=54c/$product/100\nTYPE=0/0/0\nBUSNUM=00$bus\nDEVNUM=002\n")
                write(usb, "idVendor", "054c\n")
                write(usb, "idProduct", "$product\n")
                write(usb, "bcdDevice", "0100\n")
                write(usb, "manufacturer", "Sony Interactive Entertainment\n")
                write(usb, "product", "$name\n")
                link(File(dir(usb), "subsystem"), "/sys/bus/usb")
                write(iface, "uevent", "DEVTYPE=usb_interface\nPRODUCT=54c/$product/100\nINTERFACE=3/0/0\n")
                write(iface, "bInterfaceNumber", "03\n")
                write(iface, "bInterfaceClass", "03\n")
                link(File(dir(iface), "subsystem"), "/sys/bus/usb")
                write(hid, "uevent", "DRIVER=hid-generic\nHID_ID=0003:0000054C:0000${product.uppercase()}\n" +
                    "HID_NAME=Sony Interactive Entertainment $name\nHID_PHYS=usb-droiddeck-$bus/input3\n" +
                    "HID_UNIQ=5e:43:4b:44:44:02\nMODALIAS=hid:b0003g0001v0000054Cp0000${product.uppercase()}\n")
                File(dir(hid), "report_descriptor").writeBytes(sonyDescriptor)
                link(File(dir(hid), "subsystem"), "/sys/bus/hid")
                val hidraw = "$hid/hidraw/$node"
                write(hidraw, "uevent", "MAJOR=$MAJOR\nMINOR=$minor\nDEVNAME=$node\n")
                write(hidraw, "dev", "$MAJOR:$minor\n")
                link(File(dir(hidraw), "subsystem"), "/sys/class/hidraw")
                link(File(dir(hidraw), "device"), hid)
                padNodes[minor] = hidraw
                link(File(LinuxRuntime.liveDir(context), "drm/sys/$MAJOR:$minor"), hidraw)
                File(udevData, "c$MAJOR:$minor").writeText("I:1\nE:ID_INPUT=1\nE:ID_INPUT_JOYSTICK=1\n")
                Log.i(TAG, "playstation pad: /dev/$node described as 054c:$product")
            }
            describeSony(4, "09cc", SONY_DS4_MINOR, "Wireless Controller", DS4_REPORT_DESCRIPTOR)
            describeSony(5, "0ce6", SONY_DUALSENSE_MINOR, "DualSense Wireless Controller", DUALSENSE_REPORT_DESCRIPTOR)
            describeSony(6, "0df2", SONY_DUALSENSE_EDGE_MINOR, "DualSense Edge Wireless Controller", DUALSENSE_REPORT_DESCRIPTOR)
            fun describeSteamController2015() {
                val usb = "$GUEST_DEVICES/usb7"
                val iface = "$usb/7-1:1.2"
                val hid = "$iface/0003:28DE:1102.0008"
                val node = "hidraw$SC2015_MINOR"
                write(usb, "uevent", "DEVTYPE=usb_device\nPRODUCT=28de/1102/100\nTYPE=0/0/0\nBUSNUM=007\nDEVNUM=002\n")
                write(usb, "idVendor", "28de\n")
                write(usb, "idProduct", "1102\n")
                write(usb, "bcdDevice", "0100\n")
                write(usb, "manufacturer", "Valve Software\n")
                write(usb, "product", "Steam Controller\n")
                write(usb, "serial", "DROIDDECKSC15\n")
                link(File(dir(usb), "subsystem"), "/sys/bus/usb")
                write(iface, "uevent", "DEVTYPE=usb_interface\nPRODUCT=28de/1102/100\nINTERFACE=3/0/0\n")
                write(iface, "bInterfaceNumber", "02\n")
                write(iface, "bInterfaceClass", "03\n")
                link(File(dir(iface), "subsystem"), "/sys/bus/usb")
                write(hid, "uevent", "DRIVER=hid-steam\nHID_ID=0003:000028DE:00001102\n" +
                    "HID_NAME=Valve Software Steam Controller\nHID_PHYS=usb-droiddeck-7/input2\n" +
                    "HID_UNIQ=DROIDDECKSC15\nMODALIAS=hid:b0003g0001v000028DEp00001102\n")
                File(dir(hid), "report_descriptor").writeBytes(REPORT_DESCRIPTOR)
                link(File(dir(hid), "subsystem"), "/sys/bus/hid")
                val hidraw = "$hid/hidraw/$node"
                write(hidraw, "uevent", "MAJOR=$MAJOR\nMINOR=$SC2015_MINOR\nDEVNAME=$node\n")
                write(hidraw, "dev", "$MAJOR:$SC2015_MINOR\n")
                link(File(dir(hidraw), "subsystem"), "/sys/class/hidraw")
                link(File(dir(hidraw), "device"), hid)
                padNodes[SC2015_MINOR] = hidraw
                link(File(LinuxRuntime.liveDir(context), "drm/sys/$MAJOR:$SC2015_MINOR"), hidraw)
                File(udevData, "c$MAJOR:$SC2015_MINOR").writeText("I:1\nE:ID_INPUT=1\nE:ID_INPUT_JOYSTICK=1\n")
                Log.i(TAG, "steam controller: /dev/$node described as the 2015 Steam Controller (28de:1102)")
            }
            describeSteamController2015()
            describeTriton(wired = true)
            if (triton) describeTriton(wired = false)
            hidrawClassDir = hidrawClass
            sysDevicesDir = devices
            sysHidrawClassDir = hidrawClass
            sysContext = context.applicationContext
            showPad(padType)

            Log.i(TAG, "deck pad: /dev/$NODE described as a Steam Deck controller (28de:1205)")
            listOf(devices.path + ":" + GUEST_DEVICES, hidrawClass.path + ":/sys/class/hidraw")
        } catch (e: Exception) {
            Log.w(TAG, "deck pad: could not describe the pad as a Deck controller: $e")
            emptyList()
        }
    }
}
