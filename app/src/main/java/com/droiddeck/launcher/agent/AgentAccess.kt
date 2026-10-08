package com.droiddeck.launcher.agent

import android.content.Context

/**
 * Who may run code through the agent bridge. Reading state, starting and stopping sessions,
 * launching and quitting games and sending input only need the shell (the provider's DUMP
 * permission). Running commands in the guest, evaluating JavaScript in the Steam client, session
 * environment, binary overrides and preference writes reach inside the app's sandbox - the guest
 * holds the Steam login - which the shell of a stock device otherwise cannot. They require
 * Setup > Session > Debugging tools to be on, in every build. The bridge itself cannot turn it on.
 */
object AgentAccess {
    const val MAX_REQUEST_CHARS = 256 * 1024
    private const val PREFS = "agent"
    private const val KEY_COMMANDS = "commands"

    /** The preferences file the bridge must never write: set-pref refuses it. */
    const val PROTECTED_PREFS = PREFS

    fun commandsAllowed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COMMANDS, false)

    /** The Setup toggle's own state. */
    fun commandsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_COMMANDS, false)

    fun setCommandsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_COMMANDS, enabled).apply()
        if (!enabled) runCatching { AgentEnv.revoke(context) }
            .onFailure { android.util.Log.w("AgentAccess", "Could not finish clearing debugging experiments") }
    }

    fun requireCommands(context: Context) {
        if (!commandsAllowed(context)) throw AgentException(
            "AGENT_COMMANDS_DISABLED",
            "Debugging tools are off: turn on Setup > Session > Debugging tools on the device",
        )
    }

    fun requireStart(context: Context, request: org.json.JSONObject) {
        if (request.optString("mode") == "run") requireCommands(context)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** A refusal with a stable code, answered as {"ok": false, "error": {code, message}}. */
class AgentException(val code: String, message: String) : RuntimeException(message)
