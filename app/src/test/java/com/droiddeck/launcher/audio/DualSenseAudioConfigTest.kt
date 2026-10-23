package com.droiddeck.launcher.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DualSenseAudioConfigTest {
    @Test fun theDualSenseSinkIsOnlyLoadedWhenTheBetaGivesItAPipe() {
        val off = PulseAudioComponent.buildConfig("/pa/PS0", null, null)
        assertFalse(off.any { "DualSenseAudio" in it })
        assertEquals("set-default-sink DirectAudio", off.first { it.startsWith("set-default-sink") })

        val on = PulseAudioComponent.buildConfig("/pa/PS0", null, "/audio/dualsense.fifo")
        val sink = on.single { "DualSenseAudio" in it }
        assertTrue(sink.startsWith("load-module module-pipe-sink "))
        assertTrue("file=\"/audio/dualsense.fifo\"" in sink)
        assertTrue("format=s16le rate=48000 channels=4" in sink)
        assertTrue("channel_map=front-left,front-right,rear-left,rear-right" in sink)
        for (property in listOf("device.bus=usb", "device.vendor.id=054c", "device.product.id=0ce6", "device.description='Wireless Controller'")) {
            assertTrue(property, property in sink)
        }
        assertEquals("the game's own sound still defaults to DirectAudio", "set-default-sink DirectAudio",
            on.first { it.startsWith("set-default-sink") })
    }

    @Test fun aSinkThatFailsToLoadDoesNotStopTheDaemon() {
        val config = PulseAudioComponent.buildConfig("/pa/PS0", null, "/audio/dualsense.fifo")
        val sink = config.indexOfFirst { "DualSenseAudio" in it }
        assertTrue(config.indexOf(".nofail") < sink)
        assertTrue(sink < config.indexOf(".fail"))
    }

    @Test fun theMicrophonePipeIsUnchangedByTheDualSenseSink() {
        val config = PulseAudioComponent.buildConfig("/pa/PS0", "/audio/mic.fifo", "/audio/dualsense.fifo")
        assertTrue(config.any { it.startsWith("load-module module-pipe-source source_name=DirectAudioMic file=\"/audio/mic.fifo\"") })
        assertTrue("set-default-source DirectAudioMic" in config)
    }

    @Test fun onlyAWiredDualSenseIsUsedForItsAudio() {
        assertTrue(DualSenseAudioPump.isDualSenseOutput(AudioDeviceInfo.TYPE_USB_DEVICE, "Wireless Controller"))
        assertTrue(DualSenseAudioPump.isDualSenseOutput(AudioDeviceInfo.TYPE_USB_HEADSET, "DualSense Edge Wireless Controller"))
        assertFalse(DualSenseAudioPump.isDualSenseOutput(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Wireless Controller"))
        assertFalse(DualSenseAudioPump.isDualSenseOutput(AudioDeviceInfo.TYPE_USB_HEADSET, "USB-C Headphones"))
        assertFalse(DualSenseAudioPump.isDualSenseOutput(AudioDeviceInfo.TYPE_USB_DEVICE, null))
    }

    @Test fun aChunkIsTenMillisecondsOfFourChannel16BitAudio() {
        assertEquals(8, DualSenseAudioPump.FRAME_BYTES)
        assertEquals(480 * 8, DualSenseAudioPump.CHUNK_BYTES)
    }
}
