package com.droiddeck.launcher.input

import android.media.AudioManager
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33])
class SteamVolumeKeysTest {
    private val adjustments = mutableListOf<Int>()

    private fun key(code: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0) =
        KeyEvent(0L, 0L, action, code, repeat)

    private fun dispatch(code: Int, enabled: Boolean = true, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0) =
        SteamVolumeKeys.dispatch(key(code, action, repeat), enabled, adjustments::add)

    @Test fun enabledKeysAdjustOncePerDownAndConsumeRelease() {
        assertTrue(dispatch(KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(dispatch(KeyEvent.KEYCODE_VOLUME_UP, repeat = 1))
        assertTrue(dispatch(KeyEvent.KEYCODE_VOLUME_UP, action = KeyEvent.ACTION_UP))
        assertTrue(dispatch(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertTrue(dispatch(KeyEvent.KEYCODE_VOLUME_DOWN, action = KeyEvent.ACTION_UP))
        assertEquals(listOf(AudioManager.ADJUST_RAISE, AudioManager.ADJUST_RAISE, AudioManager.ADJUST_LOWER), adjustments)
    }

    @Test fun muteTogglesOnlyOnInitialPress() {
        assertTrue(dispatch(KeyEvent.KEYCODE_VOLUME_MUTE))
        assertTrue(dispatch(KeyEvent.KEYCODE_VOLUME_MUTE, repeat = 1))
        assertTrue(dispatch(KeyEvent.KEYCODE_VOLUME_MUTE, action = KeyEvent.ACTION_UP))
        assertEquals(listOf(AudioManager.ADJUST_TOGGLE_MUTE), adjustments)
    }

    @Test fun disabledAndNonVolumeKeysFallThrough() {
        for (code in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)) {
            assertFalse(dispatch(code, enabled = false))
            assertFalse(dispatch(code, enabled = false, action = KeyEvent.ACTION_UP))
        }
        assertFalse(dispatch(KeyEvent.KEYCODE_BUTTON_A))
        assertTrue(adjustments.isEmpty())
    }
}
