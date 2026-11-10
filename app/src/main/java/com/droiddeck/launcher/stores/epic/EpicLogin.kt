package com.droiddeck.launcher.stores.epic

import android.util.Log
import android.webkit.WebView
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.StoreLoginActivity
import org.json.JSONObject
import org.json.JSONTokener

internal class EpicLogin private constructor(
    override val startUrl: String,
    override val userAgent: String,
    private val redirectPrefix: String,
) : StoreLoginActivity.Flow {
    override val readsPage: Boolean get() = true

    override fun isRedirect(url: String): Boolean = url.startsWith(redirectPrefix)

    override fun browserStep(page: String): String? {
        val action = correctiveAction(page) ?: return null
        Log.i(TAG, "the redirect page asks for a corrective action: $action")
        return ACCOUNT_SIGN_IN_URL
    }

    override fun finish(activity: StoreLoginActivity, view: WebView, url: String): String? {
        val code = EpicNative.call("auth.codeFromPage", JSONObject().put("text", url))?.optString("code", "").orEmpty()
        if (code.isEmpty()) {
            Log.w(TAG, "the redirect page carried no authorization code")
            return activity.getString(R.string.stores_login_error_verification)
        }
        val answer = EpicNative.call("auth.signIn", JSONObject().put("code", code))
        val tokens = answer?.let(EpicCredentialStore.TokenResult::from)
        if (tokens == null) {
            Log.w(TAG, "code exchange failed: ${answer?.optString("error").orEmpty().ifEmpty { "no answer" }}")
            return activity.getString(R.string.stores_login_error_generic)
        }
        return try {
            EpicCredentialStore.save(activity, tokens, merge = false)
            null
        } catch (e: Exception) {
            Log.w(TAG, "saving the sign-in failed: ${e.javaClass.simpleName}")
            activity.getString(R.string.stores_login_error_generic)
        }
    }

    companion object {
        private const val TAG = "EpicLogin"
        private const val CORRECTIVE_ACTION_REQUIRED = "errors.com.epicgames.oauth.corrective_action_required"
        internal const val ACCOUNT_SIGN_IN_URL = "https://www.epicgames.com/id/login?redirectUrl=https%3A%2F%2Fwww.epicgames.com%2Faccount%2Fpersonal"

        internal fun correctiveAction(page: String): String? {
            val text = runCatching { JSONTokener(page).nextValue() }.getOrNull() as? String ?: page
            val body = runCatching { JSONObject(text.trim()) }.getOrNull() ?: return null
            if (body.optString("errorCode") != CORRECTIVE_ACTION_REQUIRED) return null
            return body.optJSONObject("metadata")?.optString("correctiveAction").orEmpty().ifEmpty { "unknown" }
        }

        fun create(): StoreLoginActivity.Flow? {
            val config = EpicNative.call("auth.config") ?: return null
            val start = config.optString("startUrl").ifEmpty { return null }
            val prefix = config.optString("redirectPrefix").ifEmpty { return null }
            return EpicLogin(start, config.optString("webViewUserAgent"), prefix)
        }
    }
}
