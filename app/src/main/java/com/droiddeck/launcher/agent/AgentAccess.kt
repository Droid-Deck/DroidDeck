package com.droiddeck.launcher.agent

import android.content.Context
import com.droiddeck.launcher.BuildConfig

/**
 * Who may run code through the agent bridge. Reading state, starting and stopping sessions,
 * launching and quitting games and sending input only need the shell (the provider's DUMP
 * permission). Running commands in the guest, evaluating JavaScript in the Steam client, session
 * environment, binary overrides and preference writes reach inside the app's sandbox - the guest
 * holds the Steam login - which the shell of a stock device otherwise cannot. Debug builds allow
 * them; a release build only once the user turns on Setup > Session > Agent commands. The bridge
 * itself can never turn it on.
 */
object AgentAccess {
    private const val PREFS = "agent"
    private const val KEY_COMMANDS = "commands"

    /** The preferences file the bridge must never write: set-pref refuses it. */
    const val PROTECTED_PREFS = PREFS

    fun commandsAllowed(context: Context): Boolean =
        BuildConfig.DEBUG || prefs(context).getBoolean(KEY_COMMANDS, false)

    /** The Setup toggle's own state (debug builds allow commands whatever it says). */
    fun commandsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_COMMANDS, false)

    fun setCommandsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_COMMANDS, enabled).apply()
    }

    fun requireCommands(context: Context) {
        if (!commandsAllowed(context)) throw AgentException(
            "AGENT_COMMANDS_DISABLED",
            "Agent commands are off: turn on Setup > Session > Agent commands on the device",
        )
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** A refusal with a stable code, answered as {"ok": false, "error": {code, message}}. */
class AgentException(val code: String, message: String) : RuntimeException(message)
