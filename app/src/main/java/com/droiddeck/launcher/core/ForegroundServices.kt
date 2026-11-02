package com.droiddeck.launcher.core

import android.app.Notification
import android.app.Service
import android.os.Build
import android.util.Log

object ForegroundServices {
    fun start(service: Service, id: Int, notification: Notification, types: Int = 0): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29 && types != 0) service.startForeground(id, notification, types)
        else service.startForeground(id, notification)
        true
    } catch (error: RuntimeException) {
        if (Build.VERSION.SDK_INT < 31 || error !is android.app.ForegroundServiceStartNotAllowedException) throw error
        Log.w("ForegroundServices", "Foreground work cannot start in the current lifecycle", error)
        false
    }
}
