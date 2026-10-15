package com.droiddeck.launcher.stores.amazon

import android.util.Log
import android.webkit.WebView
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.StoreLoginActivity
import org.json.JSONObject

internal class AmazonLogin private constructor(
    override val startUrl: String,
    override val userAgent: String,
    private val marker: String,
    private val serial: String,
    private val clientId: String,
    private val verifier: String,
) : StoreLoginActivity.Flow {
    override fun isRedirect(url: String): Boolean = url.contains(marker)

    override fun finish(activity: StoreLoginActivity, view: WebView, url: String): String? {
        val answer = AmazonNative.call(
            "auth.register",
            JSONObject().put("url", url).put("serial", serial).put("clientId", clientId).put("verifier", verifier),
        )
        if (answer != null && answer.optBoolean("noCode", false)) {
            Log.w(TAG, "the return URL carried no authorization code")
            return activity.getString(R.string.stores_login_error_verification)
        }
        if (answer == null || answer.has("error")) {
            Log.w(TAG, "registration failed: ${answer?.optString("error").orEmpty().ifEmpty { "no answer" }}")
            return activity.getString(R.string.stores_login_error_generic)
        }
        return try {
            AmazonCredentialStore.saveSignIn(activity, answer)
            null
        } catch (e: Exception) {
            Log.w(TAG, "saving the sign-in failed: ${e.javaClass.simpleName}")
            activity.getString(R.string.stores_login_error_generic)
        }
    }

    companion object {
        private const val TAG = "AmazonLogin"

        fun create(): StoreLoginActivity.Flow? {
            val start = AmazonNative.call("auth.start") ?: return null
            if (start.has("error")) return null
            return AmazonLogin(
                startUrl = start.optString("startUrl").ifEmpty { return null },
                userAgent = start.optString("userAgent"),
                marker = start.optString("redirectMarker").ifEmpty { return null },
                serial = start.optString("serial"),
                clientId = start.optString("clientId"),
                verifier = start.optString("verifier"),
            )
        }
    }
}
