package com.droiddeck.launcher.core

import android.content.Context

object LegacyDeviceConfigSync {
    private const val PREFS = "phantom-process-limit"
    private const val MARKER = "android12-off"
    internal const val GET_MODE = "device_config get_sync_disabled_for_tests"
    internal const val IS_DISABLED = "device_config is_sync_disabled_for_tests"
    internal const val RESTORE = "device_config set_sync_disabled_for_tests none"

    fun pending(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all[MARKER] == true

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(MARKER).apply()
    }

    internal data class RecoveryResult(val confirmed: Boolean, val diagnostics: String)

    internal fun recover(shell: (String) -> WirelessAdbFix.ShellResult): RecoveryResult {
        val diagnostics = mutableListOf<String>()
        fun run(command: String): WirelessAdbFix.ShellResult {
            val result = try {
                shell(command)
            } catch (failure: Exception) {
                WirelessAdbFix.ShellResult(-1, "", failure.message ?: failure.javaClass.simpleName)
            }
            diagnostics += "$command: ${result.allOutput.trim().ifBlank { "exit ${result.exitCode}" }}"
            return result
        }

        val mode = run(GET_MODE)
        val legacy = unsupportedModeQuery(mode)
        val initial = if (legacy) run(IS_DISABLED) else mode
        val value = initial.output.trim().takeIf { initial.exitCode == 0 }
        val alreadyRestored = if (legacy) value == "false" else value == "none" || value == "until_reboot"
        val needsRestore = if (legacy) value == "true" else value == "persistent"
        val confirmed = when {
            alreadyRestored -> true
            !needsRestore -> false
            run(RESTORE).exitCode != 0 -> false
            else -> {
                val final = run(if (legacy) IS_DISABLED else GET_MODE)
                final.exitCode == 0 && final.output.trim() == if (legacy) "false" else "none"
            }
        }
        return RecoveryResult(confirmed, diagnostics.joinToString("\n"))
    }

    private fun unsupportedModeQuery(result: WirelessAdbFix.ShellResult): Boolean {
        val message = result.allOutput.lowercase()
        return message.contains("get_sync_disabled_for_tests") &&
            listOf("unknown command", "invalid command", "unrecognized command").any(message::contains)
    }
}
