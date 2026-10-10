package com.droiddeck.launcher.core

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.droiddeck.launcher.R

/** Android's phantom process monitor can kill the many child processes used by a Steam session. */
enum class PhantomProcessStatus {
    NOT_APPLICABLE,
    DISABLED,
    ENABLED,
    UNSET,
    UNREADABLE,
    OVERRIDDEN,
}

object PhantomProcessLimit {
    private const val SETTING = "settings_enable_monitor_phantom_procs"

    private const val MAX_PHANTOM = "2147483647"
    private const val PREFS = "phantom-process-limit"
    private const val PREF_ANDROID_12_OFF = "android12-off"
    private const val PREF_MANUAL_OVERRIDE = "manual-override"

    fun usesDeviceConfig(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk == Build.VERSION_CODES.S

    fun isOverridden(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_MANUAL_OVERRIDE, false)

    fun setOverridden(context: Context, overridden: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_MANUAL_OVERRIDE, overridden)
            .apply()
    }

    fun shellCommands(enabled: Boolean, sdk: Int = Build.VERSION.SDK_INT): List<String> = when {
        usesDeviceConfig(sdk) && enabled -> listOf(
            "device_config delete activity_manager max_phantom_processes",
            "device_config set_sync_disabled_for_tests none",
        )
        usesDeviceConfig(sdk) -> listOf(
            "device_config set_sync_disabled_for_tests persistent",
            "device_config put activity_manager max_phantom_processes $MAX_PHANTOM",
        )
        else -> listOf("settings put global $SETTING ${if (enabled) "true" else "false"}")
    }

    fun verifyCommand(sdk: Int = Build.VERSION.SDK_INT): String =
        if (usesDeviceConfig(sdk)) "device_config get activity_manager max_phantom_processes"
        else "settings get global $SETTING"

    fun verified(output: String, enabled: Boolean, sdk: Int = Build.VERSION.SDK_INT): Boolean {
        val value = output.trim()
        return if (usesDeviceConfig(sdk)) (value == MAX_PHANTOM) != enabled
        else value == if (enabled) "true" else "false"
    }

    fun adbCommand(enabled: Boolean, sdk: Int = Build.VERSION.SDK_INT): String =
        shellCommands(enabled, sdk).joinToString(" && ") { "adb shell $it" }

    fun adbCommand(): String = adbCommand(false)

    fun rememberAndroid12(context: Context, off: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_ANDROID_12_OFF, off).apply()
    }

    fun read(context: Context, sdk: Int = Build.VERSION.SDK_INT): PhantomProcessStatus {
        if (sdk < Build.VERSION_CODES.S) return PhantomProcessStatus.NOT_APPLICABLE
        if (usesDeviceConfig(sdk)) {
            val off = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_ANDROID_12_OFF, false)
            if (off) return PhantomProcessStatus.DISABLED
            val global = try {
                Settings.Global.getString(context.contentResolver, SETTING)
            } catch (_: Exception) {
                null
            }
            if (status(global, systemProperty(OVERRIDE_PROPERTY)) == PhantomProcessStatus.DISABLED) {
                return PhantomProcessStatus.DISABLED
            }
            return if (isOverridden(context)) PhantomProcessStatus.OVERRIDDEN else PhantomProcessStatus.UNREADABLE
        }
        val global = try {
            Settings.Global.getString(context.contentResolver, SETTING)
        } catch (_: Exception) {
            return if (isOverridden(context)) PhantomProcessStatus.OVERRIDDEN else PhantomProcessStatus.UNREADABLE
        }
        val detected = status(global, systemProperty(OVERRIDE_PROPERTY))
        return if (detected == PhantomProcessStatus.DISABLED) PhantomProcessStatus.DISABLED
        else if (isOverridden(context)) PhantomProcessStatus.OVERRIDDEN
        else detected
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

    fun hasDeveloperToggle(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    fun blocksSteam(status: PhantomProcessStatus): Boolean =
        status != PhantomProcessStatus.NOT_APPLICABLE &&
        status != PhantomProcessStatus.DISABLED &&
        status != PhantomProcessStatus.OVERRIDDEN

    fun title(context: Context, status: PhantomProcessStatus): String = context.getString(when (status) {
        PhantomProcessStatus.ENABLED -> R.string.phantom_title_on
        PhantomProcessStatus.UNSET -> R.string.phantom_title_unset
        PhantomProcessStatus.UNREADABLE -> R.string.phantom_title_unreadable
        PhantomProcessStatus.DISABLED -> R.string.phantom_title_off
        PhantomProcessStatus.OVERRIDDEN -> R.string.phantom_title_overridden
        PhantomProcessStatus.NOT_APPLICABLE -> R.string.phantom_title_not_required
    })

    fun instructions(context: Context, status: PhantomProcessStatus): String = when (status) {
        PhantomProcessStatus.ENABLED, PhantomProcessStatus.UNSET, PhantomProcessStatus.UNREADABLE ->
            context.getString(R.string.phantom_instructions_fix, fixSentence(context))
        PhantomProcessStatus.DISABLED -> context.getString(R.string.phantom_instructions_off)
        PhantomProcessStatus.OVERRIDDEN -> context.getString(R.string.phantom_instructions_overridden)
        PhantomProcessStatus.NOT_APPLICABLE -> context.getString(R.string.phantom_instructions_not_applicable)
    }

    fun gateInstructions(context: Context, status: PhantomProcessStatus): String = when (status) {
        PhantomProcessStatus.ENABLED, PhantomProcessStatus.UNSET, PhantomProcessStatus.UNREADABLE -> fixSentence(context)
        else -> instructions(context, status)
    }

    fun fixSentence(context: Context, sdk: Int = Build.VERSION.SDK_INT): String =
        context.getString(if (hasDeveloperToggle(sdk)) R.string.phantom_fix_toggle else R.string.phantom_fix_no_toggle)

    /** English: for the device report. */
    fun reportValue(status: PhantomProcessStatus): String = when (status) {
        PhantomProcessStatus.DISABLED -> "disabled (good - the OS will not kill the session's children)"
        PhantomProcessStatus.OVERRIDDEN -> "overridden (manual override applied by user)"
        PhantomProcessStatus.ENABLED -> "ENABLED (the OS may kill the session with no log; turn off Restrict child processes)"
        PhantomProcessStatus.UNSET -> "not set (ROM default applies; DroidDeck requires an explicit disabled value)"
        PhantomProcessStatus.UNREADABLE -> "unreadable (DroidDeck could not verify the setting)"
        PhantomProcessStatus.NOT_APPLICABLE -> "not applicable before Android 12"
    }
}
