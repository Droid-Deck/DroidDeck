package com.droiddeck.launcher.session

import android.content.Context
import android.content.SharedPreferences
import android.hardware.input.InputManager
import android.net.LocalServerSocket
import android.os.Build
import android.os.CombinedVibration
import android.os.Handler
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
import com.droiddeck.launcher.input.PadBridge
import java.io.DataInputStream
import java.util.Locale
import kotlin.math.max

/**
 * Rumble for the pad, on the motors in the player's hands.
 *
 * When a game plays a force-feedback effect on the fake pad (or Steam Input sends the Deck's
 * rumble or haptic reports), the fake evdev layer inside the guest connects to the abstract socket
 * [NAME] and sends one packet: strong, weak, duration in ms and the pad slot, four little-endian
 * 16-bit values. This listens for those and plays the effect on a physical controller:
 *
 *  1. the controller that last drove the pad (PadBridge.activeControllerId), re-queried by id;
 *  2. if that id is stale or reports no motors, any connected gamepad/joystick that does have
 *     motors, preferring the same vendor:product as the last active pad (a USB pad that
 *     re-enumerates with a new id when a companion app switches its mode);
 *  3. a registered [RumbleProvider] that reaches a pad's motors another way (e.g. a Razer
 *     Kishi over USB, [com.droiddeck.launcher.session.razer.RazerKishiRumble]);
 *  4. only then the device's own vibrator - and only when "phone vibration fallback" is on.
 *
 * The packet's slot word carries [PULSE] for the Deck's trackpad / UI haptics: short ticks that
 * play only while no game rumble is running and never end one.
 *
 * Every lookup is logged (id, name, vid:pid, vibrator ids, legacy hasVibrator, chosen path), rate
 * limited, and gamepads being added, changed or removed are logged too, so a session's app.log
 * says why an effect went where it went. Best effort throughout: a missing listener costs the
 * guest nothing, and a bad packet is dropped.
 */
class RumbleComponent : SessionPart() {
    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var stopped = false
    private var phone: Motors? = null
    /** Where the last effect went, so the next one (or a stop) can end it there. */
    private var playing: Motors? = null
    private var controllerId = UNRESOLVED
    private var controller: Motors? = null
    /** When the target was last resolved to the phone or nothing, so a fallback is retried now and then. */
    private var fallbackSince = 0L
    /** vendor:product of the last physical pad seen driving the pad, for finding it again under a new id. */
    private var lastVendor = 0
    private var lastProduct = 0
    private var preferences: SharedPreferences? = null
    private var enabled = false
    private var phoneFallback = true
    private var inputManager: InputManager? = null
    private val lastLog = HashMap<String, Long>()
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "rumble" || key == ControllerPrefs.RUMBLE_PHONE_FALLBACK) refreshEnabled()
    }
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = deviceEvent("added", deviceId)
        override fun onInputDeviceChanged(deviceId: Int) = deviceEvent("changed", deviceId)
        override fun onInputDeviceRemoved(deviceId: Int) = deviceEvent("removed", deviceId)
    }

    /** The motors of the controller with this input device id, or null when it has none. Tests swap it. */
    internal var controllerMotors: (Int) -> Motors? = { id -> InputDevice.getDevice(id)?.let { motorsOf(it, "active id") } }

    /** Any other connected pad with motors, preferring this vendor:product. Tests swap it. */
    internal var otherControllerMotors: (Int, Int, Int) -> Motors? = ::scanForMotors

    /** The registered [RumbleProvider]s' motors. Tests swap it. */
    internal var providerMotors: () -> Motors? = { app()?.let { RumbleProviders.motors(it) } }

    /** Until when (elapsedRealtime) a game's rumble runs, so Deck haptic ticks do not cut it short. */
    private var rumbleUntil = 0L
    private var providerGeneration = 0

    @Synchronized private fun refreshEnabled() {
        val ctx = app()
        enabled = ctx?.let { ControllerPrefs.rumbleEnabled(it) } == true
        phoneFallback = ctx?.let { ControllerPrefs.rumblePhoneFallback(it) } != false
        controllerId = UNRESOLVED
        if (!enabled) { playing?.cancel(); playing = null }
    }

    override fun start() {
        val ctx = app() ?: return
        stopped = false
        val vibrator = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
        phone = vibrator?.takeIf { it.hasVibrator() }?.let { PhoneMotors(it) }
        preferences = ControllerPrefs.prefs(ctx).also { it.registerOnSharedPreferenceChangeListener(preferenceListener) }
        refreshEnabled()
        try {
            inputManager = ctx.getSystemService(InputManager::class.java)?.also {
                it.registerInputDeviceListener(deviceListener, Handler(Looper.getMainLooper()))
            }
        } catch (e: Exception) {
            Log.w(TAG, "rumble: no input device listener ($e)")
        }
        logConnectedPads()
        // Providers may ask for device access (a USB permission dialog) now rather than mid-game.
        RumbleProviders.sessionStart(ctx)
        val s = bind(retries = 0)
        if (s != null) {
            listen(s)
        } else {
            // A previous session's listener in this process still holds the name (or the kernel
            // has not let go of it yet): close ours if we know it, then retry off this thread.
            closeStaleListener()
            Thread({
                val retry = bind(retries = BIND_RETRIES)
                if (retry != null) listen(retry) else Log.w(TAG, "rumble: no listener after $BIND_RETRIES retries")
            }, "rumble-bind").apply { isDaemon = true; start() }
        }
    }

    /** Claims the socket name, retrying [retries] times [BIND_RETRY_MS] apart. */
    private fun bind(retries: Int): LocalServerSocket? {
        var attempt = 0
        while (true) {
            if (stopped) return null
            try {
                return LocalServerSocket(NAME)
            } catch (e: Exception) {
                if (attempt >= retries) {
                    Log.w(TAG, "rumble: no listener ($e)" + if (retries > 0) " after $attempt retries" else ", retrying")
                    return null
                }
            }
            attempt++
            try { Thread.sleep(BIND_RETRY_MS) } catch (e: InterruptedException) { return null }
        }
    }

    private fun listen(s: LocalServerSocket) {
        if (stopped) { try { s.close() } catch (e: Exception) { }; return }
        server = s
        synchronized(RumbleComponent::class.java) { activeListener = s }
        Thread({
            while (server === s) {
                val client = try { s.accept() } catch (e: Exception) { break }
                try {
                    val bytes = ByteArray(8)
                    DataInputStream(client.inputStream).readFully(bytes)
                    buzz(u16(bytes, 0), u16(bytes, 2), u16(bytes, 4), u16(bytes, 6))
                } catch (e: Exception) {
                    // A partial packet or a closed peer: nothing to play.
                } finally {
                    try { client.close() } catch (e: Exception) { /* already gone */ }
                }
            }
        }, "rumble").apply { isDaemon = true; start() }
        Log.i(TAG, "rumble: listening on @$NAME")
    }

    private fun closeStaleListener() {
        val stale = synchronized(RumbleComponent::class.java) { activeListener.also { activeListener = null } } ?: return
        Log.i(TAG, "rumble: closing a stale listener left by an earlier session")
        try { stale.close() } catch (e: Exception) { /* already closed */ }
    }

    @Synchronized override fun stop() {
        stopped = true
        preferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        preferences = null
        try { inputManager?.unregisterInputDeviceListener(deviceListener) } catch (e: Exception) { }
        inputManager = null
        enabled = false
        playing?.cancel()
        playing = null
        phone = null
        controller = null
        controllerId = UNRESOLVED
        RumbleProviders.release("session end")
        val s = server
        server = null
        synchronized(RumbleComponent::class.java) { if (activeListener === s) activeListener = null }
        try { s?.close() } catch (e: Exception) { /* the accept loop ends on the next wake */ }
    }

    private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun buzz(strong: Int, weak: Int, ms: Int) = buzz(strong, weak, ms, 0)

    @Synchronized private fun buzz(strong: Int, weak: Int, ms: Int, slot: Int) {
        if (!enabled) return
        val pulse = slot and PULSE != 0
        val now = SystemClock.elapsedRealtime()
        if ((strong == 0 && weak == 0) || ms == 0) {
            // A stop: end whatever is playing, wherever it is. Not a reason to log a target. A
            // haptic stop leaves a game's rumble alone.
            if (pulse && now < rumbleUntil) return
            if (!pulse) rumbleUntil = 0L
            playing?.cancel()
            return
        }
        if (pulse && now < rumbleUntil) {
            // A trackpad tick during a game's rumble: lost in it anyway, and replacing it would end it.
            logLimited("pulse-skip", "rumble: haptic tick skipped while a rumble effect runs")
            return
        }
        if (!pulse) rumbleUntil = now + ms
        val target = target()
        if (target !== playing) {
            playing?.cancel()
            playing = target
            if (target != null) Log.i(TAG, "rumble: playing on ${target.name}")
        }
        if (target == null) {
            logLimited("none", "rumble: dropped (strong $strong, weak $weak, $ms ms): no controller motors and phone fallback is off")
            return
        }
        try {
            target.play(strong, weak, ms.toLong().coerceIn(1L, 5000L))
        } catch (e: Exception) {
            Log.w(TAG, "rumble: ${target.name}: $e")
            // The device went away or refused: look again next time.
            controllerId = UNRESOLVED
        }
    }

    /** The active controller's motors, or another pad's, or the phone's (if allowed). */
    private fun target(): Motors? {
        val id = PadBridge.activeControllerId()
        val now = SystemClock.elapsedRealtime()
        val retryFallback = controller == null && now - fallbackSince >= FALLBACK_RETRY_MS
        val providersChanged = RumbleProviders.generation != providerGeneration
        if (id != controllerId || retryFallback || providersChanged) {
            providerGeneration = RumbleProviders.generation
            controllerId = id
            controller = resolve(id)
            if (controller == null) fallbackSince = now
        }
        if (controller != null) return controller
        if (!phoneFallback) return null
        return phone
    }

    private fun resolve(id: Int): Motors? {
        if (id == PadBridge.NO_CONTROLLER) {
            // A pad a provider drives still counts when PadBridge has not seen it (or sees only
            // the on-screen pad): the player holds it.
            val provided = providerOrNull()
            if (provided != null) {
                logLimited("resolve:none", "rumble: lookup: no physical controller active -> ${provided.name}")
                return provided
            }
            logLimited("resolve:none", "rumble: lookup: no physical controller active (on-screen pad or none yet) -> " + fallbackName())
            return null
        }
        InputDevice.getDevice(id)?.let { device ->
            if (device.vendorId != 0 || device.productId != 0) { lastVendor = device.vendorId; lastProduct = device.productId }
        }
        val direct = try { controllerMotors(id) } catch (e: Exception) { Log.w(TAG, "rumble: lookup id $id failed: $e"); null }
        if (direct != null) {
            logLimited("resolve:$id", "rumble: lookup: active id $id -> controller ${direct.name}")
            return direct
        }
        val other = try { otherControllerMotors(id, lastVendor, lastProduct) } catch (e: Exception) { Log.w(TAG, "rumble: scan failed: $e"); null }
        if (other != null) {
            logLimited("resolve:$id", "rumble: lookup: active id $id has no usable motors -> other controller ${other.name}")
            return other
        }
        val provided = providerOrNull()
        if (provided != null) {
            logLimited("resolve:$id", "rumble: lookup: active id $id has no Android motors -> ${provided.name}")
            return provided
        }
        logLimited("resolve:$id", "rumble: lookup: active id $id and no other connected pad has motors -> " + fallbackName())
        return null
    }

    private fun providerOrNull(): Motors? = try { providerMotors() } catch (e: Exception) { Log.w(TAG, "rumble: provider lookup failed: $e"); null }

    /**
     * Settings -> Controller -> "Test controller rumble": the same order as a session (a pad's
     * Android motors, then a provider's, then the phone if the fallback is on), then a pattern: strong motor, weak
     * motor, three short Deck-style ticks, all at full strength. Blocks for about two seconds;
     * returns what played where, for a toast.
     */
    internal fun test(ctx: Context): String {
        attach(ctx)
        val vibrator = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
        phone = vibrator?.takeIf { it.hasVibrator() }?.let { PhoneMotors(it) }
        phoneFallback = ControllerPrefs.rumblePhoneFallback(ctx)
        logConnectedPads()
        val target = scanForMotors(UNRESOLVED, 0, 0) ?: providerOrNull() ?: (if (phoneFallback) phone else null)
        val found = RumbleProviders.describe(ctx)
        if (target == null) {
            val msg = when {
                found != null -> "Found $found but could not open a rumble path (see the rumble: log)"
                !phoneFallback -> "No controller motors found (phone vibration fallback is off)"
                else -> "No controller motors and no phone vibrator found"
            }
            Log.i(TAG, "rumble: test: $msg")
            RumbleProviders.releaseIfIdle("test done")
            return msg
        }
        Log.i(TAG, "rumble: test: playing on ${target.name}")
        try {
            target.play(0xFFFF, 0, 350); Thread.sleep(550)
            target.play(0, 0xFFFF, 350); Thread.sleep(550)
            repeat(3) { target.play(TICK_STRENGTH, TICK_STRENGTH, 15); Thread.sleep(160) }
            target.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "rumble: test: ${target.name}: $e")
            return "Rumble failed on ${target.name}: $e"
        } finally {
            // Let a provider's last stop frame go out before it lets go of the device.
            if (target !== phone) Thread.sleep(200)
            RumbleProviders.releaseIfIdle("test done")
        }
        return "Rumble: strong, weak, 3 ticks on ${target.name}" + if (target === phone && found != null) " (found $found)" else ""
    }

    private fun fallbackName() = when {
        !phoneFallback -> "nothing (phone vibration fallback off)"
        phone != null -> "the device's vibrator"
        else -> "nothing (the device has no vibrator)"
    }

    /** Every connected gamepad/joystick other than [skipId] with motors; the one matching vid:pid first. */
    private fun scanForMotors(skipId: Int, vendor: Int, product: Int): Motors? {
        var firstOther: Motors? = null
        for (otherId in InputDevice.getDeviceIds()) {
            if (otherId == skipId) continue
            val device = InputDevice.getDevice(otherId) ?: continue
            if (!isPad(device)) continue
            val motors = motorsOf(device, "scan") ?: continue
            if (vendor != 0 && device.vendorId == vendor && device.productId == product) return motors
            if (firstOther == null) firstOther = motors
        }
        return firstOther
    }

    /** The device's motors, logging what Android reports for it (rate limited per device and reason). */
    private fun motorsOf(device: InputDevice, why: String): Motors? {
        val name = "\"${device.name}\" (${vidPid(device)}, id ${device.id})"
        var ids = IntArray(0)
        var managerError: String? = null
        if (Build.VERSION.SDK_INT >= 31) {
            try { ids = device.vibratorManager.vibratorIds.sortedArray() } catch (e: Exception) { managerError = e.toString() }
        }
        @Suppress("DEPRECATION")
        val legacy = try { device.vibrator } catch (e: Exception) { null }
        val legacyHas = try { legacy?.hasVibrator() == true } catch (e: Exception) { false }
        val chosen: Motors? = when {
            Build.VERSION.SDK_INT >= 31 && ids.isNotEmpty() -> PadMotors(name, device.vibratorManager, ids)
            legacyHas && legacy != null -> LegacyPadMotors(name, legacy)
            else -> null
        }
        logLimited("dev:${device.id}:$why", String.format(Locale.ROOT,
            "rumble: %s: %s sources 0x%x virtual %b, vibratorIds %d%s, legacy hasVibrator %b -> %s",
            why, name, device.sources, device.isVirtual, ids.size, managerError?.let { " ($it)" } ?: "", legacyHas,
            when (chosen) { is PadMotors -> "VibratorManager (${ids.size} motor${if (ids.size == 1) "" else "s"})"; is LegacyPadMotors -> "legacy Vibrator"; else -> "no motors" }))
        return chosen
    }

    private fun deviceEvent(what: String, deviceId: Int) {
        val device = if (what == "removed") null else InputDevice.getDevice(deviceId)
        if (device != null && !isPad(device)) return
        if (device == null) {
            Log.i(TAG, "rumble: input device $deviceId $what")
        } else {
            Log.i(TAG, String.format(Locale.ROOT, "rumble: pad %s: \"%s\" (%s, id %d)", what, device.name, vidPid(device), device.id))
            motorsOf(device, "pad $what")
        }
        synchronized(this) {
            // Re-resolve on the next effect; the old target may be gone or a better one here.
            controllerId = UNRESOLVED
            controller = null
            fallbackSince = 0L
        }
    }

    private fun logConnectedPads() {
        try {
            val pads = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.filter { isPad(it) }
            if (pads.isEmpty()) Log.i(TAG, "rumble: no gamepad connected at start")
            pads.forEach { motorsOf(it, "at start") }
        } catch (e: Exception) {
            Log.w(TAG, "rumble: listing pads failed: $e")
        }
    }

    @Synchronized private fun logLimited(key: String, message: String) {
        val now = SystemClock.elapsedRealtime()
        val last = lastLog[key]
        if (last != null && now - last < LOG_INTERVAL_MS) return
        lastLog[key] = now
        Log.i(TAG, message)
    }

    /** One place an effect can play. Strengths are the evdev 0..65535 magnitudes. */
    interface Motors {
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

    /** One vibrator for the whole pad (before Android 12, or a pad Android exposes only that way). */
    private class LegacyPadMotors(override val name: String, private val vibrator: Vibrator) : Motors {
        override fun play(strong: Int, weak: Int, ms: Long) = vibrator.vibrate(oneShot(vibrator, max(strong, weak), ms))
        override fun cancel() = vibrator.cancel()
    }

    companion object {
        private const val TAG = "SessionService"
        /** Must match the fake evdev layer (fakeinput_steam.cpp). */
        const val NAME = "droiddeck-rumble"
        private const val UNRESOLVED = Int.MIN_VALUE
        private const val BIND_RETRIES = 20
        private const val BIND_RETRY_MS = 250L
        private const val FALLBACK_RETRY_MS = 2000L
        private const val LOG_INTERVAL_MS = 5000L
        /** Slot flag from the guest (fakeinput_steam.cpp): a Deck trackpad / UI haptic tick. */
        const val PULSE = 0x8000
        /** A Deck haptic tick at Steam's top Haptics Intensity (+12 dB): full strength. */
        private const val TICK_STRENGTH = 0xFFFF

        /** Runs [RumbleComponent.test] off the main thread and hands its result back on it. */
        fun testFromSettings(ctx: Context, done: (String) -> Unit) {
            val app = ctx.applicationContext
            Thread({
                val result = try { RumbleComponent().test(app) } catch (e: Exception) { "Rumble test failed: $e" }
                Handler(Looper.getMainLooper()).post { done(result) }
            }, "rumble-test").apply { isDaemon = true; start() }
        }

        /** The listener of the most recent session in this process, so a new one can close it if it leaked. */
        private var activeListener: LocalServerSocket? = null

        private fun isPad(device: InputDevice): Boolean {
            val s = device.sources
            return !device.isVirtual && ((s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
        }

        private fun vidPid(device: InputDevice) = String.format(Locale.ROOT, "%04x:%04x", device.vendorId, device.productId)

        private fun oneShot(vibrator: Vibrator, strength: Int, ms: Long): VibrationEffect {
            val amplitude = if (vibrator.hasAmplitudeControl()) (strength * 255L / 65535L).toInt().coerceIn(1, 255)
                else VibrationEffect.DEFAULT_AMPLITUDE
            return VibrationEffect.createOneShot(ms, amplitude)
        }
    }
}
