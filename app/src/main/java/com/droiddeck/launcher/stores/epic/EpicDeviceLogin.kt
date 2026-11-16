package com.droiddeck.launcher.stores.epic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Browser
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.droiddeck.launcher.FrontEndActivity
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoresState
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

internal object EpicDeviceLogin {
    private const val TAG = "EpicDeviceLogin"
    private const val CHROME = "com.android.chrome"
    private const val CHANNEL_ID = "epic_sign_in"
    private const val NOTIFICATION_ID = 4109
    private const val NOTICE_TIMEOUT_MS = 60_000L

    private val attempt = AtomicInteger()
    private val main = Handler(Looper.getMainLooper())

    fun begin(context: Context) {
        val app = context.applicationContext
        val mine = attempt.incrementAndGet()
        Thread({ run(app, mine) }, "epic-device-login").apply { isDaemon = true }.start()
    }

    private fun run(app: Context, mine: Int) {
        val start = EpicNative.call("auth.deviceStart")
        val url = start?.optString("url").orEmpty()
        val deviceCode = start?.optString("deviceCode").orEmpty()
        if (url.isEmpty() || deviceCode.isEmpty()) {
            Log.w(TAG, "device sign-in did not start: ${start?.optString("error").orEmpty().ifEmpty { "no answer" }}")
            return fail(app, mine)
        }
        if (!openBrowser(app, url)) return fail(app, mine)
        val intervalMs = start!!.optLong("interval", 10).coerceAtLeast(1) * 1000
        val deadline = System.currentTimeMillis() + start.optLong("expiresIn", 600) * 1000
        while (attempt.get() == mine && System.currentTimeMillis() < deadline) {
            Thread.sleep(intervalMs)
            if (attempt.get() != mine) return
            val answer = EpicNative.call("auth.devicePoll", JSONObject().put("deviceCode", deviceCode)) ?: continue
            if (answer.optBoolean("pending")) continue
            val tokens = EpicCredentialStore.TokenResult.from(answer)
            if (tokens == null) {
                Log.w(TAG, "device sign-in failed: ${answer.optString("error").ifEmpty { "no token" }}")
                return fail(app, mine)
            }
            try {
                EpicCredentialStore.save(app, tokens, merge = false)
            } catch (e: Exception) {
                Log.w(TAG, "saving the sign-in failed: ${e.javaClass.simpleName}")
                return fail(app, mine)
            }
            main.post {
                StoresState.refresh(app)
                bringBack(app, tokens.displayName)
            }
            return
        }
        if (attempt.get() == mine) Log.w(TAG, "device sign-in expired before it was confirmed")
    }

    private fun openBrowser(app: Context, url: String): Boolean {
        for (pkg in listOf(CHROME, null)) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Browser.EXTRA_APPLICATION_ID, app.packageName)
            if (pkg != null) intent.setPackage(pkg)
            if (runCatching { app.startActivity(intent) }.isSuccess) return true
        }
        Log.w(TAG, "no browser took the sign-in")
        return false
    }

    // Android lets an app in the background bring itself forward only while it may draw over
    // other apps; without that, a tap on DroidDeck's own notification is what brings it back.
    private fun bringBack(app: Context, name: String?) {
        val front = Intent(app, FrontEndActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Settings.canDrawOverlays(app) && runCatching { app.startActivity(front) }.isSuccess) return
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        val label = Store.EPIC.label
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, app.getString(R.string.stores_login_title, label), NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(app, NOTIFICATION_ID, front, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = name?.let { app.getString(R.string.setup_signed_in, it) } ?: app.getString(R.string.setup_signed_in_unnamed)
        runCatching {
            manager.notify(NOTIFICATION_ID, Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_session)
                .setContentTitle(label)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setTimeoutAfter(NOTICE_TIMEOUT_MS)
                .setCategory(Notification.CATEGORY_STATUS)
                .build())
        }.onFailure { Log.w(TAG, "could not post the return notice: ${it.javaClass.simpleName}") }
    }

    private fun fail(app: Context, mine: Int) {
        main.post {
            if (attempt.get() == mine) Toast.makeText(app, R.string.stores_login_error_generic, Toast.LENGTH_LONG).show()
        }
    }
}
