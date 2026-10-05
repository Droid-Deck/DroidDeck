package com.droiddeck.launcher.session

import android.net.LocalServerSocket
import android.content.SharedPreferences
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.InputDevice
import androidx.annotation.RequiresApi
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.input.ControllerPrefs
import com.droiddeck.launcher.input.PadBridge
import java.io.DataInputStream
import kotlin.math.max

/**
 * Rumble for the pad, on the motors in the player's hands.
 *
 * When a game plays a force-feedback effect on the fake pad (or Steam Input sends the Deck's
 * rumble report), the fake evdev layer inside the guest connects to the abstract socket [NAME]
 * and sends one packet: strong, weak, duration in ms and the pad slot, four little-endian 16-bit
 * values. This listens for those and plays the effect on the physical controller that last drove
 * the pad (PadBridge.activeControllerId) when Android exposes its motors - strong and weak
 * separately where it has two - and otherwise on the device's own vibrator, with the stronger
 * motor's strength: the on-screen pad, or a controller without rumble. Best effort both ways: a
 * missing listener costs the guest nothing, and a bad packet is dropped.
 */
class RumbleComponent : SessionPart() {
    @Volatile private var server: LocalServerSocket? = null
    private var phone: Motors? = null
    /** Where the last effect went, so the next one (or a stop) can end it there. */
    private var playing: Motors? = null
    private var controllerId = PadBridge.NO_CONTROLLER
    private var controller: Motors? = null
    private var preferences: SharedPreferences? = null
    private var enabled = false
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "rumble") refreshEnabled()
    }

    /** The motors of the controller with this input device id, or null when it has none. Tests swap it. */
    internal var controllerMotors: (Int) -> Motors? = ::lookupController

    @Synchronized private fun refreshEnabled() {
        enabled = app()?.let { ControllerPrefs.rumbleEnabled(it) } == true
        if (!enabled) { playing?.cancel(); playing = null }
    }

    override fun start() {
        val ctx = app() ?: return
        val s = try { LocalServerSocket(NAME) } catch (e: Exception) { Log.w(TAG, "rumble: no listener ($e)"); return }
        val vibrator = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
        phone = vibrator?.takeIf { it.hasVibrator() }?.let { PhoneMotors(it) }
        preferences = ControllerPrefs.prefs(ctx).also { it.registerOnSharedPreferenceChangeListener(preferenceListener) }
        refreshEnabled()
        server = s
        Thread({
            while (server === s) {
                val client = try { s.accept() } catch (e: Exception) { break }
                try {
                    val bytes = ByteArray(8)
                    DataInputStream(client.inputStream).readFully(bytes)
                    buzz(u16(bytes, 0), u16(bytes, 2), u16(bytes, 4))
                } catch (e: Exception) {
                    // A partial packet or a closed peer: nothing to play.
                } finally {
                    try { client.close() } catch (e: Exception) { /* already gone */ }
                }
            }
        }, "rumble").apply { isDaemon = true; start() }
        Log.i(TAG, "rumble: listening on @$NAME")
    }

    @Synchronized override fun stop() {
        preferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        preferences = null
        enabled = false
        playing?.cancel()
        playing = null
        phone = null
        controller = null
        controllerId = PadBridge.NO_CONTROLLER
        val s = server
        server = null
        try { s?.close() } catch (e: Exception) { /* the accept loop ends on the next wake */ }
    }

    private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    @Synchronized private fun buzz(strong: Int, weak: Int, ms: Int) {
        if (!enabled) return
        val target = target()
        if (target !== playing) {
            playing?.cancel()
            playing = target
            target?.let { Log.i(TAG, "rumble: playing on ${it.name}") }
        }
        if (target == null) return
        if ((strong == 0 && weak == 0) || ms == 0) { target.cancel(); return }
        try {
            target.play(strong, weak, ms.toLong().coerceIn(1L, 5000L))
        } catch (e: Exception) {
            Log.w(TAG, "rumble: ${target.name}: $e")
        }
    }

    /** The active controller's motors when it has any, else the phone's. */
    private fun target(): Motors? {
        val id = PadBridge.activeControllerId()
        if (id != controllerId) {
            controllerId = id
            controller = if (id == PadBridge.NO_CONTROLLER) null else try { controllerMotors(id) } catch (e: Exception) { null }
        }
        return controller ?: phone
    }

    /** One place an effect can play. Strengths are the evdev 0..65535 magnitudes. */
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

    /** Android 12+: each of the pad's motors on its own, the lower id being the strong one. */
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
            if (any) manager.vibrate(combined.combine()) else manager.cancel()
        }
        override fun cancel() = manager.cancel()
    }

    /** Before Android 12 a pad has one vibrator. */
    private class LegacyPadMotors(override val name: String, private val vibrator: Vibrator) : Motors {
        override fun play(strong: Int, weak: Int, ms: Long) = vibrator.vibrate(oneShot(vibrator, max(strong, weak), ms))
        override fun cancel() = vibrator.cancel()
    }

    companion object {
        private const val TAG = "SessionService"
        /** Must match the fake evdev layer (fakeinput_steam.cpp). */
        const val NAME = "droiddeck-rumble"

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
                return if (ids.isEmpty()) null else PadMotors(name, manager, ids)
            }
            @Suppress("DEPRECATION")
            val vibrator = device.vibrator
            return if (vibrator.hasVibrator()) LegacyPadMotors(name, vibrator) else null
        }
    }
}
