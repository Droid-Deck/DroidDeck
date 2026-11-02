package com.droiddeck.launcher.core

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

object AppPermissions {
    fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun localNetwork(context: Context): Boolean = Build.VERSION.SDK_INT < 37 ||
        granted(context, Manifest.permission.ACCESS_LOCAL_NETWORK)

    fun startup(activity: Activity): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 37 && !localNetwork(activity) &&
            !activity.getSharedPreferences("runtime-permissions", Context.MODE_PRIVATE).getBoolean("local-network", false)) {
            add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            activity.getSharedPreferences("runtime-permissions", Context.MODE_PRIVATE).edit().putBoolean("local-network", true).apply()
        }
        if (Build.VERSION.SDK_INT >= 33 && !granted(activity, Manifest.permission.POST_NOTIFICATIONS) &&
            !activity.getSharedPreferences("stores", Context.MODE_PRIVATE).getBoolean("asked_notifications", false)) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            activity.getSharedPreferences("stores", Context.MODE_PRIVATE).edit().putBoolean("asked_notifications", true).apply()
        }
    }

    fun requestLocalNetwork(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT < 37) return
        val prefs = activity.getSharedPreferences("runtime-permissions", Context.MODE_PRIVATE)
        if (localNetwork(activity) || prefs.getBoolean("local-network", false) &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_LOCAL_NETWORK)) {
            openSettings(activity)
        } else {
            prefs.edit().putBoolean("local-network", true).apply()
            activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK), requestCode)
        }
    }

    fun openSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
