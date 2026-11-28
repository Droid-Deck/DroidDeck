package com.droiddeck.launcher.input

import android.os.Handler
import android.os.Looper

class BackTaps(
    private val windowMs: () -> Long,
    private val atOnce: () -> Boolean,
    private val onTap: (Boolean) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var deferred = false
    var pending = false
        private set
    private val expire = Runnable {
        pending = false
        if (deferred) onTap(false)
    }

    fun tap() {
        if (pending) {
            cancel()
            onTap(true)
            return
        }
        pending = true
        deferred = !atOnce()
        handler.postDelayed(expire, windowMs())
        if (!deferred) onTap(false)
    }

    fun hold() = handler.removeCallbacks(expire)

    fun cancel() {
        handler.removeCallbacks(expire)
        pending = false
    }
}
