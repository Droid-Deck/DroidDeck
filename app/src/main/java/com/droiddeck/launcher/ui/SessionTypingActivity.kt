package com.droiddeck.launcher.ui

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Bundle

object SessionTyping {
    private var activity: SessionTypingActivity? = null
    private var launching = false

    fun focusDisplay(context: Context, displayId: Int) {
        if (activity != null || launching) return
        launching = true
        val intent = Intent(context, SessionTypingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        runCatching {
            context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
        }.onFailure { launching = false }
    }

    fun returnFocus(context: Context, task: Int) {
        close()
        if (task < 0) return
        runCatching { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).moveTaskToFront(task, 0) }
    }

    fun close() {
        launching = false
        activity?.finish()
        activity = null
    }

    internal fun attach(host: SessionTypingActivity): Boolean {
        if (!launching) return false
        launching = false
        activity = host
        return true
    }

    internal fun detach(host: SessionTypingActivity) {
        if (activity === host) activity = null
    }
}

class SessionTypingActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionTyping.attach(this)) finish()
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        SessionTyping.detach(this)
        super.onDestroy()
    }
}
