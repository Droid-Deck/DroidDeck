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

    /** install, start or stop; true when the guest took it. */
    fun run(context: Context, action: String): Boolean = runCatching {
        require(action in setOf("install", "start", "stop"))
        AgentGuest.call(context, JSONObject().put("kind", "vr").put("action", action), ACTION_TIMEOUT_MS)
            .optBoolean("ok", false)
    }.getOrDefault(false)

    /** The agent's status answer -> what the menu shows. */
    fun parse(answer: JSONObject): Status = when {
        !answer.optBoolean("ok", false) -> Status.UNAVAILABLE
        answer.optBoolean("installing") -> Status.INSTALLING
        !answer.optBoolean("driver") -> Status.UNAVAILABLE
        !answer.optBoolean("installed") -> Status.NOT_INSTALLED
        answer.optBoolean("running") -> Status.RUNNING
        else -> Status.READY
    }

    private const val STATUS_TIMEOUT_MS = 10_000L
    private const val ACTION_TIMEOUT_MS = 40_000L
}
