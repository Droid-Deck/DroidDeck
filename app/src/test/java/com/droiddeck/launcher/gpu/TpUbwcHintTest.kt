package com.droiddeck.launcher.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.droiddeck.launcher.session.SessionPrefs

class TpUbwcHintTest {
    @Test fun a740OnABlobV6xxTurnsTheHintOff() {
        assertEquals("enable_tp_ubwc_flag_hint=0", TpUbwcHint.features(740, "512.676.53"))
        assertEquals("enable_tp_ubwc_flag_hint=0", TpUbwcHint.features(740, "512.676.75"))
        assertEquals("enable_tp_ubwc_flag_hint=0", TpUbwcHint.features(740, "512.676"))
        assertEquals("enable_tp_ubwc_flag_hint=0", TpUbwcHint.features(740, "512.699.10"))
    }

    @Test fun a740OnThePocoBlobTurnsTheHintOn() {
        assertEquals("enable_tp_ubwc_flag_hint=1", TpUbwcHint.features(740, "512.676.76"))
        assertEquals("enable_tp_ubwc_flag_hint=1", TpUbwcHint.features(740, "512.676.80"))
    }

    @Test fun a740OnANewerBlobTurnsTheHintOn() {
        assertEquals("enable_tp_ubwc_flag_hint=1", TpUbwcHint.features(740, "512.700.0"))
        assertEquals("enable_tp_ubwc_flag_hint=1", TpUbwcHint.features(740, "512.762.10"))
    }

    @Test fun a740OnOneUiTurnsTheHintOnWhateverTheBlobVersion() {
        assertEquals("enable_tp_ubwc_flag_hint=1", TpUbwcHint.features(740, "512.676.53", samsung = true))
        assertEquals("enable_tp_ubwc_flag_hint=1", TpUbwcHint.features(740, null, samsung = true))
    }

    @Test fun onOverridesThePocoRuleEvenWhenTheVersionIsUnreadable() {
        for (version in listOf("512.676.1", "512.676.53", "garbage", null)) {
            assertEquals("enable_tp_ubwc_flag_hint=1", TpUbwcHint.features(740, version, mode = SessionPrefs.TP_UBWC_ON))
        }
    }

    @Test fun offOverridesSamsungAndNewerBlobs() {
        assertEquals("enable_tp_ubwc_flag_hint=0", TpUbwcHint.features(740, "512.762.10", mode = SessionPrefs.TP_UBWC_OFF))
        assertEquals("enable_tp_ubwc_flag_hint=0", TpUbwcHint.features(740, null, samsung = true, mode = SessionPrefs.TP_UBWC_OFF))
    }

    @Test fun overridesDoNotApplyToOtherGpus() {
        for (mode in listOf(SessionPrefs.TP_UBWC_ON, SessionPrefs.TP_UBWC_OFF)) {
            assertNull(TpUbwcHint.features(750, "512.762.10", mode = mode))
            assertNull(TpUbwcHint.features(0, null, mode = mode))
        }
    }

    @Test fun otherGpusKeepTheirDriverDefault() {
        assertNull(TpUbwcHint.features(750, "512.762.10"))
        assertNull(TpUbwcHint.features(830, "512.676.53"))
        assertNull(TpUbwcHint.features(750, "512.676.53", samsung = true))
    }

    @Test fun anUnreadableSystemDriverKeepsTheDriverDefault() {
        assertNull(TpUbwcHint.features(740, null))
        assertNull(TpUbwcHint.features(740, "garbage"))
    }
}
