package com.droiddeck.launcher.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DualSenseAudioRoutingTest {
    private fun u(b: Byte) = b.toInt() and 0xFF

    @Test fun withoutHeadphonesTheRightChannelGoesToTheSpeakerAtFullVolume() {
        val r = DualSense.buildAudioRoutingReport(headphonesPlugged = false)
        assertEquals(DualSense.REPORT_SIZE_OUTPUT, r.size)
        assertEquals(0x02, u(r[0]))
        assertEquals("audio control + speaker volume valid", 0xA0, u(r[1]))
        assertEquals("audio_control2 valid", 0x80, u(r[2]))
        assertEquals(0x64, u(r[6]))
        assertEquals("output path 3: speaker gets the right channel", 0x30, u(r[8]))
        assertEquals("+6 dB speaker preamp", 0x02, u(r[38]))
    }

    @Test fun headphonesGetTheStereoPairAndNothingElseChanges() {
        val r = DualSense.buildAudioRoutingReport(headphonesPlugged = true)
        assertEquals(0x80, u(r[1]))
        assertEquals(0, u(r[2]))
        assertEquals(0, u(r[8]))
        assertEquals("speaker volume left alone", 0, u(r[6]))
    }

    @Test fun routingNeverTouchesRumbleLightsOrTriggers() {
        for (plugged in listOf(true, false)) {
            val r = DualSense.buildAudioRoutingReport(plugged)
            assertEquals("no rumble valid bits", 0, u(r[1]) and 0x03)
            assertEquals("no lightbar/LED valid bits", 0, u(r[2]) and 0x1F)
            assertTrue("trigger effects stay empty", r.copyOfRange(11, 33).all { it.toInt() == 0 })
            assertEquals(0, u(r[39]))
        }
    }

    @Test fun theHeadphoneJackIsReadFromStatusByteOne() {
        val report = ByteArray(DualSense.REPORT_SIZE_INPUT).apply {
            this[0] = DualSense.REPORT_ID_INPUT
            this[1] = 0x80.toByte(); this[2] = 0x80.toByte(); this[3] = 0x80.toByte(); this[4] = 0x80.toByte()
            this[8] = 0x08
        }
        val unplugged = DualSense.parseInputReport(report)
        assertNotNull(unplugged)
        assertFalse(unplugged!!.headphonesPlugged)
        report[54] = 0x01
        assertTrue(DualSense.parseInputReport(report)!!.headphonesPlugged)
        report[54] = 0x02
        assertFalse("a microphone alone is not headphones", DualSense.parseInputReport(report)!!.headphonesPlugged)
    }

    @Test fun preserveSpeakerRoutingKeepsSpeakerAliveWhenGameSendsZeroedAudioControl() {
        val gameReport = DualSense.buildOutputReport(leftMotor = 80, rightMotor = 40).apply {
            this[1] = (this[1].toInt() or 0x80).toByte()
            this[8] = 0x00
        }
        val sent = DualSense.preserveSpeakerRouting(gameReport)
        assertEquals(DualSense.REPORT_SIZE_OUTPUT, sent.size)
        assertEquals("speaker output path preserved", 0x30, u(sent[8]))
        assertEquals("speaker volume preserved", 0x64, u(sent[6]))
        assertEquals("speaker preamp preserved", 0x02, u(sent[38]) and 0x02)
        assertEquals("rumble preserved", 40, u(sent[3]))
        assertEquals("rumble preserved", 80, u(sent[4]))
    }
}
