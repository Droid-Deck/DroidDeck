package com.droiddeck.launcher.stores.steam

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.StoresNative
import org.json.JSONArray
import org.json.JSONObject

/** Blocking calls into the Rust engine; they reach the network, so never on the main thread. */
internal object SteamEngine {
    private const val TAG = "SteamEngine"
    const val MISSING = "engine_missing"
    const val MISSING_TEXT = "Steam: the store engine is not available"

    private const val RETRY_MS = 60_000L

    private val lock = Any()
    @Volatile private var signedIn = false
    private var failed: JSONObject? = null
    private var failedAt = 0L

    fun failure(error: String): JSONObject = JSONObject().put("ok", false).put("error", error)

    private fun call(op: String, block: () -> String): JSONObject {
        if (!StoresNative.ensureLoaded()) return failure(MISSING)
        val text = try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "$op: not in this engine build")
            return failure(MISSING)
        } catch (t: Throwable) {
            Log.w(TAG, "$op: ${t.javaClass.simpleName}")
            return failure(t.javaClass.simpleName)
        }
        return runCatching { JSONObject(text) }.getOrElse { failure("$op: unreadable answer") }
    }

    fun signIn(context: Context): JSONObject {
        synchronized(lock) {
            if (signedIn) return JSONObject().put("ok", true)
            failed?.takeIf { SystemClock.elapsedRealtime() - failedAt < RETRY_MS }?.let { return it }
            val roots = JSONArray()
                .put(SessionPrefs.steamRoot(context, "arm64").absolutePath)
                .put(SessionPrefs.steamRoot(context, SteamClient.X86_64).absolutePath)
            val registry = SteamClient.registry(context).absolutePath
            val answer = call("signIn") { SteamNative.nativeSignIn(roots.toString(), registry, "") }
            signedIn = answer.optBoolean("ok")
            failed = if (signedIn) null else answer
            failedAt = SystemClock.elapsedRealtime()
            if (!signedIn) Log.w(TAG, "sign-in: ${answer.optString("error")}")
            return answer
        }
    }

    fun forget() {
        synchronized(lock) {
            failed = null
            if (!signedIn) return
            signedIn = false
            try {
                SteamNative.nativeSignOut()
            } catch (t: Throwable) {
                Log.w(TAG, "sign-out: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun signedCall(context: Context, op: String, block: () -> String): JSONObject {
        synchronized(lock) {
            val signIn = signIn(context)
            if (!signIn.optBoolean("ok")) return signIn
            val answer = call(op, block)
            val error = answer.optString("error").lowercase()
            if (!answer.optBoolean("ok") && listOf("logon", "sign", "session", "connect").any { it in error }) signedIn = false
            return answer
        }
    }

    fun library(context: Context): JSONObject = signedCall(context, "library") { SteamNative.nativeLibrary() }

    fun plan(context: Context, appId: Int, language: String): JSONObject =
        signedCall(context, "plan") { SteamNative.nativePlan(appId, language) }

    fun uninstall(appId: Int, steamRoot: String, host: String, guest: String, roots: String): JSONObject {
        synchronized(lock) {
            return call("uninstall") { SteamNative.nativeUninstall(appId, steamRoot, host, guest, roots) }
        }
    }
}
