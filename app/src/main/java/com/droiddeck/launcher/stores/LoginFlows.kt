package com.droiddeck.launcher.stores

import android.net.Uri
import android.util.Log
import android.webkit.WebView
import com.droiddeck.launcher.stores.gog.GogAuth

/** The three stores' sign-in flows for [StoreLoginActivity]. */
internal object LoginFlows {
    private const val TAG = "StoreLogin"

    fun forStore(store: Store, state: String): StoreLoginActivity.Flow? = when (store) {
        Store.GOG -> GogFlow(state)
        Store.EPIC -> epicFlow()
        Store.AMAZON -> amazonFlow()
    }

    /** Set by the Epic and Amazon backends when they are in the build; null keeps the chip's card honest. */
    @Volatile var epicFlow: () -> StoreLoginActivity.Flow? = { null }
    @Volatile var amazonFlow: () -> StoreLoginActivity.Flow? = { null }

    /**
     * GOG's implicit grant: the token arrives in the redirect URL's fragment. The CSRF state is
     * checked only when GOG echoes one back - its social-login path returns none, and treating
     * that as a mismatch threw away good tokens.
     */
    private class GogFlow(private val state: String) : StoreLoginActivity.Flow {
        override val startUrl: String get() = GogAuth.authUrl(state)
        override val userAgent: String get() = GogAuth.GALAXY_UA
        override fun isRedirect(url: String): Boolean = url.startsWith(GogAuth.REDIRECT_PREFIX)

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
                // The keys only, never the values: the fragment carries live tokens.
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
