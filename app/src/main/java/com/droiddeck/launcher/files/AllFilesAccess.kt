package com.droiddeck.launcher.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

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
}
