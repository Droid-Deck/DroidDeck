package com.droiddeck.launcher.gpu

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionEvents
import java.io.File

/** Process-wide cursor survives Activity recreation; a new session ignores older log errors. */
internal object SteamVrHealth {
    private var session: String? = null
    private var log: SteamVrDeviceLossLog? = null

    @Synchronized fun begin(context: Context, sessionId: String?) {
        if (sessionId == session && log != null) return
        session = sessionId
        log = SteamVrDeviceLossLog(File(LinuxRuntime.rootDir(context),
            "root/.local/share/Steam/logs/vrcompositor.txt"))
    }

    @Synchronized fun deviceLost(): Boolean = log?.poll() == true
}

internal class SteamVrRecovery(private val activity: Activity, private val foreground: () -> Boolean) {
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var handled = false
    private var countdown: Runnable? = null

    fun detected() {
        if (handled || !foreground() || activity.isFinishing || activity.isDestroyed) return
        handled = true
        SessionEvents.record("steamvr.device_lost")
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val automatic = SteamVrRecoveryPolicy.canRestartAutomatically(prefs.getLong(LAST_ATTEMPT, 0), System.currentTimeMillis())
        var seconds = 8
        fun message() = if (automatic) activity.getString(R.string.steamvr_recovery_countdown, seconds)
            else activity.getString(R.string.steamvr_recovery_repeated)
        val prompt = AlertDialog.Builder(activity)
            .setTitle(R.string.steamvr_recovery_title)
            .setMessage(message())
            .setPositiveButton(R.string.steamvr_recovery_restart) { _, _ -> restart() }
            .setNegativeButton(R.string.steamvr_recovery_cancel) { _, _ -> }
            .create()
        prompt.setOnDismissListener { countdown?.let(handler::removeCallbacks); countdown = null; dialog = null }
        dialog = prompt
        // A touch meant for the game must not silently dismiss the recovery controls.
        prompt.setCanceledOnTouchOutside(false)
        prompt.show()
        if (automatic) {
            countdown = object : Runnable {
                override fun run() {
                    if (!foreground() || activity.isFinishing || activity.isDestroyed) { handled = false; prompt.dismiss(); return }
                    if (--seconds <= 0) { prompt.dismiss(); restart(); return }
                    prompt.setMessage(message())
                    handler.postDelayed(this, 1000)
                }
            }.also { handler.postDelayed(it, 1000) }
        }
    }

    private fun restart() {
        if (!foreground()) return
        // Commit before crossing processes so a repeated fault cannot create a restart loop.
        if (!activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(LAST_ATTEMPT, System.currentTimeMillis()).commit()) return
        SessionEvents.record("steamvr.recovery_requested")
        activity.startActivity(Intent(activity, SteamVrRestartActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun close() { countdown?.let(handler::removeCallbacks); countdown = null; dialog?.dismiss() }

    companion object {
        internal const val PREFS = "steamvr_recovery"
        internal const val LAST_ATTEMPT = "lastAttempt"
    }
}
