package com.droiddeck.launcher.session

import android.app.ActivityManager
import android.content.Context
import android.util.Log

object RivalClients {
    private const val TAG = "RivalClients"

    private val PACKAGES = arrayOf(
        "com.xiaoji.egggame",
    )

    fun installed(context: Context): List<String> =
        PACKAGES.filter { pkg ->
            try { context.packageManager.getPackageInfo(pkg, 0); true } catch (e: Throwable) { false }
        }

    fun name(pkg: String): String = if (pkg == "com.xiaoji.egggame") "GameHub" else pkg

    fun stopBeforeSession(context: Context) {
        val rivals = installed(context)
        if (rivals.isEmpty()) return
        Log.w(TAG, "competing Steam client installed: $rivals")
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                for (pkg in rivals) am.killBackgroundProcesses(pkg)
                Log.i(TAG, "asked Android to stop background processes of $rivals")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not stop competing Steam clients", t)
        }
    }
}
