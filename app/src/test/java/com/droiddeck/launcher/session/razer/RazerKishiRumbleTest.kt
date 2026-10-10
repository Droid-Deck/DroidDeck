package com.droiddeck.launcher.session.razer

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class RazerKishiRumbleTest {
    @Test fun motorLevelsMapToTheKishisFullRangeWithoutAttenuation() {
        assertEquals(255, RazerV25.level(65535))
        assertEquals(128, RazerV25.level(32768))
        assertEquals(88, RazerV25.level(0x5800))
        assertEquals(1, RazerV25.level(1))
        assertEquals(0, RazerV25.level(0))
        assertEquals(255, RazerV25.level(70000))
    }

    @Test fun razerReportLayoutAndChecksum() {
        val args = RazerV25.legacyRumbleArgs(0xFF, 0x80)
        assertEquals(65, args.size)
        // chunk 1, then MSB-first bits: 7 zero, 8 zero, low 0xFF, high 0x80
        assertArrayEquals(byteArrayOf(1, 0, 0x01, 0xFF.toByte(), 0x00), args.copyOfRange(0, 5))
        val r = RazerV25.report(0x1F, 0x16, 0x0E, args)
        assertEquals(90, r.size)
        assertEquals(0x1F.toByte(), r[1]); assertEquals(65.toByte(), r[5])
        assertEquals(0x16.toByte(), r[6]); assertEquals(0x0E.toByte(), r[7])
        var x = 0; for (i in 2..87) x = x xor (r[i].toInt() and 0xFF)
        assertEquals(x.toByte(), r[88]); assertEquals(0.toByte(), r[89])
        assertArrayEquals(args, r.copyOfRange(8, 8 + 65))
        assertArrayEquals(byteArrayOf(1, 0, 0, 0, 0), RazerV25.legacyRumbleArgs(0, 0).copyOfRange(0, 5))
    }

    @Test fun onlyTheKishiIsKnownForUsbRumble() {
        for (pid in intArrayOf(0x0037, 0x0719, 0x0724, 0x0727, 0x0721, 0x071A, 0x0717, 0x0718)) assertNotNull(RazerKishiRumble.known(0x1532, pid))
        assertNull(RazerKishiRumble.known(0x045E, 0x028E))
        assertNull(RazerKishiRumble.known(0x1532, 0x0084))  // a Razer mouse: never asked for
    }

    // Interface layouts from a Kishi V3 Pro's dumpsys usb.
    private val xinput0037 = listOf(
        RazerKishiRumble.Iface(0, 255, 93, 1, null, 1, 1),                         // xpad: gamepad input
        RazerKishiRumble.Iface(1, 3, 0, 1, null, 1, 0),                             // usbhid: touch / keys
        RazerKishiRumble.Iface(2, 3, 1, 1, "Razer Notify (*)", 1, 0),
        RazerKishiRumble.Iface(3, 3, 0, 1, "Razer Protocol2.5", 0, 1),
        RazerKishiRumble.Iface(4, 3, 0, 0, "Razer HD Haptics Specifications", 1, 1),
    )
    private val hid0724 = listOf(
        RazerKishiRumble.Iface(0, 3, 1, 0, null, 1, 0),                             // usbhid: gamepad input
        RazerKishiRumble.Iface(1, 3, 0, 1, null, 1, 0),
        RazerKishiRumble.Iface(2, 3, 1, 1, "Razer Notify (*)", 1, 0),
        RazerKishiRumble.Iface(3, 3, 0, 1, "Razer Protocol2.5", 0, 1),
        RazerKishiRumble.Iface(4, 3, 0, 0, "Razer HD Haptics Specifications", 1, 1),
    )

    @Test fun protocol25IsChosenByNameInBothModesAndNeverAnInputInterface() {
        val pad = RazerKishiRumble.known(0x1532, 0x0724)!!
        for (layout in listOf(xinput0037, hid0724)) {
            assertEquals(3, layout[RazerKishiRumble.pick(layout, pad)!!.first].id)
            // Without interface names: Cortex's interface number, still output-only.
            val unnamed = layout.map { it.copy(name = null) }
            assertEquals(3, unnamed[RazerKishiRumble.pick(unnamed, pad)!!.first].id)
            // Even a mislabelled input interface is refused.
            val bogus = layout.map { if (it.id == 0) it.copy(name = "Razer Protocol2.5") else if (it.id == 3) it.copy(name = null) else it }
            assertEquals(3, bogus[RazerKishiRumble.pick(bogus, pad)!!.first].id)
            // Nothing output-only: nothing chosen.
            assertNull(RazerKishiRumble.pick(layout.filter { it.id != 3 }, pad))
        }
    }

    @Test fun theAppIsNeverAUsbAttachHandler() {
        // Being one makes Android route the pad to DroidDeck on plug-in, so its companion app
        // (Razer Cortex) no longer gets it; USB access is asked for at session start / test only.
        val manifest = java.io.File("src/main/AndroidManifest.xml").takeIf { it.exists() } ?: java.io.File("app/src/main/AndroidManifest.xml")
        assertFalse(manifest.readText().contains("USB_DEVICE_ATTACHED"))
    }

    @Test fun releasingWithNothingOpenIsHarmlessAndHoldsNothing() {
        RazerKishiRumble.releaseIfIdle("test")
        RazerKishiRumble.release("test")
        assertFalse(RazerKishiRumble.holdsInterface)
    }
}
