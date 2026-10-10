package com.droiddeck.launcher.stores

import android.util.Log

/**
 * The native download engine for the three stores: `libdroiddeckstores.so`, loaded the first time
 * a store needs it, never at process start. The app has to run without it (a build without the
 * Rust side, or an ABI the library was not built for), so [available] is a plain false then and
 * Setup says "Stores engine not built"; the store managers fall back to their Java fetch loops.
 */
object StoresNative {
    private const val TAG = "StoresNative"
    private const val LIBRARY = "droiddeckstores"

    @Volatile private var loaded: Boolean? = null
    @Volatile private var versionText: String? = null
    @Volatile private var failure: String? = null

    /** True once the library is loaded and its version symbol binds; false when it cannot be. Cached. */
    val available: Boolean get() = ensureLoaded()

    /** The engine's own version string, or null when the library is not there. */
    val version: String? get() { ensureLoaded(); return versionText }

    /** Why the library did not load, for the Setup row; null when it did. */
    val problem: String? get() { ensureLoaded(); return failure }

    @Synchronized
    fun ensureLoaded(): Boolean {
        loaded?.let { return it }
        val ok = try {
            System.loadLibrary(LIBRARY)
            versionText = nativeVersion()
            Log.i(TAG, "engine ${versionText ?: "?"} loaded")
            true
        } catch (t: Throwable) {
            // UnsatisfiedLinkError when the .so is missing or a symbol is not there; anything else is
            // a surprise worth the same treatment: the Java paths carry on.
            failure = t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
            Log.w(TAG, "engine unavailable: $failure")
            false
        }
        loaded = ok
        return ok
    }

    /** Resets the cached answer, so a probe after an update tries the library again. */
    @Synchronized
    fun forget() { loaded = null }

    /** The crate's version string; only after [ensureLoaded] said yes. */
    @JvmStatic
    external fun nativeVersion(): String
}
