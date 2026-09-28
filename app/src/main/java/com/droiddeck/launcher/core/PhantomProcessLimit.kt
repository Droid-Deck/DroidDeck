package com.droiddeck.launcher.core

import android.content.ContentResolver
import android.os.Build
import android.provider.Settings

/** Android's phantom process monitor can kill the many child processes used by a Steam session. */
enum class PhantomProcessStatus {
    NOT_APPLICABLE,
    DISABLED,
    ENABLED,
    UNSET,
    UNREADABLE,
}

object PhantomProcessLimit {
    const val ADB_COMMAND = "adb shell settings put global settings_enable_monitor_phantom_procs false"
    const val SHELL_COMMAND = "settings put global settings_enable_monitor_phantom_procs false"
    private const val SETTING = "settings_enable_monitor_phantom_procs"

    fun adbCommand(enabled: Boolean): String =
        "adb shell settings put global $SETTING ${if (enabled) "true" else "false"}"

    fun read(resolver: ContentResolver, sdk: Int = Build.VERSION.SDK_INT): PhantomProcessStatus {
        if (sdk < Build.VERSION_CODES.S) return PhantomProcessStatus.NOT_APPLICABLE
        val global = try {
            Settings.Global.getString(resolver, SETTING)
        } catch (_: Exception) {
            return PhantomProcessStatus.UNREADABLE
        }
        return status(global, systemProperty(OVERRIDE_PROPERTY))
    }

    /**
     * The value Android itself acts on, in FeatureFlagUtils.isEnabled's order: the global setting
     * when it is set, otherwise the feature-flag override property. Developer options' "Disable
     * child process restrictions" writes only the property, so a device switched off there has no
     * global setting at all and must not be reported as unset.
     */
    fun status(global: String?, override: String?): PhantomProcessStatus {
        val value = global?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: override?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return when (value) {
            "false", "0" -> PhantomProcessStatus.DISABLED
            null -> PhantomProcessStatus.UNSET
            else -> PhantomProcessStatus.ENABLED
        }
    }

    private const val OVERRIDE_PROPERTY = "persist.sys.fflag.override.$SETTING"

    private fun systemProperty(name: String): String? = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, name) as? String
    } catch (_: Exception) {
        null
    }

    fun blocksSteam(status: PhantomProcessStatus): Boolean =
        status != PhantomProcessStatus.NOT_APPLICABLE && status != PhantomProcessStatus.DISABLED

    fun title(status: PhantomProcessStatus): String = when (status) {
        PhantomProcessStatus.ENABLED -> "Child-process limit is on"
        PhantomProcessStatus.UNSET -> "Child-process limit is unset"
        PhantomProcessStatus.UNREADABLE -> "Child-process limit could not be checked"
        PhantomProcessStatus.DISABLED -> "Child-process limit is off"
        PhantomProcessStatus.NOT_APPLICABLE -> "Child-process limit not required"
    }

    fun instructions(status: PhantomProcessStatus): String = when (status) {
        PhantomProcessStatus.ENABLED -> "Turn off “Restrict child processes” in Developer options. If you cannot find that option, connect this device to a computer with ADB and run the command below. Return here and check again."
        PhantomProcessStatus.UNSET -> "This setting is unset, so the ROM default applies and may enforce the limit. Set “Restrict child processes” to off in Developer options. If you cannot find that option, connect this device to a computer with ADB and run the command below. Return here and check again."
        PhantomProcessStatus.UNREADABLE -> "DroidDeck could not read the setting. Set “Restrict child processes” to off in Developer options. If you cannot find that option, connect this device to a computer with ADB and run the command below. Return here and check again."
        PhantomProcessStatus.DISABLED -> "Steam sessions can start."
        PhantomProcessStatus.NOT_APPLICABLE -> "This Android version does not use this limit."
    }

    fun gateInstructions(status: PhantomProcessStatus): String = when (status) {
        PhantomProcessStatus.ENABLED -> "Turn off “Restrict child processes” in Developer options."
        PhantomProcessStatus.UNSET -> "The ROM default may enforce this limit. Explicitly turn off “Restrict child processes” in Developer options."
        PhantomProcessStatus.UNREADABLE -> "DroidDeck could not check this setting. Turn off “Restrict child processes” in Developer options."
        else -> instructions(status)
    }

    fun reportValue(status: PhantomProcessStatus): String = when (status) {
        PhantomProcessStatus.DISABLED -> "disabled (good - the OS will not kill the session's children)"
        PhantomProcessStatus.ENABLED -> "ENABLED (the OS may kill the session with no log; turn off Restrict child processes)"
        PhantomProcessStatus.UNSET -> "not set (ROM default applies; DroidDeck requires an explicit disabled value)"
        PhantomProcessStatus.UNREADABLE -> "unreadable (DroidDeck could not verify the setting)"
        PhantomProcessStatus.NOT_APPLICABLE -> "not applicable before Android 12"
    }
}
