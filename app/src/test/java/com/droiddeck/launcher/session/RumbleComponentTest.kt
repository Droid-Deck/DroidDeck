package com.droiddeck.launcher.session

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.Looper
import android.os.Vibrator
import android.os.VibratorManager
import com.droiddeck.launcher.input.ControllerPrefs
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

    private fun effect() {
        // Inject a decoded force-feedback packet; transport is outside this preference test.
        RumbleComponent::class.java.getDeclaredMethod("buzz", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(rumble, 65535, 5000)
    }

    @Implements(LocalServerSocket::class)
    class NoPacketsSocket {
        @Implementation fun __constructor__(name: String) = Unit
        @Implementation fun accept(): LocalSocket = throw IOException("No incoming packets")
        @Implementation fun close() = Unit
    }
}
