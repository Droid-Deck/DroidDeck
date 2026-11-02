package com.droiddeck.launcher.files

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.AddedExes
import com.droiddeck.launcher.stores.StoreInstallRoot

object AllFilesAccess {
    fun granted(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun usesSharedStorage(context: Context): Boolean {
        val own = (listOf(context.filesDir.parentFile) + context.getExternalFilesDirs(null).map { it?.parentFile })
            .filterNotNull().map { it.absolutePath + "/" }
        fun shared(path: String) = own.none { (path + "/").startsWith(it) }
        return AddedExes.list(context).any { shared(it.folder) } ||
            StoreInstallRoot.roots(context).any { shared(it.absolutePath) }
    }

    fun ask(activity: Activity): Boolean {
        if (granted() || activity.isFinishing || activity.isDestroyed) return false
        AlertDialog.Builder(activity)
            .setTitle(R.string.setup_all_files)
            .setMessage(R.string.fm_drive_access_text)
            .setPositiveButton(R.string.upd_open_settings) { _, _ -> openSettings(activity) }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
        return true
    }

    fun missing(sdk: Int = Build.VERSION.SDK_INT, granted: () -> Boolean = { AllFilesAccess.granted() }): Boolean =
        sdk >= Build.VERSION_CODES.R && !granted()

    fun openSettings(activity: Activity) = open(activity)

    fun open(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val forApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(forApp)
        } catch (e: ActivityNotFoundException) {
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
