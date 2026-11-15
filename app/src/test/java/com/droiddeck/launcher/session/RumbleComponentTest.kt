package com.droiddeck.launcher.session

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.Looper
import android.os.Vibrator
import android.os.VibratorManager
import com.droiddeck.launcher.input.ControllerPrefs
import com.droiddeck.launcher.input.JoyCon
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
        rumble = RumbleComponent().also { it.motorLooper = Looper.getMainLooper(); it.attach(context); it.start() }
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

    @Test fun repeatedAndRapidEffectsDoNotRestartTheMotorsButTheLatestStillLands() {
        val pad = FakeMotors()
        rumble.controllerMotors = { pad }
        activeController(PAD_ID)
        repeat(5) { effect(strong = 40000, weak = 1000) }
        effect(strong = 20000, weak = 1000)
        effect(strong = 30000, weak = 1000)
        assertEquals("a stream of packets restarted the motor each time", 1, pad.played.size)
        idle(RumbleComponent.MIN_GAP_MS)
        assertEquals("the last change inside the gap was lost",
            listOf(Triple(40000, 1000, 5000L), Triple(30000, 1000, 5000L)), pad.played)
        effect(strong = 30000, weak = 1000)
        assertEquals("the same strength, still playing, was started again", 2, pad.played.size)
        effect(strong = 0, weak = 0)
        assertFalse(pad.vibrating)
        effect(strong = 20000, weak = 1000)
        assertTrue("an effect right after a stop was dropped", pad.vibrating)
    }

    @Test fun aHeldEffectKeepsTheMotorsRunningUntilTheGameStopsIt() {
        val pad = FakeMotors()
        rumble.controllerMotors = { pad }
        activeController(PAD_ID)
        effect(strong = 40000, weak = 20000, ms = RumbleComponent.HOLD_MS)
        assertEquals(listOf(Triple(40000, 20000, RumbleComponent.HOLD_EFFECT_MS)), pad.played)
        idle(RumbleComponent.KEEPALIVE_MS * 4 + 100)
        assertEquals("the held effect was not re-armed before it ran out", 5, pad.played.size)
        effect(strong = 40000, weak = 20000, ms = RumbleComponent.HOLD_MS)
        assertEquals("a repeat of the held strength restarted the motors", 5, pad.played.size)
        effect(strong = 0, weak = 0)
        assertFalse(pad.vibrating)
        idle(RumbleComponent.KEEPALIVE_MS * 4 + 100)
        assertEquals("the keepalive restarted the motors after a stop", 5, pad.played.size)
        assertFalse(pad.vibrating)
    }

    @Test fun quietingStopsAHeldEffectAndDropsEffectsUntilLifted() {
        val pad = FakeMotors()
        rumble.controllerMotors = { pad }
        activeController(PAD_ID)
        effect(strong = 40000, weak = 20000, ms = RumbleComponent.HOLD_MS)
        rumble.quiet(true)
        assertFalse(pad.vibrating)
        idle(RumbleComponent.KEEPALIVE_MS * 4 + 100)
        assertEquals("the keepalive restarted the motors while quiet", 1, pad.played.size)
        effect(strong = 40000, weak = 20000)
        assertFalse("an effect played while quiet", pad.vibrating)
        rumble.quiet(false)
        effect(strong = 40000, weak = 20000)
        assertTrue(pad.vibrating)
    }

    @Test fun aHeldEffectFollowsTheGamesNewStrength() {
        val pad = FakeMotors()
        rumble.controllerMotors = { pad }
        activeController(PAD_ID)
        effect(strong = 40000, weak = 0, ms = RumbleComponent.HOLD_MS)
        effect(strong = 10000, weak = 0, ms = RumbleComponent.HOLD_MS)
        idle(RumbleComponent.KEEPALIVE_MS * 2)
        assertTrue(pad.played.drop(1).isNotEmpty())
        assertTrue("the keepalive replayed an old strength", pad.played.drop(1).all { it.first == 10000 })
    }

    @Test fun aTimedEffectIsNotRearmed() {
        val pad = FakeMotors()
        rumble.controllerMotors = { pad }
        activeController(PAD_ID)
        effect(strong = 40000, weak = 0, ms = 300)
        idle(RumbleComponent.KEEPALIVE_MS * 3)
        assertEquals(listOf(Triple(40000, 0, 300L)), pad.played)
    }

    @Test fun disablingStopsTheKeepalive() {
        val pad = FakeMotors()
        rumble.controllerMotors = { pad }
        activeController(PAD_ID)
        effect(ms = RumbleComponent.HOLD_MS)
        ControllerPrefs.setRumble(context, false)
        idle(RumbleComponent.KEEPALIVE_MS * 3)
        assertEquals(1, pad.played.size)
        assertFalse(pad.vibrating)
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

    @Test fun eachPlayerSlotRumblesItsOwnControllerAndVirtualPadsUseTheLastActive() {
        val first = FakeMotors()
        val second = FakeMotors()
        rumble.controllerMotors = { id -> if (id == PAD_ID) first else if (id == SECOND_PAD_ID) second else null }
        activeController(PAD_ID)
        activeController(SECOND_PAD_ID, slot = 1)
        effect(strong = 100, weak = 0, slot = 1)
        assertEquals(listOf(Triple(100, 0, 5000L)), second.played)
        assertTrue(first.played.isEmpty())
        effect(strong = 200, weak = 0)
        assertEquals(listOf(Triple(200, 0, 5000L)), first.played)
        assertTrue("one player's effect ended another's", second.vibrating)
        effect(strong = 0, weak = 0, slot = 1)
        assertFalse(second.vibrating)
        assertTrue(first.vibrating)
        effect(strong = 300, weak = 0, slot = 16)
        assertEquals(Triple(300, 0, 5000L), second.played.last())
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test fun eachVrHandRumblesItsOwnJoyConAndNeverThePhone() {
        val left = FakeMotors()
        val right = FakeMotors()
        rumble.controllerMotors = { id -> if (id == PAD_ID) left else if (id == SECOND_PAD_ID) right else null }
        JoyCon.setVrDevices(this, intArrayOf(PAD_ID, SECOND_PAD_ID))
        effect(strong = 30000, weak = 30000, slot = JoyCon.VR_RUMBLE_SLOT + JoyCon.LEFT)
        effect(strong = 20000, weak = 20000, slot = JoyCon.VR_RUMBLE_SLOT + JoyCon.RIGHT)
        assertEquals(listOf(Triple(30000, 30000, 5000L)), left.played)
        assertEquals(listOf(Triple(20000, 20000, 5000L)), right.played)
        JoyCon.clearVrDevices(this)
        effect(strong = 10000, weak = 10000, slot = JoyCon.VR_RUMBLE_SLOT + JoyCon.LEFT)
        assertFalse("the hand kept rumbling after SteamVR let it go", left.vibrating)
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test fun aJoyConPairSplitsTheMotorsLikeOneController() {
        val left = FakeMotors()
        val right = FakeMotors()
        rumble.controllerMotors = { id -> if (id == PAD_ID) left else if (id == SECOND_PAD_ID) right else null }
        activeController(SECOND_PAD_ID)
        joyCons(0, PAD_ID, SECOND_PAD_ID)
        effect(strong = 40000, weak = 1000)
        assertEquals(listOf(Triple(40000, 0, 5000L)), left.played)
        assertEquals(listOf(Triple(0, 1000, 5000L)), right.played)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(RumbleComponent.MIN_GAP_MS))
        effect(strong = 20000, weak = 0)
        assertTrue(left.vibrating)
        assertFalse(right.vibrating)
        joyCons(0, PadBridge.NO_CONTROLLER, PadBridge.NO_CONTROLLER)
        effect(strong = 10000, weak = 10000)
        assertEquals("a lone Joy-Con missed the effect", Triple(10000, 10000, 5000L), right.played.last())
        assertFalse("the departed Joy-Con kept rumbling", left.vibrating)
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @After fun clearActiveController() {
        for (slot in 0 until PadBridge.SLOTS) {
            activeController(PadBridge.NO_CONTROLLER, slot)
            joyCons(slot, PadBridge.NO_CONTROLLER, PadBridge.NO_CONTROLLER)
        }
        JoyCon.clearVrDevices(this)
    }

    private fun joyCons(slot: Int, left: Int, right: Int) {
        val pairs = PadBridge::class.java.getDeclaredField("slotJoyCons").apply { isAccessible = true }.get(null)
        (pairs as java.util.concurrent.atomic.AtomicIntegerArray).set(slot * 2 + JoyCon.LEFT, left)
        pairs.set(slot * 2 + JoyCon.RIGHT, right)
    }

    private fun activeController(id: Int, slot: Int = 0) {
        PadBridge::class.java.getDeclaredField("activeControllerId").apply { isAccessible = true }.setInt(null, id)
        val slots = PadBridge::class.java.getDeclaredField("slotControllers").apply { isAccessible = true }.get(null)
        (slots as java.util.concurrent.atomic.AtomicIntegerArray).set(slot, id)
    }

    private fun effect(strong: Int = 65535, weak: Int = 65535, slot: Int = 0, ms: Int = 5000) {
        val int = Int::class.javaPrimitiveType
        RumbleComponent::class.java.getDeclaredMethod("buzz", int, int, int, int)
            .apply { isAccessible = true }.invoke(rumble, strong, weak, ms, slot)
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))

    private class FakeMotors : RumbleComponent.Motors {
        override val name = "fake pad"
        val played = mutableListOf<Triple<Int, Int, Long>>()
        var vibrating = false
        override fun play(strong: Int, weak: Int, ms: Long) { played += Triple(strong, weak, ms); vibrating = true }
        override fun cancel() { vibrating = false }
    }

    private companion object {
        const val PAD_ID = 7
        const val SECOND_PAD_ID = 8
    }

    @Implements(LocalServerSocket::class)
    class NoPacketsSocket {
        @Implementation fun __constructor__(name: String) = Unit
        @Implementation fun accept(): LocalSocket = throw IOException("No incoming packets")
        @Implementation fun close() = Unit
    }
}
