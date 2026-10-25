package com.droiddeck.launcher.gpu

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.droiddeck.launcher.R
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.wayland.WaylandCompositor

internal object GpuProcessRecovery {
    const val EXTRA_SESSION_REQUEST = "gpuRecoverySession"

    fun restartIfNeeded(activity: Activity, request: Intent, lost: Boolean = WaylandCompositor.hasDeviceLost()): Boolean {
        if (!lost) return false
        if (SessionPrefs.gpuDriverMode(activity) == SessionPrefs.GPU_DRIVERS_AUTO) {
            AutoDriverTrial.rollbackDue(activity)?.let { AutoDriverTrial.rollBack(activity, it) }
        }
        // The recovery process will kill us; finish pending driver selection writes first.
        if (!SessionPrefs.sessionPrefs(activity).edit().commit()) {
            Toast.makeText(activity, R.string.steamvr_recovery_failed, Toast.LENGTH_LONG).show()
            return true
        }
        activity.startActivity(Intent(activity, SteamVrRestartActivity::class.java)
            .putExtra(EXTRA_SESSION_REQUEST, Intent(request)))
        return true
    }

    @Suppress("DEPRECATION")
    fun sessionRequest(context: Context, recovery: Intent): Intent {
        val saved = recovery.getParcelableExtra<Intent>(EXTRA_SESSION_REQUEST)
        return (saved?.let { Intent(it) } ?: Intent()
            .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_STEAM)
            .putExtra(SessionActivity.EXTRA_STEAMVR, true))
            .setClass(context, SessionActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
