package com.droiddeck.launcher.gpu

import android.content.Context
import com.droiddeck.launcher.agent.AgentGuest
import org.json.JSONObject

object SteamVr {
    enum class Status {
        UNAVAILABLE,
        NOT_INSTALLED,
        INSTALLING,
        READY,
        RUNNING,
    }

    fun status(context: Context): Status = runCatching {
        parse(AgentGuest.call(context, JSONObject().put("kind", "vr").put("action", "status"), STATUS_TIMEOUT_MS))
    }.getOrDefault(Status.UNAVAILABLE)

    fun run(context: Context, action: String): Boolean = runCatching {
        require(action in setOf("install", "start", "stop"))
        succeeded(action, AgentGuest.call(context, JSONObject().put("kind", "vr").put("action", action), ACTION_TIMEOUT_MS))
    }.getOrDefault(false)

    fun succeeded(action: String, answer: JSONObject): Boolean = when {
        !answer.optBoolean("ok", false) -> false
        action == "install" -> answer.optBoolean("started", false)
        else -> answer.optInt("exitCode", -1) == 0
    }

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
