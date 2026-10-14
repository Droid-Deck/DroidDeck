package com.droiddeck.launcher

import android.app.ActivityOptions
import android.app.role.RoleManager
import android.graphics.Bitmap
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.graphics.drawable.toBitmap
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState

object HomeApp {
    data class LaunchableApp(
        val label: String,
        val packageName: String,
        val className: String,
        val icon: Bitmap?,
    )

    fun isHomeScreenEnabled(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(ComponentName(context, HomeActivity::class.java)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setHomeScreenEnabled(context: Context, enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, HomeActivity::class.java),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    fun isDefault(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles?.isRoleAvailable(RoleManager.ROLE_HOME) == true) {
                return roles.isRoleHeld(RoleManager.ROLE_HOME)
            }
        }
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addCategory(Intent.CATEGORY_DEFAULT)
        return context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == context.packageName
    }

    fun defaultLabel(context: Context): String? {
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addCategory(Intent.CATEGORY_DEFAULT)
        val resolved = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY) ?: return null
        val activity = resolved.activityInfo ?: return null
        val label = runCatching { pm.getApplicationLabel(activity.applicationInfo).toString() }.getOrNull()
        return label?.trim()?.takeIf { it.isNotEmpty() }
            ?: resolved.loadLabel(pm)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun roleRequestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val roles = context.getSystemService(RoleManager::class.java) ?: return null
        if (!roles.isRoleAvailable(RoleManager.ROLE_HOME) || roles.isRoleHeld(RoleManager.ROLE_HOME)) return null
        return roles.createRequestRoleIntent(RoleManager.ROLE_HOME)
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_HOME_SETTINGS)

    fun launchableApps(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launch, 0)
            .asSequence()
            .mapNotNull { result ->
                val activity = result.activityInfo ?: return@mapNotNull null
                if (!activity.exported || activity.packageName == context.packageName) return@mapNotNull null
                val label = result.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { activity.packageName }
                val icon = runCatching { result.loadIcon(pm).toBitmap(48, 48) }.getOrNull()
                LaunchableApp(label, activity.packageName, activity.name, icon)
            }
            .distinctBy { it.packageName }
            .toList()
        return apps.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun launch(context: Context, app: LaunchableApp, displayId: Int? = null) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(app.packageName, app.className))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (displayId == null) {
            context.startActivity(intent)
            return
        }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        context.startActivity(intent, options.toBundle())
    }

    fun isActiveSteamSession(): Boolean =
        SessionState.running && SessionState.mode == SessionService.MODE_STEAM

    fun openSystemHomeSettings(context: Context) {
        try {
            context.startActivity(settingsIntent())
        } catch (_: Exception) {
            val request = roleRequestIntent(context)
            if (request != null) context.startActivity(request)
        }
    }
}
