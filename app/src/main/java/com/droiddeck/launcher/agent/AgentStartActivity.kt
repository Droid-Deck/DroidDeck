package com.droiddeck.launcher.agent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.session.SessionService
import org.json.JSONArray
import org.json.JSONObject

/** Shell-launched trampoline: Android permits the shell to start this protected debug Activity. */
class AgentStartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = decodeRequest(intent.getStringExtra(EXTRA_REQUEST))
        if (request == null) {
            finish()
            return
        }

        Thread({
            try {
                AgentBridgeProvider.awaitRecovery()
                runOnUiThread {
                    if (!isFinishing) startSession(request)
                }
            } catch (e: Exception) {
                Log.e(TAG, "waiting for agent recovery", e)
                runOnUiThread { finish() }
            }
        }, "agent-start-recovery").start()
    }

    private fun decodeRequest(encoded: String?): JSONObject? {
        if (encoded.isNullOrBlank()) {
            Log.e(TAG, "missing agent start request")
            return null
        }
        if (encoded.length > AgentAccess.MAX_REQUEST_CHARS) {
            Log.w(TAG, "agent start request is too large")
            return null
        }
        return try {
            JSONObject(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e(TAG, "invalid agent start request", e)
            null
        }
    }

    private fun startSession(request: JSONObject) {
        try {
            AgentAccess.requireStart(this, request)
        } catch (e: AgentException) {
            Log.w(TAG, e.code)
            finish()
            return
        }
        val requestedMode = request.optString("mode")
        val sessionMode = when (requestedMode) {
            "steam" -> SessionService.MODE_STEAM
            "desktop" -> SessionService.MODE_DESKTOP
            "run" -> SessionService.MODE_RUN
            else -> {
                Log.e(TAG, "invalid agent session mode: $requestedMode")
                finish()
                return
            }
        }

        val program = if (sessionMode == SessionService.MODE_RUN) {
            request.optString("program").takeIf { it.isNotBlank() }
        } else {
            null
        }
        if (sessionMode == SessionService.MODE_RUN && program == null) {
            Log.e(TAG, "run mode requires a program path")
            finish()
            return
        }

        val sessionIntent = Intent(this, SessionActivity::class.java).apply {
            action = SessionService.ACTION_AGENT_START
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(SessionService.EXTRA_MODE, sessionMode)
            if (program != null) {
                val args = request.optJSONArray("programArgs") ?: JSONArray()
                putExtra(SessionService.EXTRA_PROGRAM, program)
                putExtra(SessionService.EXTRA_PROGRAM_ARGS, Array(args.length()) { index -> args.optString(index) })
            }
            if (sessionMode == SessionService.MODE_STEAM) {
                request.optString("steamUi").takeIf { it == "desktop" }?.let {
                    putExtra(SessionService.EXTRA_STEAM_UI, it)
                }
                request.optString("steamUrl").takeIf { it.startsWith("steam://") }?.let {
                    putExtra(SessionService.EXTRA_STEAM_URL, it)
                }
            }
        }
        startActivity(sessionIntent)
        finish()
    }

    companion object {
        const val EXTRA_REQUEST = "request"
        private const val TAG = "AgentStartActivity"
    }
}
