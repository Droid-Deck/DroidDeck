package com.droiddeck.launcher.stores.epic

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Browser
import android.util.Log
import android.widget.Toast
import com.droiddeck.launcher.DeviceCodeActivity
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoresState
import org.json.JSONObject

/**
 * Epic's login page in the browser ends on the launcher's authorization code shown as text, since
 * its page script keeps the launcher client from being sent back to an app; the user copies it and
 * DroidDeck takes it from the clipboard when it is in front again.
 */
internal object EpicCodeLogin {
    private const val TAG = "EpicCodeLogin"
    private const val CHROME = "com.android.chrome"
    private const val PENDING_MS = 15 * 60 * 1000L
    private val ANY_CODE = Regex("(?<![0-9a-fA-F])[0-9a-fA-F]{32}(?![0-9a-fA-F])")

    @Volatile private var pendingUntil = 0L
    private val main = Handler(Looper.getMainLooper())

    fun begin(context: Context) {
        val app = context.applicationContext
        Thread({
            val url = EpicNative.call("auth.config")?.optString("startUrl").orEmpty()
            if (url.isEmpty()) {
                Log.w(TAG, "no sign-in page")
                main.post { failed(app) }
                return@Thread
            }
            pendingUntil = System.currentTimeMillis() + PENDING_MS
            main.post {
                DeviceCodeActivity.onPaste = { ctx, text -> offer(ctx, text) }
                val floated = DeviceCodeActivity.show(app, "", url, 0, Store.EPIC.label, app.getString(R.string.epic_copy_code), paste = true)
                if (!floated && !openBrowser(app, url)) { pendingUntil = 0; failed(app) }
            }
        }, "epic-code-login").apply { isDaemon = true }.start()
    }

    /** Called when a DroidDeck window gains focus, the only moment Android lets it read the clipboard. */
    fun resumed(activity: Activity) {
        if (System.currentTimeMillis() > pendingUntil) return
        val text = runCatching {
            activity.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString()
        }.getOrNull() ?: return
        offer(activity, text)
    }

    private fun offer(context: Context, text: String): Boolean {
        if (System.currentTimeMillis() > pendingUntil) return false
        val trimmed = text.trim()
        val code = EpicNative.call("auth.codeFromPage", JSONObject().put("text", trimmed))?.optString("code").orEmpty()
            .ifEmpty { ANY_CODE.find(trimmed)?.value.orEmpty() }
        if (code.isEmpty()) return false
        pendingUntil = 0
        val app = context.applicationContext
        runCatching { app.getSystemService(ClipboardManager::class.java)?.clearPrimaryClip() }
        DeviceCodeActivity.close()
        Thread({
            val problem = signIn(app, code)
            main.post {
                if (problem == null) StoresState.refresh(app)
                else {
                    // Epic's codes last only minutes; the page in the signed-in browser hands out a fresh one.
                    Toast.makeText(app, problem, Toast.LENGTH_LONG).show()
                    begin(app)
                }
            }
        }, "epic-code-login").apply { isDaemon = true }.start()
        return true
    }

    private fun signIn(app: Context, code: String): String? {
        val answer = EpicNative.call("auth.signIn", JSONObject().put("code", code))
        val tokens = answer?.let(EpicCredentialStore.TokenResult::from)
        if (tokens == null) {
            Log.w(TAG, "code exchange failed: ${answer?.optString("error").orEmpty().ifEmpty { "no answer" }}")
            return app.getString(R.string.stores_login_error_generic)
        }
        return try {
            EpicCredentialStore.save(app, tokens, merge = false)
            null
        } catch (e: Exception) {
            Log.w(TAG, "saving the sign-in failed: ${e.javaClass.simpleName}")
            app.getString(R.string.stores_login_error_generic)
        }
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

    private fun failed(app: Context) = Toast.makeText(app, R.string.stores_login_error_generic, Toast.LENGTH_LONG).show()
}
