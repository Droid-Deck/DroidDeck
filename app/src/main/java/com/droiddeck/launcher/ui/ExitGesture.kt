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
    private const val HOLD = 0.7f
    private const val RELEASE = 0.4f
    private const val MERGE_MS = 150L

    var armed = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) resetLeft()
        }

    var onRail = false
        set(value) {
            if (field == value) return
            field = value
            resetLeft()
        }

    private var backCount = 0
    private var backAt = 0L
    private var leftCount = 0
    private var leftAt = 0L
    private var leftHeld = false
    private var leftReleasedAt = 0L
    private var stickLeft = false
    private var dpadLeft = false

    fun back(): Int {
        val now = SystemClock.uptimeMillis()
        backCount = if (now - backAt <= WINDOW_MS) backCount + 1 else 1
        backAt = now
        return backCount
    }

    fun resetBack() {
        backCount = 0
        backAt = 0L
    }

    fun stick(x: Float): Int {
        if (!armed || !onRail) return 0
        val next = if (x <= -HOLD) true else if (x >= -RELEASE) false else stickLeft
        if (next == stickLeft) return 0
        stickLeft = next
        return pulse()
    }

    fun dpad(down: Boolean): Int {
        if (!armed || !onRail) return 0
        if (dpadLeft == down) return 0
        dpadLeft = down
        return pulse()
    }

    fun resetLeft() {
        leftCount = 0
        leftAt = 0L
        leftHeld = false
        leftReleasedAt = 0L
        stickLeft = false
        dpadLeft = false
    }

    // 摇杆轴与 hat 派生的 DPAD 会重复上报同一次推杆，回中后留一小段合并窗口，避免一次动作计两次。
    private fun pulse(): Int {
        val now = SystemClock.uptimeMillis()
        if (leftHeld && leftReleasedAt != 0L && now - leftReleasedAt >= MERGE_MS) {
            leftHeld = false
            leftReleasedAt = 0L
        }
        if (stickLeft || dpadLeft) {
            leftReleasedAt = 0L
            if (leftHeld) return 0
            leftHeld = true
            leftCount = if (now - leftAt <= WINDOW_MS) leftCount + 1 else 1
            leftAt = now
            return leftCount
        }
        if (leftHeld && leftReleasedAt == 0L) leftReleasedAt = now
        return 0
    }
}
