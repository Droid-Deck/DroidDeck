package com.droiddeck.launcher.gpu

import android.content.Context
import com.droiddeck.launcher.agent.AgentGuest
import org.json.JSONObject

/**
 * Cardboard mode's SteamVR (docs/cardboard-vr.md): Valve's ARM64 SteamVR in the guest, rendering
 * through the DroidDeck driver as a side-by-side pair that the Cardboard pass puts the lens on. Driven
 * through the guest agent's fixed "vr" request (droiddeck-steamvr), so it needs no agent commands.
 * Every call blocks on the guest: run them off the main thread.
 */
object SteamVr {
    enum class Status {
        /** No session, no agent, or a runtime without the helper or the driver. */
        UNAVAILABLE,
        NOT_INSTALLED,
        /** Downloading or unpacking (571 MB). */
        INSTALLING,
        READY,
        RUNNING,
    }

    fun status(context: Context): Status = runCatching {
        parse(AgentGuest.call(context, JSONObject().put("kind", "vr").put("action", "status"), STATUS_TIMEOUT_MS))
    }.getOrDefault(Status.UNAVAILABLE)

    /** install, start or stop; true when the guest did it. */
    fun run(context: Context, action: String): Boolean = runCatching {
        require(action in setOf("install", "start", "stop"))
        succeeded(action, AgentGuest.call(context, JSONObject().put("kind", "vr").put("action", action), ACTION_TIMEOUT_MS))
    }.getOrDefault(false)

    /**
     * The agent answers ok whenever it ran the request, even when droiddeck-steamvr then failed; the
     * helper's own exit code says whether start or stop worked. install runs detached and only reports
     * that it started (its progress shows in status).
     */
    fun succeeded(action: String, answer: JSONObject): Boolean = when {
        !answer.optBoolean("ok", false) -> false
        action == "install" -> answer.optBoolean("started", false)
        else -> answer.optInt("exitCode", -1) == 0
    }

    /** The agent's status answer -> what the menu shows. */
    fun parse(answer: JSONObject): Status = when {
        !answer.optBoolean("ok", false) -> Status.UNAVAILABLE
        answer.optInt("exitCode", 0) != 0 -> Status.UNAVAILABLE
        answer.optBoolean("installing") -> Status.INSTALLING
        !answer.optBoolean("driver") -> Status.UNAVAILABLE
        !answer.optBoolean("installed") -> Status.NOT_INSTALLED
        answer.optBoolean("running") -> Status.RUNNING
        else -> Status.READY
    }

    private const val STATUS_TIMEOUT_MS = 10_000L
    private const val ACTION_TIMEOUT_MS = 40_000L
}
