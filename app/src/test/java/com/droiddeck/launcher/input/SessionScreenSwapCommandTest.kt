package com.droiddeck.launcher.input

import android.os.Looper
import com.droiddeck.launcher.R
import com.droiddeck.launcher.SessionActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SessionScreenSwapCommandTest {
    @Test fun unavailableSwapExplainsWhyInsteadOfSilentlyIgnoringTheButton() {
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        ReflectionHelpers.getField<androidx.compose.runtime.MutableState<Boolean>>(activity, "resumed\$delegate").value = true
        val output: InputOutput = ReflectionHelpers.getField(activity, "inputOutput")
        output.command(InputCommand.SWAP_SCREENS, InputRole.SECONDARY)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(activity.getString(R.string.swap_screens_unavailable), ShadowToast.getTextOfLatestToast())
    }
}
