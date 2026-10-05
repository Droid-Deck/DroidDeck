package com.droiddeck.launcher.session

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.Looper
import android.os.Vibrator
import android.os.VibratorManager
import com.droiddeck.launcher.input.ControllerPrefs
import com.droiddeck.launcher.input.PadBridge
import java.io.IOException
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31], manifest = Config.NONE, shadows = [RumbleComponentTest.NoPacketsSocket::class])
class RumbleComponentTest {
    private lateinit var context: Context
    private lateinit var vibrator: Vibrator
    private lateinit var rumble: RumbleComponent

    @Before fun startListener() {
        context = RuntimeEnvironment.getApplication()
        ControllerPrefs.prefs(context).edit().clear().commit()
        vibrator = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java).defaultVibrator
            else context.getSystemService(Vibrator::class.java)
        shadowOf(vibrator).setHasVibrator(true)
        rumble = RumbleComponent().also { it.attach(context); it.start() }
    }

    @After fun stopListener() { rumble.stop() }

    @Test fun disablingCancelsCurrentEffectAndDropsSubsequentEffectsUntilEnabled() {
        effect()
        assertTrue(shadowOf(vibrator).isVibrating)
        ControllerPrefs.setRumble(context, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shadowOf(vibrator).isVibrating)
        effect()
        assertFalse(shadowOf(vibrator).isVibrating)
        ControllerPrefs.setRumble(context, true)
        shadowOf(Looper.getMainLooper()).idle()
        effect()
        assertTrue(shadowOf(vibrator).isVibrating)
        rumble.stop()
        assertFalse(shadowOf(vibrator).isVibrating)
        effect()
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test fun resettingControllerRestoresRumbleInAnExistingSession() {
        ControllerPrefs.setRumble(context, false)
        shadowOf(Looper.getMainLooper()).idle()
        effect()
        assertFalse(shadowOf(vibrator).isVibrating)
        ControllerPrefs.resetAll(context)
        shadowOf(Looper.getMainLooper()).idle()
        effect()
        assertTrue(shadowOf(vibrator).isVibrating)
    }

    @Test fun aControllerWithMotorsGetsTheEffectInsteadOfThePhone() {
        val pad = FakeMotors()
        rumble.controllerMotors = { id -> if (id == PAD_ID) pad else null }
        activeController(PAD_ID)
        effect(strong = 40000, weak = 1000)
        assertEquals(listOf(Triple(40000, 1000, 5000L)), pad.played)
        assertFalse("the phone buzzed as well", shadowOf(vibrator).isVibrating)
    }

    @Test fun aControllerWithoutMotorsFallsBackToThePhone() {
        rumble.controllerMotors = { null }
        activeController(PAD_ID)
        effect()
        assertTrue(shadowOf(vibrator).isVibrating)
    }

    @Test fun theOnScreenPadTakingOverMovesTheEffectBackToThePhone() {
        val pad = FakeMotors()
        rumble.controllerMotors = { pad }
        activeController(PAD_ID)
        effect()
        assertTrue(pad.vibrating)
        activeController(PadBridge.NO_CONTROLLER)
        effect()
        assertFalse("the controller kept rumbling after the switch", pad.vibrating)
        assertTrue(shadowOf(vibrator).isVibrating)
    }

    @Test fun aStopPacketDisablingAndStoppingAllEndTheControllersEffect() {
        val pad = FakeMotors()
        rumble.controllerMotors = { pad }
        activeController(PAD_ID)
        effect()
        effect(strong = 0, weak = 0)
        assertFalse(pad.vibrating)
        effect()
        ControllerPrefs.setRumble(context, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(pad.vibrating)
        ControllerPrefs.setRumble(context, true)
        shadowOf(Looper.getMainLooper()).idle()
        effect()
        rumble.stop()
        assertFalse(pad.vibrating)
    }

    @After fun clearActiveController() { activeController(PadBridge.NO_CONTROLLER) }

    private fun activeController(id: Int) {
        PadBridge::class.java.getDeclaredField("activeControllerId").apply { isAccessible = true }.setInt(null, id)
    }

    private fun effect(strong: Int = 65535, weak: Int = 65535) {
        // Inject a decoded force-feedback packet; transport is outside these tests.
        RumbleComponent::class.java.getDeclaredMethod("buzz", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(rumble, strong, weak, 5000)
    }

    private class FakeMotors : RumbleComponent.Motors {
        override val name = "fake pad"
        val played = mutableListOf<Triple<Int, Int, Long>>()
        var vibrating = false
        override fun play(strong: Int, weak: Int, ms: Long) { played += Triple(strong, weak, ms); vibrating = true }
        override fun cancel() { vibrating = false }
    }

    private companion object { const val PAD_ID = 7 }

    @Implements(LocalServerSocket::class)
    class NoPacketsSocket {
        @Implementation fun __constructor__(name: String) = Unit
        @Implementation fun accept(): LocalSocket = throw IOException("No incoming packets")
        @Implementation fun close() = Unit
    }
}
