package com.droiddeck.launcher.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TpUbwcHintTest {
    @Test fun a740OnABlobV6xxTurnsTheHintOff() {
        assertEquals("enable_tp_ubwc_flag_hint=0", TpUbwcHint.features(740, "512.676.53"))
    }

    @Test fun a740OnANewerBlobTurnsTheHintOn() {
        assertEquals("enable_tp_ubwc_flag_hint=1", TpUbwcHint.features(740, "512.762.10"))
    }

    @Test fun a740OnOneUiTurnsTheHintOnWhateverTheBlobVersion() {
        assertEquals("enable_tp_ubwc_flag_hint=1", TpUbwcHint.features(740, "512.676.53", samsung = true))
        assertEquals("enable_tp_ubwc_flag_hint=1", TpUbwcHint.features(740, null, samsung = true))
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
