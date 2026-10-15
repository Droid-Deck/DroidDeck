package com.droiddeck.launcher.gpu

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.Gravity
import android.widget.TextView
import com.droiddeck.launcher.R
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.session.SessionService
import org.json.JSONObject

/** A visible, private process survives destruction of the app's native graphics context. */
class SteamVrRestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            setText(R.string.steamvr_recovery_restarting)
            gravity = Gravity.CENTER
            textSize = 22f
        })
        Thread({ recover() }, "steamvr-process-recovery").start()
    }

    private fun recover() {
        try {
            val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val main = manager.runningAppProcesses?.firstOrNull {
                it.processName == packageName && it.uid == Process.myUid() && it.pid != Process.myPid()
            } ?: error("DroidDeck process is unavailable")
            val uri = Uri.parse("content://$packageName.agent")
            contentResolver.call(uri, "stop", null, null)
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val answer = contentResolver.call(uri, "state", null, null)?.getString("json")
                val session = answer?.let(::JSONObject)?.optJSONObject("session")
                if (session?.optString("phase") in listOf("IDLE", "FAILED") &&
                    session?.optBoolean("artifactsComplete") == true) break
                SystemClock.sleep(250)
            }
            // Only the verified main process, never a caller-supplied PID or an external app.
            Process.killProcess(main.pid)
            val exited = SystemClock.elapsedRealtime() + 5000
            while (java.io.File("/proc/${main.pid}").exists() && SystemClock.elapsedRealtime() < exited) SystemClock.sleep(50)
            check(!java.io.File("/proc/${main.pid}").exists()) { "DroidDeck process did not exit" }
            val settings = java.io.File(com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(this),
                "root/.local/share/Steam/config/steamvr.vrsettings")
            runCatching { SteamVrRecoverySettings.unblockHeadset(settings) }
                .onSuccess { if (it) android.util.Log.i("SteamVrRecovery", "Cleared the DroidDeck HMD crash block before restarting SteamVR") }
                .onFailure { android.util.Log.w("SteamVrRecovery", "Could not read the SteamVR settings; restarting with them unchanged", it) }
            runOnUiThread {
                startActivity(Intent(this, SessionActivity::class.java)
                    .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_STEAM)
                    .putExtra(SessionActivity.EXTRA_STEAMVR, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finishAndRemoveTask()
            }
        } catch (e: Exception) {
            android.util.Log.e("SteamVrRecovery", "Could not restart DroidDeck", e)
            runOnUiThread {
                setContentView(TextView(this).apply {
                    setText(R.string.steamvr_recovery_failed)
                    gravity = Gravity.CENTER
                    textSize = 22f
                })
            }
        }
    }
}
