package com.droiddeck.launcher.stores

import android.util.Log

object StoresNative {
    private const val TAG = "StoresNative"
    private const val LIBRARY = "droiddeckstores"

    @Volatile private var loaded: Boolean? = null
    @Volatile private var versionText: String? = null
    @Volatile private var failure: String? = null

    val available: Boolean get() = ensureLoaded()

    val version: String? get() { ensureLoaded(); return versionText }

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
            failure = t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
            Log.w(TAG, "engine unavailable: $failure")
            false
        }
        loaded = ok
        return ok
    }

    @Synchronized
    fun forget() { loaded = null }

    @JvmStatic
    external fun nativeVersion(): String
}
