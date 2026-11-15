package com.droiddeck.launcher.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.droiddeck.launcher.HomeApp
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.shortcuts.Relay
import java.io.File

object SteamIntegration {
    const val ACTION_SETUP = "com.droiddeck.launcher.STEAM_INTEGRATION"
    const val EXTRA_FEATURE = "integration_feature"
    const val SHORTCUTS_STATUS = "com.droiddeck.launcher.SHORTCUTS_INSTALL_STATUS"
    private const val SHORTCUTS_ASSET = "shortcuts.apk"
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
        shortcuts = shortcutsEnabled(context),
        shortcutsRestricted = restrictedSettings(context),
        suggested = !SessionPrefs.sessionPrefs(context).getBoolean("steam_integration_seen", false),
    )

    private fun restrictedSettings(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return false
        val mode = runCatching {
            val uid = context.packageManager.getApplicationInfo(Relay.PACKAGE, 0).uid
            context.getSystemService(android.app.AppOpsManager::class.java)
                .unsafeCheckOpNoThrow("android:access_restricted_settings", uid, Relay.PACKAGE)
        }.getOrDefault(android.app.AppOpsManager.MODE_ALLOWED)
        return mode == android.app.AppOpsManager.MODE_ERRORED || mode == android.app.AppOpsManager.MODE_IGNORED
    }

    private val shortcutsComponent = ComponentName(Relay.PACKAGE, Relay.SERVICE)

    fun shortcutsEnabled(context: Context): Boolean {
        val enabled = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull().orEmpty()
        return enabled.split(':').any { ComponentName.unflattenFromString(it.trim()) == shortcutsComponent }
    }

    fun shortcutsListed(context: Context): Boolean =
        context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            ?.installedAccessibilityServiceList.orEmpty()
            .any { ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) == shortcutsComponent }

    fun bundledShortcuts(context: Context): File {
        val target = File(context.cacheDir, SHORTCUTS_ASSET)
        context.assets.open(SHORTCUTS_ASSET).use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    fun shortcutsInstalled(context: Context, bundled: File): Boolean {
        val packages = context.packageManager
        @Suppress("DEPRECATION")
        val installed = runCatching { packages.getPackageInfo(Relay.PACKAGE, 0).versionCode }.getOrNull() ?: return false
        @Suppress("DEPRECATION")
        val shipped = packages.getPackageArchiveInfo(bundled.absolutePath, 0)?.versionCode ?: return true
        return installed >= shipped
    }

    fun dismissSuggestion(context: Context) {
        SessionPrefs.sessionPrefs(context).edit().putBoolean("steam_integration_seen", true).apply()
    }

    fun shortcutsAppInfo() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${Relay.PACKAGE}"))

    fun brightnessSettings(context: Context) = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
        Uri.parse("package:${context.packageName}"))

    fun shortcutSettings(context: Context): Intent {
        val service = shortcutsComponent.flattenToString()
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", service)
            .putExtra(":settings:show_fragment_args", android.os.Bundle().apply { putString(":settings:fragment_args_key", service) })
    }
}
