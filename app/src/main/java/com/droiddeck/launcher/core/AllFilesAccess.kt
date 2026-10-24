package com.droiddeck.launcher.core

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
        if (granted() || activity.isFinishing) return false
        AlertDialog.Builder(activity)
            .setTitle(R.string.all_files_title)
            .setMessage(R.string.all_files_message)
            .setPositiveButton(R.string.all_files_allow) { _, _ -> openSettings(activity) }
            .setNegativeButton(R.string.all_files_later, null)
            .show()
        return true
    }

    fun openSettings(activity: Activity) {
        val forApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + activity.packageName))
        try {
            activity.startActivity(forApp)
        } catch (e: ActivityNotFoundException) {
            runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }
}
