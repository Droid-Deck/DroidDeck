package com.droiddeck.launcher.core

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.droiddeck.launcher.R

enum class PhantomProcessStatus {
    NOT_APPLICABLE,
    DISABLED,
    ENABLED,
    UNSET,
    UNREADABLE,
}

object PhantomProcessLimit {
    private const val SETTING = "settings_enable_monitor_phantom_procs"
    private const val MAX_PHANTOM = "2147483647"
    const val GLOBAL_QUERY = "settings get global $SETTING"
    const val OVERRIDE_QUERY = "getprop persist.sys.fflag.override.$SETTING"
    const val DEVICE_CONFIG_QUERY = "device_config get activity_manager max_phantom_processes"

    fun usesDeviceConfig(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= Build.VERSION_CODES.S

    fun shellCommands(enabled: Boolean, sdk: Int = Build.VERSION.SDK_INT): List<String> = buildList {
        if (usesDeviceConfig(sdk)) {
            add(if (enabled) "device_config delete activity_manager max_phantom_processes"
                else "device_config put activity_manager max_phantom_processes $MAX_PHANTOM")
        }
        add("settings put global $SETTING ${if (enabled) "true" else "false"}")
    }

    fun verificationMatches(
        global: String?,
        maxPhantom: String?,
        globalReadable: Boolean,
        deviceConfigReadable: Boolean,
        enabled: Boolean,
        sdk: Int = Build.VERSION.SDK_INT,
        override: String? = null,
        overrideReadable: Boolean = true,
    ): Boolean = if (enabled) {
        (sdk == Build.VERSION_CODES.S || (globalReadable && normalized(global) == "true")) &&
            (!usesDeviceConfig(sdk) || (deviceConfigReadable && validMaximum(maxPhantom) && normalized(maxPhantom) != MAX_PHANTOM))
    } else {
        status(global, override, maxPhantom, globalReadable, deviceConfigReadable, overrideReadable, sdk) == PhantomProcessStatus.DISABLED
    }

    fun adbCommand(enabled: Boolean, sdk: Int = Build.VERSION.SDK_INT): String =
        "adb shell \"${shellCommands(enabled, sdk).joinToString("; ")}\""

    fun adbCommand(): String = adbCommand(false)

    internal data class Readings(
        val global: String?,
        val override: String?,
        val maxPhantom: String?,
        val globalReadable: Boolean,
        val overrideReadable: Boolean,
        val deviceConfigReadable: Boolean,
    )

    internal data class VerificationSnapshot(
        val readings: Readings,
        val observed: Readings,
        val sdk: Int,
        val fingerprint: String,
        val elapsedRealtime: Long,
    )

    // activity_manager is private to DeviceConfig; a recent shell read can bridge its permission check.
    @Volatile private var verifiedSnapshot: VerificationSnapshot? = null
    private const val VERIFICATION_TTL_MS = 60_000L

    fun clearVerification() {
        verifiedSnapshot = null
    }

    fun rememberVerification(
        context: Context,
        global: String?,
        maxPhantom: String?,
        globalReadable: Boolean,
        deviceConfigReadable: Boolean,
        override: String? = null,
        overrideReadable: Boolean = true,
        sdk: Int = Build.VERSION.SDK_INT,
    ) {
        verifiedSnapshot = VerificationSnapshot(
            Readings(global, override, maxPhantom, globalReadable, overrideReadable, deviceConfigReadable),
            readSettings(context), sdk, Build.FINGERPRINT, SystemClock.elapsedRealtime(),
        )
    }

    fun read(context: Context, sdk: Int = Build.VERSION.SDK_INT): PhantomProcessStatus {
        if (sdk < Build.VERSION_CODES.S) return PhantomProcessStatus.NOT_APPLICABLE
        val current = readSettings(context)
        val remembered = verifiedSnapshot?.let {
            snapshotStatus(it, current, sdk, Build.FINGERPRINT, SystemClock.elapsedRealtime())
        }
        return remembered ?: status(current, sdk)
    }

    internal fun snapshotStatus(
        snapshot: VerificationSnapshot,
        current: Readings,
        sdk: Int,
        fingerprint: String,
        elapsedRealtime: Long,
    ): PhantomProcessStatus? {
        if (sdk != snapshot.sdk || fingerprint != snapshot.fingerprint ||
            elapsedRealtime - snapshot.elapsedRealtime !in 0..VERIFICATION_TTL_MS || current.deviceConfigReadable ||
            current.globalReadable != snapshot.observed.globalReadable ||
            current.overrideReadable != snapshot.observed.overrideReadable ||
            normalized(current.global) != normalized(snapshot.observed.global) ||
            normalized(current.override) != normalized(snapshot.observed.override)) return null
        return status(current.copy(
            maxPhantom = snapshot.readings.maxPhantom,
            deviceConfigReadable = snapshot.readings.deviceConfigReadable,
        ), sdk)
    }

    private fun readSettings(context: Context): Readings {
        val global = runCatching { Settings.Global.getString(context.contentResolver, SETTING) }
        val override = runCatching { systemProperty(OVERRIDE_PROPERTY) }
        val maximum = runCatching {
            Class.forName("android.provider.DeviceConfig")
                .getMethod("getProperty", String::class.java, String::class.java)
                .invoke(null, "activity_manager", "max_phantom_processes") as? String
        }
        return Readings(global.getOrNull(), override.getOrNull(), maximum.getOrNull(),
            global.isSuccess, override.isSuccess, maximum.isSuccess)
    }

    private fun status(readings: Readings, sdk: Int): PhantomProcessStatus = with(readings) {
        status(global, override, maxPhantom, globalReadable, deviceConfigReadable, overrideReadable, sdk)
    }

    fun status(
        global: String?,
        override: String?,
        maxPhantom: String? = null,
        globalReadable: Boolean = true,
        deviceConfigReadable: Boolean = true,
        overrideReadable: Boolean = true,
        sdk: Int = Build.VERSION.SDK_INT,
    ): PhantomProcessStatus {
        val globalValue = normalized(global)
        val flagReadable = globalReadable && (globalValue != null || overrideReadable)
        val flagValue = if (globalValue != null) globalValue else normalized(override)
        val disabled = sdk != Build.VERSION_CODES.S && flagReadable && flag(flagValue) == false
        val unlimited = deviceConfigReadable && normalized(maxPhantom) == MAX_PHANTOM
        if (disabled || unlimited) return PhantomProcessStatus.DISABLED
        if (!deviceConfigReadable || !validMaximum(maxPhantom)) return PhantomProcessStatus.UNREADABLE
        if (sdk == Build.VERSION_CODES.S) return PhantomProcessStatus.ENABLED
        if (!flagReadable || (flagValue != null && flag(flagValue) == null)) return PhantomProcessStatus.UNREADABLE
        return if (flag(flagValue) == true) PhantomProcessStatus.ENABLED else PhantomProcessStatus.UNSET
    }

    private fun normalized(value: String?): String? =
        value?.trim()?.lowercase()?.takeUnless { it.isEmpty() || it == "null" }

    private fun flag(value: String?): Boolean? = when (normalized(value)) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }

    private fun validMaximum(value: String?): Boolean =
        normalized(value).let { it == null || it.toIntOrNull()?.let { maximum -> maximum >= 0 } == true }

    private const val OVERRIDE_PROPERTY = "persist.sys.fflag.override.$SETTING"

    private fun systemProperty(name: String): String? =
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, name) as? String

    fun hasDeveloperToggle(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    fun blocksSteam(status: PhantomProcessStatus): Boolean =
        status != PhantomProcessStatus.NOT_APPLICABLE && status != PhantomProcessStatus.DISABLED

    fun title(context: Context, status: PhantomProcessStatus): String = context.getString(when (status) {
        PhantomProcessStatus.ENABLED -> R.string.phantom_title_on
        PhantomProcessStatus.UNSET -> R.string.phantom_title_unset
        PhantomProcessStatus.UNREADABLE -> R.string.phantom_title_unreadable
        PhantomProcessStatus.DISABLED -> R.string.phantom_title_off
        PhantomProcessStatus.NOT_APPLICABLE -> R.string.phantom_title_not_required
    })

    fun instructions(context: Context, status: PhantomProcessStatus): String = when (status) {
        PhantomProcessStatus.ENABLED, PhantomProcessStatus.UNSET, PhantomProcessStatus.UNREADABLE ->
            context.getString(R.string.phantom_instructions_fix, fixSentence(context))
        PhantomProcessStatus.DISABLED -> context.getString(R.string.phantom_instructions_off)
        PhantomProcessStatus.NOT_APPLICABLE -> context.getString(R.string.phantom_instructions_not_applicable)
    }

    fun gateInstructions(context: Context, status: PhantomProcessStatus): String = when (status) {
        PhantomProcessStatus.ENABLED, PhantomProcessStatus.UNSET, PhantomProcessStatus.UNREADABLE -> fixSentence(context)
        else -> instructions(context, status)
    }

    fun fixSentence(context: Context, sdk: Int = Build.VERSION.SDK_INT): String =
        context.getString(if (hasDeveloperToggle(sdk)) R.string.phantom_fix_toggle else R.string.phantom_fix_no_toggle)

    fun reportValue(status: PhantomProcessStatus): String = when (status) {
        PhantomProcessStatus.DISABLED -> "monitor disabled or process count limit raised (other Android process restrictions still apply)"
        PhantomProcessStatus.ENABLED -> "ENABLED (the OS may kill the session with no log; turn off Restrict child processes)"
        PhantomProcessStatus.UNSET -> "not set (ROM default applies; DroidDeck requires an explicit disabled value)"
        PhantomProcessStatus.UNREADABLE -> "unreadable (DroidDeck could not verify the setting)"
        PhantomProcessStatus.NOT_APPLICABLE -> "not applicable before Android 12"
    }
}
