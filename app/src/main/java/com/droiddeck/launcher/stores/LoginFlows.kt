package com.droiddeck.launcher.stores

import android.net.Uri
import android.util.Log
import android.webkit.WebView
import com.droiddeck.launcher.stores.gog.GogAuth

internal object LoginFlows {
    private const val TAG = "StoreLogin"

    fun forStore(store: Store, state: String, browserUserAgent: String): StoreLoginActivity.Flow? = when (store) {
        Store.GOG -> GogFlow(state, browserUserAgent)
        Store.EPIC -> epicFlow()
        Store.AMAZON -> amazonFlow()
        Store.ITCH, Store.XBOX, Store.BATTLENET -> null
    }

    @Volatile var epicFlow: () -> StoreLoginActivity.Flow? = { null }
    @Volatile var amazonFlow: () -> StoreLoginActivity.Flow? = { null }

    private class GogFlow(private val state: String, override val userAgent: String) : StoreLoginActivity.Flow {
        override val startUrl: String get() = GogAuth.authUrl(state)
        override fun isRedirect(url: String): Boolean = url.startsWith(GogAuth.REDIRECT_PREFIX)

        override fun forgetSignIn(done: () -> Unit) {
            runCatching { WebCookies.expire(GogAuth.SIGN_IN_URLS, GogAuth.COOKIE_DOMAIN, listOf("/")) { done() } }
                .onFailure {
                    Log.w(TAG, "gog: could not clear the previous web sign-in: ${it.javaClass.simpleName}")
                    done()
                }
        }

        override fun finish(activity: StoreLoginActivity, view: WebView, url: String): String? {
            val fragment = Uri.parse(url).fragment ?: return activity.getString(com.droiddeck.launcher.R.string.stores_login_error_verification)
            val frag = Uri.parse("x://x?$fragment")
            val returned = frag.getQueryParameter("state")
            if (returned != null && returned != state) {
                Log.w(TAG, "gog: state mismatch; redirect rejected")
                return activity.getString(com.droiddeck.launcher.R.string.stores_login_error_verification)
            }
            val access = frag.getQueryParameter("access_token")
            if (access.isNullOrEmpty()) {
                Log.w(TAG, "gog: redirect without access_token; keys=${frag.queryParameterNames}")
                return activity.getString(com.droiddeck.launcher.R.string.stores_login_error_verification)
            }
            return try {
                GogAuth.saveLogin(activity, access, frag.getQueryParameter("refresh_token"), frag.getQueryParameter("user_id"))
                null
            } catch (e: Exception) {
                Log.w(TAG, "gog: saving the sign-in failed: ${e.javaClass.simpleName}")
                activity.getString(com.droiddeck.launcher.R.string.stores_login_error_generic)
            }
        }
    }
}
