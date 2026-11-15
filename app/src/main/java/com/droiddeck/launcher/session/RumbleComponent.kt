package com.droiddeck.launcher.session

import android.net.LocalServerSocket
import android.content.SharedPreferences
import android.os.Build
import android.os.CombinedVibration
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.InputDevice
import androidx.annotation.RequiresApi
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.input.ControllerPrefs
import com.droiddeck.launcher.input.JoyCon
import com.droiddeck.launcher.input.PadBridge
import java.io.DataInputStream
import kotlin.math.max

class RumbleComponent : SessionPart() {
    @Volatile private var server: LocalServerSocket? = null
    private var phone: Motors? = null
    private val playing = HashMap<Int, Motors>()
    private val effects = HashMap<Int, Effect>()
    private val controllers = HashMap<Int, Motors?>()
    private val joyConPairs = HashMap<Long, Motors>()
    private var preferences: SharedPreferences? = null
    private var enabled = false
    private var quiet = false
    private var motorThread: HandlerThread? = null
    private var handler: Handler? = null
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "rumble") refreshEnabled()
    }

    internal var controllerMotors: (Int) -> Motors? = ::lookupController
    internal var motorLooper: Looper? = null

    @Synchronized private fun refreshEnabled() {
        enabled = app()?.let { ControllerPrefs.rumbleEnabled(it) } == true
        if (!enabled) cancelAll()
    }

    @Synchronized fun quiet(on: Boolean) {
        quiet = on
        if (on) cancelAll()
    }

    override fun start() {
        val ctx = app() ?: return
        val s = try { LocalServerSocket(NAME) } catch (e: Exception) { Log.w(TAG, "rumble: no listener ($e)"); return }
        val vibrator = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
        phone = vibrator?.takeIf { it.hasVibrator() }?.let { PhoneMotors(it) }
        handler = Handler(motorLooper ?: HandlerThread("rumble-motors").also { motorThread = it; it.start() }.looper)
        preferences = ControllerPrefs.prefs(ctx).also { it.registerOnSharedPreferenceChangeListener(preferenceListener) }
        refreshEnabled()
        server = s
        Thread({
            while (server === s) {
                val client = try { s.accept() } catch (e: Exception) { break }
                try {
                    val bytes = ByteArray(8)
                    DataInputStream(client.inputStream).readFully(bytes)
                    buzz(u16(bytes, 0), u16(bytes, 2), u16(bytes, 4), u16(bytes, 6))
                } catch (e: Exception) {
                } finally {
                    try { client.close() } catch (e: Exception) {   }
                }
            }
        }, "rumble").apply { isDaemon = true; start() }
        Log.i(TAG, "rumble: listening on @$NAME")
    }

    @Synchronized override fun stop() {
        preferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        preferences = null
        enabled = false
        cancelAll()
        handler = null
        motorThread?.quitSafely()
        motorThread = null
        phone = null
        controllers.clear()
        joyConPairs.clear()
        val s = server
        server = null
        try { s?.close() } catch (e: Exception) {   }
    }

    private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    @Synchronized private fun buzz(strong: Int, weak: Int, ms: Int, slot: Int) {
        if (!enabled || quiet) return
        val target = target(slot)
        val previous = playing[slot]
        if (target !== previous) {
            previous?.cancel()
            forget(slot)
            if (target == null) playing.remove(slot) else playing[slot] = target
            target?.let { Log.i(TAG, "rumble: slot $slot playing on ${it.name}") }
        }
        if (target == null) return
        if ((strong == 0 && weak == 0) || ms == 0) { target.cancel(); forget(slot); return }
        val handler = handler ?: return
        val effect = effects.getOrPut(slot) { Effect(slot) }
        effect.want(strong, weak, ms)
        val now = SystemClock.uptimeMillis()
        if (effect.playing(now)) return
        handler.removeCallbacks(effect)
        val wait = effect.at + MIN_GAP_MS - now
        if (wait > 0) handler.postDelayed(effect, wait) else play(effect)
    }

    @Synchronized private fun play(effect: Effect) {
        val target = playing[effect.slot]
        if (target == null || effects[effect.slot] !== effect) return
        val duration = if (effect.hold) HOLD_EFFECT_MS else effect.ms.toLong().coerceIn(1L, 5000L)
        try {
            target.play(effect.strong, effect.weak, duration)
            effect.played(SystemClock.uptimeMillis(), duration)
            if (effect.hold) handler?.postDelayed(effect, KEEPALIVE_MS)
        } catch (e: Exception) {
            Log.w(TAG, "rumble: ${target.name}: $e")
        }
    }

    private fun forget(slot: Int) {
        effects.remove(slot)?.let { handler?.removeCallbacks(it) }
    }

    private fun target(slot: Int): Motors? {
        JoyCon.vrRumbleDevice(slot)?.let { hand -> return if (hand == PadBridge.NO_CONTROLLER) null else motors(hand) }
        val left = PadBridge.joyConForSlot(slot, JoyCon.LEFT)
        val right = PadBridge.joyConForSlot(slot, JoyCon.RIGHT)
        if (left != PadBridge.NO_CONTROLLER && right != PadBridge.NO_CONTROLLER) {
            val key = (left.toLong() shl 32) or (right.toLong() and 0xFFFFFFFFL)
            joyConPairs[key]?.let { return it }
            val l = motors(left)
            val r = motors(right)
            if (l != null && r != null) return JoyConPairMotors(l, r).also { joyConPairs[key] = it }
        }
        val id = PadBridge.controllerForSlot(slot)
        if (id == PadBridge.NO_CONTROLLER) return phone
        return motors(id) ?: phone
    }

    private fun motors(id: Int): Motors? {
        if (!controllers.containsKey(id)) controllers[id] = try { controllerMotors(id) } catch (e: Exception) { null }
        return controllers[id]
    }

    private fun cancelAll() {
        playing.values.toSet().forEach { it.cancel() }
        playing.clear()
        effects.values.forEach { handler?.removeCallbacks(it) }
        effects.clear()
    }

    private inner class Effect(val slot: Int) : Runnable {
        var strong = 0
        var weak = 0
        var ms = 0
        var at = -MIN_GAP_MS
        private var playedStrong = 0
        private var playedWeak = 0
        private var playedMs = 0
        private var until = 0L
        val hold get() = ms == HOLD_MS

        fun want(strong: Int, weak: Int, ms: Int) { this.strong = strong; this.weak = weak; this.ms = ms }

        fun playing(now: Long) = playedMs != 0 && playedStrong == strong && playedWeak == weak && playedMs == ms &&
            (hold || now < until)

        fun played(now: Long, duration: Long) {
            playedStrong = strong
            playedWeak = weak
            playedMs = ms
            at = now
            until = now + duration
        }

        override fun run() = play(this)
    }

    internal interface Motors {
        val name: String
        fun play(strong: Int, weak: Int, ms: Long)
        fun cancel()
    }

    private class PhoneMotors(private val vibrator: Vibrator) : Motors {
        override val name = "the device's vibrator"
        override fun play(strong: Int, weak: Int, ms: Long) = vibrator.vibrate(oneShot(vibrator, max(strong, weak), ms))
        override fun cancel() = vibrator.cancel()
    }

    @RequiresApi(31)
    private class PadMotors(override val name: String, private val manager: VibratorManager, private val ids: IntArray) : Motors {
        override fun play(strong: Int, weak: Int, ms: Long) {
            val combined = CombinedVibration.startParallel()
            var any = false
            fun add(id: Int, strength: Int) {
                if (strength == 0) return
                combined.addVibrator(id, oneShot(manager.getVibrator(id), strength, ms))
                any = true
            }
            if (ids.size >= 2) { add(ids[0], strong); add(ids[1], weak) } else add(ids[0], max(strong, weak))
            if (any) manager.vibrate(combined.combine()) else cancel()
        }

        // Some vendors' input-device vibrator HALs (Samsung's) don't halt a Bluetooth pad's running
        // effect on cancel(), but a new effect always replaces it.
        override fun cancel() {
            val supersede = CombinedVibration.startParallel()
            ids.forEach { supersede.addVibrator(it, VibrationEffect.createOneShot(1L, 1)) }
            manager.vibrate(supersede.combine())
            manager.cancel()
        }
    }

    @RequiresApi(31)
    private class JoyConMotors(override val name: String, private val manager: VibratorManager) : Motors {
        override fun play(strong: Int, weak: Int, ms: Long) =
            manager.vibrate(CombinedVibration.createParallel(oneShot(manager.defaultVibrator, max(strong, weak), ms)))
        override fun cancel() = manager.cancel()
    }

    private class JoyConPairMotors(private val left: Motors, private val right: Motors) : Motors {
        override val name = "${left.name} and ${right.name}"
        override fun play(strong: Int, weak: Int, ms: Long) {
            if (strong == 0) left.cancel() else left.play(strong, 0, ms)
            if (weak == 0) right.cancel() else right.play(0, weak, ms)
        }
        override fun cancel() {
            left.cancel()
            right.cancel()
        }
    }

    private class LegacyPadMotors(override val name: String, private val vibrator: Vibrator) : Motors {
        override fun play(strong: Int, weak: Int, ms: Long) = vibrator.vibrate(oneShot(vibrator, max(strong, weak), ms))
        override fun cancel() {
            vibrator.vibrate(VibrationEffect.createOneShot(1L, 1))
            vibrator.cancel()
        }
    }

    companion object {
        private const val TAG = "SessionService"
        const val NAME = "droiddeck-rumble"
        const val MIN_GAP_MS = 50L
        // Must match RUMBLE_HOLD_MS in fakeinput_steam.cpp.
        const val HOLD_MS = 0xFFFF
        const val HOLD_EFFECT_MS = 2000L
        const val KEEPALIVE_MS = 1500L

        private fun oneShot(vibrator: Vibrator, strength: Int, ms: Long): VibrationEffect {
            val amplitude = if (vibrator.hasAmplitudeControl()) (strength * 255L / 65535L).toInt().coerceIn(1, 255)
                else VibrationEffect.DEFAULT_AMPLITUDE
            return VibrationEffect.createOneShot(ms, amplitude)
        }

        private fun lookupController(id: Int): Motors? {
            val device = InputDevice.getDevice(id) ?: return null
            val name = "\"${device.name}\""
            if (Build.VERSION.SDK_INT >= 31) {
                val manager = device.vibratorManager
                val ids = manager.vibratorIds.sortedArray()
                if (ids.isEmpty()) return null
                return if (JoyCon.side(device) >= 0) JoyConMotors(name, manager) else PadMotors(name, manager, ids)
            }
            @Suppress("DEPRECATION")
            val vibrator = device.vibrator
            return if (vibrator.hasVibrator()) LegacyPadMotors(name, vibrator) else null
        }
    }
}
