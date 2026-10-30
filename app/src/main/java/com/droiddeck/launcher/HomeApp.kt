package com.droiddeck.launcher

import android.app.ActivityOptions
import android.app.role.RoleManager
import android.graphics.Bitmap
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.graphics.drawable.toBitmap
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import kotlin.math.roundToInt

object HomeApp {
    private const val ICON_DP = 72

    data class LaunchableApp(
        val label: String,
        val packageName: String,
        val className: String,
        val icon: Bitmap?,
        val system: Boolean = false,
        val game: Boolean = false,
        val installedAt: Long = 0L,
        val updatedAt: Long = 0L,
        val user: UserHandle? = null,
        val userSerial: Long = 0L,
    ) {
        val component get() = ComponentName(packageName, className)
        val key get() = "$packageName/$className" + if (userSerial != 0L) "#$userSerial" else ""
        val work get() = userSerial != 0L
    }

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
        val launcher = context.getSystemService(LauncherApps::class.java)
        val users = context.getSystemService(UserManager::class.java)
        val pm = context.packageManager
        val own = Process.myUserHandle()
        val iconPx = (ICON_DP * context.resources.displayMetrics.density).roundToInt()
        val apps = users.userProfiles.flatMap { user ->
            val serial = if (user == own) 0L else users.getSerialNumberForUser(user)
            runCatching { launcher.getActivityList(null, user) }.getOrDefault(emptyList()).mapNotNull { info ->
                val component = info.componentName
                if (component.packageName == context.packageName && user == own) return@mapNotNull null
                val label = info.label?.toString()?.trim().orEmpty().ifEmpty { component.packageName }
                val icon = runCatching { info.getBadgedIcon(0).toBitmap(iconPx, iconPx) }.getOrNull()
                val app = info.applicationInfo
                val updated = if (user == own) runCatching { pm.getPackageInfo(component.packageName, 0).lastUpdateTime }.getOrNull() else null
                @Suppress("DEPRECATION")
                val game = app.category == ApplicationInfo.CATEGORY_GAME || app.flags and ApplicationInfo.FLAG_IS_GAME != 0
                LaunchableApp(
                    label, component.packageName, component.className, icon,
                    system = app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                    game = game,
                    installedAt = info.firstInstallTime,
                    updatedAt = updated ?: info.firstInstallTime,
                    user = user,
                    userSerial = serial,
                )
            }
        }
        return apps.distinctBy { it.key }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun launch(context: Context, app: LaunchableApp, displayId: Int? = null, bounds: Rect? = null) {
        val options = displayId?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }
        val launcher = context.getSystemService(LauncherApps::class.java)
        launcher.startMainActivity(app.component, app.user ?: Process.myUserHandle(), bounds, options)
    }

    fun canUseShortcuts(context: Context): Boolean =
        runCatching { context.getSystemService(LauncherApps::class.java).hasShortcutHostPermission() }.getOrDefault(false)

    fun shortcuts(context: Context, app: LaunchableApp): List<ShortcutInfo> {
        if (!canUseShortcuts(context)) return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(app.packageName)
            .setActivity(app.component)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
        return runCatching { context.getSystemService(LauncherApps::class.java).getShortcuts(query, app.user ?: Process.myUserHandle()) }
            .getOrNull().orEmpty()
            .filter { it.isEnabled }
            .sortedWith(compareBy<ShortcutInfo> { if (it.isDeclaredInManifest) 0 else 1 }.thenBy { it.rank })
            .take(5)
    }

    fun shortcutIcon(context: Context, info: ShortcutInfo, px: Int = (ICON_DP * context.resources.displayMetrics.density).roundToInt()): Bitmap? = runCatching {
        context.getSystemService(LauncherApps::class.java)
            .getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi)?.toBitmap(px, px)
    }.getOrNull()

    fun startShortcut(context: Context, info: ShortcutInfo) {
        val task = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()?.taskId
        task?.let { com.droiddeck.launcher.ui.DualScreenPanel.yieldTo(it, true) }
        try {
            context.getSystemService(LauncherApps::class.java).startShortcut(info, null, null)
        } catch (e: Exception) {
            task?.let { com.droiddeck.launcher.ui.DualScreenPanel.yieldTo(it, false) }
            throw e
        }
    }

    fun openAppInfo(context: Context, app: LaunchableApp) {
        context.getSystemService(LauncherApps::class.java)
            .startAppDetailsActivity(app.component, app.user ?: Process.myUserHandle(), null, null)
    }

    fun canUninstall(app: LaunchableApp): Boolean = !app.system && !app.work

    fun uninstall(context: Context, app: LaunchableApp) {
        context.startActivity(
            Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
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
