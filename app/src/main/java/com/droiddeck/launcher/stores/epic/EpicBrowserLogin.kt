package com.droiddeck.launcher.stores.epic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.LoopbackListener
import com.droiddeck.launcher.stores.StoreLoginActivity
import org.json.JSONObject
import java.io.Closeable

internal class EpicBrowserLogin private constructor(
    private val listener: LoopbackListener,
    private val startUrl: String,
    private val redirectUri: String,
    private val context: Context,
    private val onResult: (String?) -> Unit,
) : Closeable {
    private fun open(): Boolean {
        val uri = Uri.parse(startUrl)
        val intents = listOf(CHROME, null).map { pkg ->
            Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).also { if (pkg != null) it.setPackage(pkg) }
        }
        for (intent in intents) {
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        Log.w(TAG, "no browser took the sign-in")
        return false
    }

    private fun complete(outcome: LoopbackListener.Outcome) {
        val problem = when (outcome) {
            is LoopbackListener.Outcome.Code -> EpicLogin.signIn(context, outcome.code, redirectUri)
            LoopbackListener.Outcome.Denied -> context.getString(R.string.stores_login_error_verification)
            LoopbackListener.Outcome.TimedOut -> context.getString(R.string.stores_login_error_generic)
        }
        onResult(problem)
    }

    override fun close() = listener.close()

    companion object {
        private const val TAG = "EpicBrowserLogin"
        private const val CHROME = "com.android.chrome"
        private const val TIMEOUT_MS = 10 * 60 * 1000L

        fun begin(context: Context, onResult: (String?) -> Unit): EpicBrowserLogin? {
            val app = context.applicationContext
            val state = StoreLoginActivity.generateState()
            lateinit var login: EpicBrowserLogin
            val listener = try {
                val page = LoopbackListener.page(app.getString(R.string.epic_browser_sign_in_return), returnIntent(app))
                LoopbackListener(state, page, TIMEOUT_MS) { login.complete(it) }
            } catch (e: Exception) {
                Log.w(TAG, "no loopback listener: ${e.javaClass.simpleName}")
                return null
            }
            val start = EpicNative.call("auth.browserStart", JSONObject().put("port", listener.port).put("state", state))
            val url = start?.optString("url").orEmpty()
            val redirect = start?.optString("redirectUri").orEmpty()
            if (url.isEmpty() || redirect.isEmpty()) { listener.close(); return null }
            login = EpicBrowserLogin(listener, url, redirect, app, onResult)
            listener.start(Uri.parse(redirect).path.orEmpty())
            if (!login.open()) { listener.close(); return null }
            return login
        }

        private fun returnIntent(context: Context): String =
            "intent://sign-in-return#Intent;scheme=droiddeck;package=${context.packageName};end"
    }
}
