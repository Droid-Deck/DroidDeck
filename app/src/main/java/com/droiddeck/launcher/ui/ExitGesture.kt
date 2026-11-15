package com.droiddeck.launcher.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock

internal fun Context.finishTask() {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) {
            c.finishAndRemoveTask()
            return
        }
        c = c.baseContext
    }
}

internal object ExitGesture {
    private const val WINDOW_MS = 2_000L

    private var count = 0
    private var firstAt = 0L

    fun back(): Int {
        val now = SystemClock.uptimeMillis()
        if (count == 0 || now - firstAt > WINDOW_MS) {
            count = 1
            firstAt = now
        } else {
            count += 1
        }
        return count
    }

    fun reset() {
        count = 0
        firstAt = 0L
    }
}
