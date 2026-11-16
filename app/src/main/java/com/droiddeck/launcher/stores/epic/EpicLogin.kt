package com.droiddeck.launcher.stores.epic

import android.content.Context
import android.util.Log
import android.webkit.WebView
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.StoreLoginActivity
import org.json.JSONObject

internal class EpicLogin private constructor(
    override val startUrl: String,
    override val userAgent: String,
    private val redirectPrefix: String,
) : StoreLoginActivity.Flow {
    override val readsPage: Boolean get() = true

    override fun isRedirect(url: String): Boolean = url.startsWith(redirectPrefix)

    override fun finish(activity: StoreLoginActivity, view: WebView, url: String): String? {
        val code = EpicNative.call("auth.codeFromPage", JSONObject().put("text", url))?.optString("code", "").orEmpty()
        if (code.isEmpty()) {
            Log.w(TAG, "the redirect page carried no authorization code")
            return activity.getString(R.string.stores_login_error_verification)
        }
        return signIn(activity, code, null)
    }

    companion object {
        private const val TAG = "EpicLogin"

        fun signIn(context: Context, code: String, redirectUri: String?): String? {
            val request = JSONObject().put("code", code)
            if (redirectUri != null) request.put("redirectUri", redirectUri)
            val answer = EpicNative.call("auth.signIn", request)
            val tokens = answer?.let(EpicCredentialStore.TokenResult::from)
            if (tokens == null) {
                Log.w(TAG, "code exchange failed: ${answer?.optString("error").orEmpty().ifEmpty { "no answer" }}")
                return context.getString(R.string.stores_login_error_generic)
            }
            return try {
                EpicCredentialStore.save(context, tokens, merge = false)
                null
            } catch (e: Exception) {
                Log.w(TAG, "saving the sign-in failed: ${e.javaClass.simpleName}")
                context.getString(R.string.stores_login_error_generic)
            }
        }

        fun create(): StoreLoginActivity.Flow? {
            val config = EpicNative.call("auth.config") ?: return null
            val start = config.optString("startUrl").ifEmpty { return null }
            val prefix = config.optString("redirectPrefix").ifEmpty { return null }
            return EpicLogin(start, config.optString("webViewUserAgent"), prefix)
        }
    }
}
