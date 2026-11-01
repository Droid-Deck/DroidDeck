package com.droiddeck.launcher.core

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.droiddeck.launcher.HomeApp
import com.droiddeck.launcher.input.HomeButtonService
import com.droiddeck.launcher.session.SessionPrefs

object SteamIntegration {
    const val ACTION_SETUP = "com.droiddeck.launcher.STEAM_INTEGRATION"
    const val EXTRA_FEATURE = "integration_feature"
    enum class Feature { WIFI, BRIGHTNESS, HOME, SHORTCUTS }

    data class State(
        val wifi: Boolean = false,
        val locationPermission: Boolean = false,
        val locationOn: Boolean = false,
        val locationBlocked: Boolean = false,
        val brightness: Boolean = false,
        val home: Boolean = false,
        val shortcuts: Boolean = false,
        val shortcutsRestricted: Boolean = false,
        val shortcutsTried: Boolean = false,
        val suggested: Boolean = true,
    )

    fun read(context: Context) = State(
        wifi = SessionPrefs.wifiDiscoveryEnabled(context),
        locationPermission = WifiDiscovery.permissionGranted(context),
        locationOn = WifiDiscovery.locationEnabled(context),
        brightness = Settings.System.canWrite(context),
        home = HomeApp.isDefault(context),
        shortcuts = context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                val service = it.resolveInfo.serviceInfo
                ComponentName(service.packageName, service.name) == ComponentName(context, HomeButtonService::class.java)
            },
        shortcutsRestricted = restrictedSettings(context),
        suggested = !SessionPrefs.sessionPrefs(context).getBoolean("steam_integration_seen", false),
    )

    // Android 13+ blocks accessibility services for sideloaded APKs until the user allows restricted settings in App info.
    // MODE_DEFAULT is inconclusive on Android 15, so only an explicit denial counts here.
    private fun restrictedSettings(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return false
        val mode = runCatching {
            context.getSystemService(android.app.AppOpsManager::class.java)
                .unsafeCheckOpNoThrow("android:access_restricted_settings", android.os.Process.myUid(), context.packageName)
        }.getOrDefault(android.app.AppOpsManager.MODE_ALLOWED)
        return mode == android.app.AppOpsManager.MODE_ERRORED || mode == android.app.AppOpsManager.MODE_IGNORED
    }

    private fun shortcutsComponent(context: Context) = ComponentName(context, HomeButtonService::class.java)

    fun shortcutsChosen(context: Context): Boolean = shortcutsSelection(context) == true

    private fun shortcutsSelection(context: Context): Boolean? {
        val target = shortcutsComponent(context)
        val enabled = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrElse { return null }.orEmpty()
        return enabled.split(':').any { ComponentName.unflattenFromString(it.trim()) == target }
    }

    fun setShortcutsListed(context: Context, listed: Boolean) {
        val packages = context.packageManager
        val component = shortcutsComponent(context)
        val state = if (listed) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching {
            if (packages.getComponentEnabledSetting(component) != state) {
                packages.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
        }
    }

    fun syncShortcutsListing(context: Context) {
        shortcutsSelection(context)?.let { setShortcutsListed(context, it) }
    }

    fun dismissSuggestion(context: Context) {
        SessionPrefs.sessionPrefs(context).edit().putBoolean("steam_integration_seen", true).apply()
    }

    fun brightnessSettings(context: Context) = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
        Uri.parse("package:${context.packageName}"))

    // ACCESSIBILITY_DETAILS_SETTINGS is restricted to system apps; the fragment args key highlights our entry in the list.
    fun shortcutSettings(context: Context): Intent {
        val service = ComponentName(context, HomeButtonService::class.java).flattenToString()
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", service)
            .putExtra(":settings:show_fragment_args", android.os.Bundle().apply { putString(":settings:fragment_args_key", service) })
    }
}
