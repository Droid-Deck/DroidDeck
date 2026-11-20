package com.droiddeck.launcher.gpu

import android.content.Context
import com.droiddeck.launcher.agent.AgentGuest
import org.json.JSONObject

object SteamVr {
    const val APP_ID = "250820"

    enum class Status {
        UNAVAILABLE,
        NOT_INSTALLED,
        INSTALLING,
        READY,
        RUNNING,
    }

    internal fun shouldPoll(status: Status?, cardboardEnabled: Boolean, pictureSwitched: Boolean,
                            glassesControl: Boolean, requested: Boolean): Boolean =
        requested || cardboardEnabled || pictureSwitched || glassesControl ||
            status == Status.RUNNING || status == Status.INSTALLING

    internal fun autoAction(
        status: Status, requested: Boolean, loading: Boolean, steamSession: Boolean,
        startTried: Boolean, installTried: Boolean,
    ): String? {
        if (!requested || loading || !steamSession || startTried) return null
        return when (status) {
            Status.READY -> "start"
            Status.NOT_INSTALLED -> if (installTried) null else "install"
            else -> null
        }
    }

    data class Report(val status: Status, val percent: Int? = null)

    fun status(context: Context): Status = report(context).status

    fun report(context: Context): Report = runCatching {
        report(AgentGuest.call(context, JSONObject().put("kind", "vr").put("action", "status"), STATUS_TIMEOUT_MS))
    }.getOrDefault(Report(Status.UNAVAILABLE))

    fun report(answer: JSONObject): Report {
        val status = parse(answer)
        val size = answer.optLong("size", 0L)
        val done = answer.optLong("downloaded", -1L)
        val percent = if (status == Status.INSTALLING && size > 0 && done in 0 until size) (done * 100 / size).toInt() else null
        return Report(status, percent)
    }

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
