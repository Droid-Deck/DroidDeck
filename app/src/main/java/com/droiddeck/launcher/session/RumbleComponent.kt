package com.droiddeck.launcher.session

import android.net.LocalServerSocket
import android.content.SharedPreferences
import android.os.Build
import android.os.CombinedVibration
import android.os.SystemClock
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

class RumbleComponent : SessionPart() {
    @Volatile private var server: LocalServerSocket? = null
    private var phone: Motors? = null
    private val playing = HashMap<Int, Motors>()
    private val sent = HashMap<Int, Sent>()
    private val controllers = HashMap<Int, Motors?>()
    private var preferences: SharedPreferences? = null
    private var enabled = false
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "rumble") refreshEnabled()
    }

    internal var controllerMotors: (Int) -> Motors? = ::lookupController

    @Synchronized private fun refreshEnabled() {
        enabled = app()?.let { ControllerPrefs.rumbleEnabled(it) } == true
        if (!enabled) cancelAll()
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
        phone = null
        controllers.clear()
        val s = server
        server = null
        try { s?.close() } catch (e: Exception) {   }
    }

    private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    @Synchronized private fun buzz(strong: Int, weak: Int, ms: Int, slot: Int) {
        if (!enabled) return
        val target = target(slot)
        val previous = playing[slot]
        if (target !== previous) {
            previous?.cancel()
            sent.remove(slot)
            if (target == null) playing.remove(slot) else playing[slot] = target
            target?.let { Log.i(TAG, "rumble: slot $slot playing on ${it.name}") }
        }
        if (target == null) return
        if ((strong == 0 && weak == 0) || ms == 0) { target.cancel(); sent.remove(slot); return }
        val now = SystemClock.uptimeMillis()
        val last = sent[slot]
        if (last != null && (now - last.at < MIN_GAP_MS || (last.strong == strong && last.weak == weak && now < last.until))) return
        val duration = ms.toLong().coerceIn(1L, 5000L)
        try {
            target.play(strong, weak, duration)
            sent[slot] = Sent(strong, weak, now, now + duration)
        } catch (e: Exception) {
            Log.w(TAG, "rumble: ${target.name}: $e")
        }
    }

    private fun target(slot: Int): Motors? {
        val id = PadBridge.controllerForSlot(slot)
        if (id == PadBridge.NO_CONTROLLER) return phone
        if (!controllers.containsKey(id)) controllers[id] = try { controllerMotors(id) } catch (e: Exception) { null }
        return controllers[id] ?: phone
    }

    private fun cancelAll() {
        playing.values.toSet().forEach { it.cancel() }
        playing.clear()
        sent.clear()
    }

    private data class Sent(val strong: Int, val weak: Int, val at: Long, val until: Long)

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
            if (any) manager.vibrate(combined.combine()) else manager.cancel()
        }
        override fun cancel() = manager.cancel()
    }

    private class LegacyPadMotors(override val name: String, private val vibrator: Vibrator) : Motors {
        override fun play(strong: Int, weak: Int, ms: Long) = vibrator.vibrate(oneShot(vibrator, max(strong, weak), ms))
        override fun cancel() = vibrator.cancel()
    }

    companion object {
        private const val TAG = "SessionService"
        const val NAME = "droiddeck-rumble"
        const val MIN_GAP_MS = 50L

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
