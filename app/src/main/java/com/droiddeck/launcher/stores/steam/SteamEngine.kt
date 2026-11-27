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
    /** After Steam refuses sign-ins for coming too often, wait well past its limit. */
    private const val THROTTLED_MS = 30 * 60_000L

    private val lock = Any()
    @Volatile private var signedIn = false
    @Volatile private var signedInSteamId: String? = null
    /** The saved login the last sign-in attempt read, so a refusal rejects that login and not one saved since. */
    @Volatile var attempted: String? = null
        private set
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
            if (signedIn) return JSONObject().put("ok", true).apply { signedInSteamId?.let { put("steamId", it) } }
            failed?.takeIf { SystemClock.elapsedRealtime() - failedAt < waitAfter(it) }?.let { return it }
            val roots = JSONArray()
                .put(SessionPrefs.steamRoot(context, "arm64").absolutePath)
                .put(SessionPrefs.steamRoot(context, SteamClient.X86_64).absolutePath)
            val registry = SteamClient.registry(context).absolutePath
            attempted = SteamStore.fingerprint(context)
            val answer = call("signIn") { SteamNative.nativeSignIn(roots.toString(), registry, "") }
            signedIn = answer.optBoolean("ok")
            signedInSteamId = answer.optString("steamId").takeIf { signedIn && it.isNotBlank() }
            failed = if (signedIn) null else answer
            failedAt = SystemClock.elapsedRealtime()
            if (!signedIn) Log.w(TAG, "sign-in: ${answer.optString("error")}")
            return answer
        }
    }

    private fun waitAfter(failure: JSONObject): Long {
        val error = failure.optString("error")
        return if ("RateLimitExceeded" in error || "Throttle" in error) THROTTLED_MS else RETRY_MS
    }

    fun forget() {
        synchronized(lock) {
            failed = null
            if (!signedIn) {
                signedInSteamId = null
                return
            }
            signedIn = false
            signedInSteamId = null
            try {
                SteamNative.nativeSignOut()
            } catch (t: Throwable) {
                Log.w(TAG, "sign-out: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun signedCall(context: Context, op: String, includeSteamId: Boolean = false, block: () -> String): JSONObject {
        synchronized(lock) {
            val signIn = signIn(context)
            if (!signIn.optBoolean("ok")) return signIn
            val answer = call(op, block)
            val error = answer.optString("error").lowercase()
            if (!answer.optBoolean("ok") && listOf("logon", "sign", "session", "connect").any { it in error }) {
                signedIn = false
                signedInSteamId = null
            }
            if (answer.optBoolean("ok") && includeSteamId) signedInSteamId?.let { answer.put("steamId", it) }
            return answer
        }
    }

    fun library(context: Context): JSONObject = signedCall(context, "library", includeSteamId = true) { SteamNative.nativeLibrary() }

    fun plan(context: Context, appId: Int, language: String): JSONObject =
        signedCall(context, "plan") { SteamNative.nativePlan(appId, language) }

    fun uninstall(appId: Int, steamRoot: String, host: String, guest: String, roots: String): JSONObject {
        synchronized(lock) {
            return call("uninstall") { SteamNative.nativeUninstall(appId, steamRoot, host, guest, roots) }
        }
    }
}
