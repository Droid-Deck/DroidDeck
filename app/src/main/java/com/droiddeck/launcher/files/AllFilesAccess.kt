package com.droiddeck.launcher.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionState

object AllFilesAccess {
    fun missing(sdk: Int = Build.VERSION.SDK_INT, granted: () -> Boolean = { Environment.isExternalStorageManager() }): Boolean =
        sdk >= Build.VERSION_CODES.R && !granted()

    fun open(context: Context) {
        val app = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(app) }.recoverCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Android applies newly granted file access to a running process only after a restart:
    a drive that stays unreadable with the permission granted needs this, not another trip
    to settings. Refuses while a session runs so a guest is never killed underneath. */
    fun restartApp(context: Context) {
        if (SessionState.running) {
            Toast.makeText(context, R.string.fm_restart_while_running, Toast.LENGTH_LONG).show()
            return
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        context.startActivity(Intent.makeRestartActivityTask(launch.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Runtime.getRuntime().exit(0)
    }
}
