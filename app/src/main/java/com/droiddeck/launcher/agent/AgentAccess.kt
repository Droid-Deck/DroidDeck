package com.droiddeck.launcher.agent

import android.content.Context

object AgentAccess {
    const val MAX_REQUEST_CHARS = 256 * 1024
    private const val PREFS = "agent"
    private const val KEY_COMMANDS = "commands"

    const val PROTECTED_PREFS = PREFS

    fun commandsAllowed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COMMANDS, false)

    fun commandsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_COMMANDS, false)

    fun setCommandsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_COMMANDS, enabled).apply()
        if (!enabled) runCatching { AgentEnv.revoke(context) }
            .onFailure { android.util.Log.w("AgentAccess", "Could not finish clearing debugging experiments") }
    }

    fun requireCommands(context: Context) {
        if (!commandsAllowed(context)) throw AgentException(
            "AGENT_COMMANDS_DISABLED",
            "Debugging tools are off: turn on Setup > Diagnostics > Debugging tools on the device",
        )
    }

    fun requireStart(context: Context, request: org.json.JSONObject) {
        if (request.optString("mode") == "run") requireCommands(context)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

class AgentException(val code: String, message: String) : RuntimeException(message)
