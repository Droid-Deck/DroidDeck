package com.droiddeck.launcher.stores.steam

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.util.Log
import com.droiddeck.launcher.FrontEndActivity
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionState

interface SteamClientSignInHost {
    fun startSteamStoreSignIn(): Boolean
}

internal object SteamClientSignIn {
    const val ACTION_OPEN = "com.droiddeck.launcher.OPEN_STEAM_STORE_SIGN_IN"
    const val EXTRA_STORE_SIGN_IN = "com.droiddeck.launcher.extra.STEAM_STORE_SIGN_IN"

    private const val TAG = "SteamClientSignIn"
    private const val PREFS = "stores"
    private const val PENDING = "steam_client_sign_in_pending"
    private const val DISPATCHED = "steam_client_sign_in_dispatched"
    private const val BASELINE_SESSION = "steam_client_sign_in_baseline"
    private const val TARGET_SESSION = "steam_client_sign_in_target"
    private val ended = setOf(SessionPhase.IDLE, SessionPhase.FAILED)

    fun open(context: Context): Boolean {
        findHost(context)?.let { return it.startSteamStoreSignIn() }
        val intent = Intent(context, FrontEndActivity::class.java)
            .setAction(ACTION_OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }.getOrElse {
            Log.w(TAG, "could not open the Steam client (${it.javaClass.simpleName})")
            false
        }
    }

    private fun findHost(context: Context): SteamClientSignInHost? {
        var current: Context? = context
        while (current != null) {
            if (current is SteamClientSignInHost) return current
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }

    fun arm(context: Context, baselineSessionId: String? = null, targetSessionId: String? = null): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(PENDING, true)
            .putBoolean(DISPATCHED, false)
            .putString(BASELINE_SESSION, baselineSessionId)
            .putString(TARGET_SESSION, targetSessionId)
            .commit()

    fun markDispatched(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PENDING, false)) prefs.edit().putBoolean(DISPATCHED, true).commit()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(PENDING)
            .remove(DISPATCHED)
            .remove(BASELINE_SESSION)
            .remove(TARGET_SESSION)
            .apply()
    }

    fun finishIfSessionEnded(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!readyToFinish(
                prefs.getBoolean(PENDING, false), prefs.getBoolean(DISPATCHED, false),
                SessionState.running, SessionState.phase,
            )) return false

        val shouldSync = shouldSync(
            baselineSessionId = prefs.getString(BASELINE_SESSION, null),
            targetSessionId = prefs.getString(TARGET_SESSION, null),
            currentSessionId = SessionState.sessionId,
        )
        clear(context)
        if (shouldSync) SteamBackend.syncLibrary(context.applicationContext, force = true)
        return shouldSync
    }

    internal fun shouldSync(
        baselineSessionId: String?,
        targetSessionId: String?,
        currentSessionId: String?,
    ): Boolean = if (targetSessionId != null) {
        currentSessionId == targetSessionId
    } else {
        currentSessionId != baselineSessionId
    }

    internal fun readyToFinish(pending: Boolean, dispatched: Boolean, running: Boolean, phase: SessionPhase): Boolean =
        pending && dispatched && !running && phase in ended
}
