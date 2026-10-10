package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Another way to reach a controller's motors when Android exposes none for it - e.g. a vendor
 * protocol over USB ([com.droiddeck.launcher.session.razer.RazerKishiRumble]). [RumbleComponent]
 * asks the registered providers after the pad's own Android motors and before the phone.
 *
 * Kept small on purpose, so a provider can live outside the app later (a companion add-on APK
 * offered from the Components page, bridged by a thin adapter) without RumbleComponent changing.
 */
interface RumbleProvider {
    /** For logs. */
    val name: String

    /** Bumped whenever the provider's devices or open paths change, so the next effect looks again. */
    val generation: Int get() = 0

    /** A session starts: a provider may ask for device access now rather than mid-game. */
    fun sessionStart(context: Context) {}

    /**
     * Motors to play on, or null when the provider has nothing usable. Called from the rumble
     * thread whenever the target is re-resolved; must be cheap when no device of its kind is there.
     */
    fun motors(context: Context): RumbleComponent.Motors?

    /** For the Settings test's message: the device this provider found (and its state), or null. */
    fun describe(context: Context): String? = null

    /** Session end: stop and let go of every device. */
    fun release(why: String) {}

    /** After the Settings test: let go unless a session is using the device. */
    fun releaseIfIdle(why: String) {}
}

/** The providers [RumbleComponent] consults, in registration order. */
object RumbleProviders {
    private const val TAG = "SessionService"
    private val providers = CopyOnWriteArrayList<RumbleProvider>()

    fun register(provider: RumbleProvider) {
        if (providers.none { it === provider }) providers += provider
    }

    fun unregister(provider: RumbleProvider) { providers.remove(provider) }

    val all: List<RumbleProvider> get() = providers

    /** Sum of the providers' generations: changes when any of them changes. */
    val generation: Int get() = providers.sumOf { it.generation }

    fun motors(context: Context): RumbleComponent.Motors? {
        for (p in providers) {
            val m = try { p.motors(context) } catch (e: Exception) { Log.w(TAG, "rumble: ${p.name}: $e"); null }
            if (m != null) return m
        }
        return null
    }

    fun describe(context: Context): String? =
        providers.mapNotNull { p -> try { p.describe(context) } catch (e: Exception) { null } }.joinToString("; ").ifEmpty { null }

    fun sessionStart(context: Context) = each { it.sessionStart(context) }
    fun release(why: String) = each { it.release(why) }
    fun releaseIfIdle(why: String) = each { it.releaseIfIdle(why) }

    private inline fun each(action: (RumbleProvider) -> Unit) {
        for (p in providers) try { action(p) } catch (e: Exception) { Log.w(TAG, "rumble: ${p.name}: $e") }
    }
}
