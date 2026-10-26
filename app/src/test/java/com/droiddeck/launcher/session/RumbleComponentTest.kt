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
        rumble = RumbleComponent().also { it.providerMotors = { null }; it.motorLooper = Looper.getMainLooper(); it.attach(context); it.start() }
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

    @Test fun aStaleActiveIdFindsTheSamePadUnderItsNewId() {
        val pad = FakeMotors()
        rumble.controllerMotors = { null }
        rumble.otherControllerMotors = { skip, _, _ -> if (skip == PAD_ID) pad else null }
        activeController(PAD_ID)
        effect()
        assertTrue(pad.vibrating)
        assertFalse("the phone buzzed as well", shadowOf(vibrator).isVibrating)
    }

    @Test fun withPhoneFallbackOffAPadWithoutMotorsLeavesThePhoneStill() {
        ControllerPrefs.setRumblePhoneFallback(context, false)
        shadowOf(Looper.getMainLooper()).idle()
        rumble.controllerMotors = { null }
        rumble.otherControllerMotors = { _, _, _ -> null }
        activeController(PAD_ID)
        effect()
        assertFalse(shadowOf(vibrator).isVibrating)
        ControllerPrefs.setRumblePhoneFallback(context, true)
        shadowOf(Looper.getMainLooper()).idle()
        effect()
        assertTrue(shadowOf(vibrator).isVibrating)
    }

    @Test fun aPadWithoutAndroidMotorsRumblesThroughAProviderBeforeThePhone() {
        val usb = FakeMotors()
        rumble.controllerMotors = { null }
        rumble.otherControllerMotors = { _, _, _ -> null }
        rumble.providerMotors = { usb }
        activeController(PAD_ID)
        effect(strong = 40000, weak = 1000)
        assertEquals(listOf(Triple(40000, 1000, 5000L)), usb.played)
        assertFalse("the phone buzzed as well", shadowOf(vibrator).isVibrating)
    }

    @Test fun androidMotorsWinOverAProvider() {
        val pad = FakeMotors(); val usb = FakeMotors()
        rumble.controllerMotors = { pad }
        rumble.providerMotors = { usb }
        activeController(PAD_ID)
        effect()
        assertTrue(pad.vibrating)
        assertTrue(usb.played.isEmpty())
    }

    @Test fun aProvidersPadStillRumblesWhenNoAndroidControllerIsActive() {
        val usb = FakeMotors()
        rumble.providerMotors = { usb }
        activeController(PadBridge.NO_CONTROLLER)
        effect()
        assertTrue(usb.vibrating)
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test fun aFullStrengthEffectReachesTheMotorsUnscaled() {
        val pad = FakeMotors()
        rumble.controllerMotors = { pad }
        activeController(PAD_ID)
        effect(strong = 65535, weak = 65535, ms = 200)
        assertEquals(listOf(Triple(65535, 65535, 200L)), pad.played)
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

    @Test fun deckHapticsPlayOnThePhoneNeverOnTheController() {
        val pad = FakeMotors()
        rumble.controllerMotors = { id -> if (id == PAD_ID) pad else null }
        activeController(PAD_ID)
        haptic(db = 7, ms = 15)
        assertTrue("a haptic tick did not play on the phone", shadowOf(vibrator).isVibrating)
        assertTrue("a haptic tick reached the controller's motors", pad.played.isEmpty())
        effect(strong = 40000, weak = 1000)
        assertEquals(listOf(Triple(40000, 1000, 5000L)), pad.played)
    }

    @Test fun deckHapticOnTheFallbackPhoneDoesNotSuppressOrCancelGameRumble() {
        val phone = FakeMotors()
        RumbleComponent::class.java.getDeclaredField("phone").apply { isAccessible = true }.set(rumble, phone)
        activeController(PadBridge.NO_CONTROLLER)
        effect(strong = 40000, weak = 0)
        haptic(db = 7, ms = 2500)
        assertEquals(2000L, phone.played.last().third)
        val until = RumbleComponent::class.java.getDeclaredField("hapticUntil").apply { isAccessible = true }.getLong(rumble)
        assertTrue(until - android.os.SystemClock.elapsedRealtime() <= 2000L)
        haptic(db = 0, ms = 0)
        assertTrue("a haptic stop cancelled game rumble on the shared phone vibrator", phone.vibrating)
        effect(strong = 40000, weak = 0)
        assertEquals("the haptic left game-effect deduplication stale", 3, phone.played.size)
    }

    @Test fun usbRumbleProviderStartsOnlyWhileGameRumbleIsEnabled() {
        ControllerPrefs.setRumble(context, false)
        rumble.stop()
        var starts = 0
        var releases = 0
        val provider = object : RumbleProvider {
            override val name = "test provider"
            override fun sessionStart(context: Context) { starts++ }
            override fun motors(context: Context): RumbleComponent.Motors? = null
            override fun release(why: String) { releases++ }
        }
        RumbleProviders.register(provider)
        try {
            rumble = RumbleComponent().also {
                it.providerMotors = { null }
                it.motorLooper = Looper.getMainLooper()
                it.attach(context)
                it.start()
            }
            assertEquals(0, starts)
            ControllerPrefs.setRumble(context, true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, starts)
            ControllerPrefs.setRumble(context, false)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, releases)
        } finally {
            RumbleProviders.unregister(provider)
        }
    }

    @Test fun deckHapticsIgnoreThePhoneFallbackSetting() {
        ControllerPrefs.setRumblePhoneFallback(context, false)
        shadowOf(Looper.getMainLooper()).idle()
        haptic(db = 0, ms = 15)
        assertTrue(shadowOf(vibrator).isVibrating)
    }

    @Test fun deckHapticsPlayAtTheHigherSlidersStrength() {
        val phone = FakeMotors()
        RumbleComponent::class.java.getDeclaredField("phone").apply { isAccessible = true }.set(rumble, phone)
        haptic(db = -5, ms = 15, side = DeckHaptics.LEFT)
        haptic(db = 7, ms = 15, side = DeckHaptics.RIGHT)
        haptic(db = -5, ms = 25, side = DeckHaptics.LEFT)
        assertEquals(listOf(Triple(DeckHaptics.LOW_AMPLITUDE * 257, 0, 15L), Triple(255 * 257, 0, 15L), Triple(255 * 257, 0, 25L)), phone.played)
    }

    @Test fun aDeckHapticStopNeverEndsAGameRumble() {
        val pad = FakeMotors()
        rumble.controllerMotors = { id -> if (id == PAD_ID) pad else null }
        activeController(PAD_ID)
        effect()
        haptic(db = 0, ms = 0)
        assertTrue(pad.vibrating)
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

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))

    private fun activeController(id: Int, slot: Int = 0) {
        PadBridge::class.java.getDeclaredField("activeControllerId").apply { isAccessible = true }.setInt(null, id)
        val slots = PadBridge::class.java.getDeclaredField("slotControllers").apply { isAccessible = true }.get(null)
        (slots as java.util.concurrent.atomic.AtomicIntegerArray).set(slot, id)
    }

    private fun effect(strong: Int = 65535, weak: Int = 65535, ms: Int = 5000, slot: Int = 0) {
        // Inject a decoded force-feedback packet; transport is outside these tests.
        val i = Int::class.javaPrimitiveType
        RumbleComponent::class.java.getDeclaredMethod("buzz", i, i, i, i)
            .apply { isAccessible = true }.invoke(rumble, strong, weak, ms, slot)
    }

    private fun haptic(db: Int, ms: Int, side: Int = DeckHaptics.BOTH) =
        effect(strong = db and 0xFFFF, weak = 0, ms = ms, slot = RumbleComponent.HAPTIC or side)

    private class FakeMotors : RumbleComponent.Motors {
        override val name = "fake pad"
        val played = mutableListOf<Triple<Int, Int, Long>>()
        var vibrating = false
        override fun play(strong: Int, weak: Int, ms: Long) { played += Triple(strong, weak, ms); vibrating = true }
        override fun cancel() { vibrating = false }
    }

    private companion object { const val PAD_ID = 7; const val SECOND_PAD_ID = 8 }

    @Implements(LocalServerSocket::class)
    class NoPacketsSocket {
        @Implementation fun __constructor__(name: String) = Unit
        @Implementation fun accept(): LocalSocket = throw IOException("No incoming packets")
        @Implementation fun close() = Unit
    }
}
