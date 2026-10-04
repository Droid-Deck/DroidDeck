package com.droiddeck.launcher.session

import android.net.LocalServerSocket
import android.content.SharedPreferences
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.input.ControllerPrefs
import java.io.DataInputStream
import kotlin.math.max

/**
 * Rumble for the on-screen pad, on the phone itself.
 *
 * When a game plays a force-feedback effect on the fake pad, the fake evdev layer inside the guest
 * connects to the abstract socket [NAME] and sends one packet: strong, weak, duration in ms and
 * the pad slot, four little-endian 16-bit values. This listens for those and drives the device's
 * vibrator with the stronger motor's strength for the effect's duration. Best effort both ways:
 * a missing listener costs the guest nothing, and a bad packet is dropped.
 */
class RumbleComponent : SessionPart() {
    @Volatile private var server: LocalServerSocket? = null
    private var vibrator: Vibrator? = null
    private var preferences: SharedPreferences? = null
    private var enabled = false
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "rumble") refreshEnabled()
    }

    @Synchronized private fun refreshEnabled() {
        enabled = app()?.let { ControllerPrefs.rumbleEnabled(it) } == true
        if (!enabled) vibrator?.cancel()
    }

    override fun start() {
        val ctx = app() ?: return
        val s = try { LocalServerSocket(NAME) } catch (e: Exception) { Log.w(TAG, "rumble: no listener ($e)"); return }
        vibrator = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
        preferences = ControllerPrefs.prefs(ctx).also { it.registerOnSharedPreferenceChangeListener(preferenceListener) }
        refreshEnabled()
        server = s
        Thread({
            while (server === s) {
                val client = try { s.accept() } catch (e: Exception) { break }
                try {
                    val bytes = ByteArray(8)
                    DataInputStream(client.inputStream).readFully(bytes)
                    val strong = u16(bytes, 0); val weak = u16(bytes, 2); val ms = u16(bytes, 4)
                    buzz(max(strong, weak), ms)
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
        vibrator?.cancel()
        vibrator = null
        val s = server
        server = null
        try { s?.close() } catch (e: Exception) { /* the accept loop ends on the next wake */ }
    }

    private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    @Synchronized private fun buzz(strength: Int, ms: Int) {
        if (!enabled) return
        val vibrator = vibrator ?: return
        if (!vibrator.hasVibrator()) return
        if (strength == 0 || ms == 0) { vibrator.cancel(); return }
        val amplitude = (strength * 255L / 65535L).toInt().coerceIn(1, 255)
        val duration = ms.toLong().coerceIn(1L, 5000L)
        try {
            if (vibrator.hasAmplitudeControl()) vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            else vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            Log.w(TAG, "rumble: $e")
        }
    }

    companion object {
        private const val TAG = "SessionService"
        /** Must match the fake evdev layer (fakeinput_steam.cpp). */
        const val NAME = "droiddeck-rumble"
    }
}
