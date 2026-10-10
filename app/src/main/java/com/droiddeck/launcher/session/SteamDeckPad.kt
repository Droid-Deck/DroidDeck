package com.droiddeck.launcher.session

import android.content.Context
import android.system.Os
import android.util.Log
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File

/**
 * The session's pad, described to the Steam client as a Steam Deck controller.
 *
 * On a Deck - and on the handhelds InputPlumber serves as one - the client finds the controller
 * through libudev as a `hidraw` device under a `hid` device under a USB interface and reads it
 * itself. libfakeinput serves the device node (`/dev/hidraw16`, built from the pad's input ring);
 * what the client also needs is the sysfs it walks to find and identify that node, which a sandbox
 * shows nothing of. This writes that sysfs as the kernel lays out a Deck's controller interface and
 * returns the binds that put it in place:
 *
 * - `/sys/devices/droiddeck/usb1` - the USB device (Valve, 28de:1205), its interface 2, the HID
 *   device on it (`HID_ID`, `report_descriptor`) and that device's `hidraw/hidraw16`;
 * - `/sys/class/hidraw/hidraw16` - what an enumeration of the `hidraw` subsystem lists;
 * - `/sys/dev/char/240:16` - how the node's device number is looked up (in the directory the
 *   runtime already binds there for the GPU);
 * - `/run/udev/data/c240:16` - the udev database entry that marks the device initialised, without
 *   which libudev's enumeration leaves a device node out;
 * - [listingDir] - stand-ins for the listings of `/sys`, `/sys/class` and `/sys/bus`. An app under
 *   an enforcing SELinux policy (every retail phone) may not list those directories, and libudev
 *   abandons its whole scan when it cannot, so the client never reached `hidraw`; libfakeinput
 *   lists these to the client instead when the real listing is refused (FAKE_DECK_SYSFS_LISTING).
 *
 * Symlinks are absolute guest paths; proot resolves them inside the session.
 */
object SteamDeckPad {
    private const val TAG = "SessionService"
    /** Must match libfakeinput (fakeinput_steam.cpp: DECK_HIDRAW_*). */
    private const val MAJOR = 240
    private const val MINOR = 16
    private const val NODE = "hidraw$MINOR"
    private const val GUEST_DEVICES = "/sys/devices/droiddeck"
    private const val USB = "$GUEST_DEVICES/usb1"
    private const val INTERFACE = "$USB/1-1:1.2"
    private const val HID = "$INTERFACE/0003:28DE:1205.0001"
    private const val HIDRAW = "$HID/hidraw/$NODE"
    private const val SERIAL = "DROIDDECK0001"

    // The paired Bluetooth Steam Controller is a separate Triton HID device. It must not inherit
    // the Deck's identity or report format. Must match fakeinput_steam.cpp (TRITON_HIDRAW_*).
    private const val TRITON_MINOR = 18
    private const val TRITON_NODE = "hidraw$TRITON_MINOR"
    private const val TRITON_HID = "$GUEST_DEVICES/bluetooth/0005:28DE:1303.0003"
    private const val TRITON_HIDRAW = "$TRITON_HID/hidraw/$TRITON_NODE"
    private const val TRITON_SERIAL = "DROIDDECKSC01"

    // Steam's touch controller (SteamTouchDevice), a second device beside the Deck's: 0000:11fb,
    // "Mobile Touch Control", on a USB device of its own. Must match libfakeinput (TOUCH_*).
    private const val TOUCH_MINOR = 17
    private const val TOUCH_NODE = "hidraw$TOUCH_MINOR"
    private const val TOUCH_USB = "$GUEST_DEVICES/usb2"
    private const val TOUCH_INTERFACE = "$TOUCH_USB/2-1:1.0"
    private const val TOUCH_HID = "$TOUCH_INTERFACE/0003:0000:11FB.0002"
    private const val TOUCH_HIDRAW = "$TOUCH_HID/hidraw/$TOUCH_NODE"
    private const val TOUCH_SERIAL = "DROIDDECK0001"

    /** libfakeinput's kTouchReportDescriptor: Generic Desktop / Game Pad, 40-byte input report. */
    private val TOUCH_REPORT_DESCRIPTOR = intArrayOf(
        0x05, 0x01, 0x09, 0x05, 0xa1, 0x01,
        0x09, 0x02, 0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x28, 0x81, 0x02,
        0x09, 0x03, 0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x40, 0x91, 0x02,
        0x09, 0x04, 0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x40, 0xb1, 0x02, 0xc0,
    ).map { it.toByte() }.toByteArray()

    /** InputPlumber's CONTROLLER_DESCRIPTOR, as libfakeinput answers HIDIOCGRDESC. */
    private val REPORT_DESCRIPTOR = intArrayOf(
        0x06, 0xff, 0xff, 0x09, 0x01, 0xa1, 0x01, 0x09, 0x02, 0x09, 0x03, 0x15, 0x00,
        0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x40, 0x81, 0x02, 0x09, 0x06, 0x09, 0x07,
        0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x40, 0xb1, 0x02, 0xc0,
    ).map { it.toByte() }.toByteArray()

    /** Triton's native 0x45/0x47 input and 0x80-0x85 haptic output report IDs. */
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

    /** Where [prepare] puts the stand-in listings; bound into the guest at the same path. */
    fun listingDir(sessionRoot: File) = File(sessionRoot, "sys/deck/listing")

    /** Writes the tree and returns the `host:guest` binds for it, or none if it could not be made. */
    fun prepare(context: Context, sessionRoot: File, touch: Boolean = false, triton: Boolean = false, deck: Boolean = true): List<String> {
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
            // Without the Deck (the pad is the Steam Controller), its device is never listed.
            if (deck) link(File(hidrawClass, NODE), HIDRAW)

            // Only the names matter: libudev reads a listing for the subsystems to descend into and
            // then opens /sys/class/hidraw itself, which is the bind above.
            val listing = listingDir(sessionRoot)
            for (name in listOf("sys/bus", "sys/class", "sys/devices", "class/hidraw", "bus")) File(listing, name).mkdirs()
            // The runtime binds this directory over /sys/dev/char for the GPU (LinuxRuntime.bindGpuNode).
            link(File(context.cacheDir, "drm/sys/$MAJOR:$MINOR"), HIDRAW)

            val udevData = File(LinuxRuntime.rootDir(context), "run/udev/data").apply { mkdirs() }
            File(udevData, "c$MAJOR:$MINOR").apply {
                if (deck) writeText("I:1\nE:ID_INPUT=1\nE:ID_INPUT_JOYSTICK=1\n") else delete()
            }

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
                link(File(context.cacheDir, "drm/sys/$MAJOR:$TOUCH_MINOR"), TOUCH_HIDRAW)
                File(udevData, "c$MAJOR:$TOUCH_MINOR").writeText("I:1\nE:ID_INPUT=1\nE:ID_INPUT_JOYSTICK=1\n")
                Log.i(TAG, "deck pad: /dev/$TOUCH_NODE described as Steam's touch controller (0000:11fb)")
            }

            if (triton) {
                write(TRITON_HID, "uevent", "DRIVER=hid-generic\nHID_ID=0005:000028DE:00001303\n" +
                    "HID_NAME=Valve Software Steam Controller\nHID_PHYS=bluetooth-droiddeck-1\n" +
                    "HID_UNIQ=$TRITON_SERIAL\nMODALIAS=hid:b0005g0001v000028DEp00001303\n")
                File(dir(TRITON_HID), "report_descriptor").writeBytes(TRITON_REPORT_DESCRIPTOR)
                link(File(dir(TRITON_HID), "subsystem"), "/sys/bus/hid")
                write(TRITON_HIDRAW, "uevent", "MAJOR=$MAJOR\nMINOR=$TRITON_MINOR\nDEVNAME=$TRITON_NODE\n")
                write(TRITON_HIDRAW, "dev", "$MAJOR:$TRITON_MINOR\n")
                link(File(dir(TRITON_HIDRAW), "subsystem"), "/sys/class/hidraw")
                link(File(dir(TRITON_HIDRAW), "device"), TRITON_HID)
                link(File(hidrawClass, TRITON_NODE), TRITON_HIDRAW)
                link(File(context.cacheDir, "drm/sys/$MAJOR:$TRITON_MINOR"), TRITON_HIDRAW)
                File(udevData, "c$MAJOR:$TRITON_MINOR").writeText("I:1\nE:ID_INPUT=1\nE:ID_INPUT_JOYSTICK=1\n")
                Log.i(TAG, "steam controller: /dev/$TRITON_NODE described as Triton (28de:1303)")
            }

            Log.i(TAG, "deck pad: /dev/$NODE described as a Steam Deck controller (28de:1205)")
            listOf(devices.path + ":" + GUEST_DEVICES, hidrawClass.path + ":/sys/class/hidraw")
        } catch (e: Exception) {
            Log.w(TAG, "deck pad: could not describe the pad as a Deck controller: $e")
            emptyList()
        }
    }
}
