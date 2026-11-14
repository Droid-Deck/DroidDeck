package com.droiddeck.launcher.stores.gamejolt

import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.StoreLoginActivity
import com.droiddeck.launcher.stores.WebCookies

internal class GameJoltLogin(override val userAgent: String) : StoreLoginActivity.Flow {
    override val startUrl: String get() = GameJoltApi.LOGIN

    override fun isRedirect(url: String): Boolean {
        val uri = Uri.parse(url)
        if (uri.host != HOST && uri.host != "www.$HOST") return false
        val path = uri.path.orEmpty()
        return AUTH_PATHS.none { path == it || path.startsWith("$it/") }
    }

    override fun forgetSignIn(done: () -> Unit) {
        runCatching { WebCookies.expire(listOf(GameJoltApi.SITE), HOST, listOf("/")) { done() } }
            .onFailure {
                Log.w(TAG, "could not clear the previous web sign-in: ${it.javaClass.simpleName}")
                done()
            }
    }

    override fun finish(activity: StoreLoginActivity, view: WebView, url: String): String? {
        val manager = CookieManager.getInstance()
        manager.flush()
        val cookies = manager.getCookie(GameJoltApi.SITE).orEmpty()
        if (cookies.isBlank()) {
            Log.w(TAG, "no cookies after the sign-in redirect")
            return activity.getString(R.string.stores_login_error_verification)
        }
        val answer = GameJoltApi.get("/web/search/games?q=a", cookies)
        val user = answer.user
        Log.d(TAG, "verify: code=${answer.code} cookies=${cookies.split(';').joinToString(",") { it.substringBefore('=').trim() }} userKeys=${user?.keys()?.asSequence()?.joinToString(",").orEmpty()}")
        if (user == null || user.optLong("id") <= 0L) return activity.getString(R.string.stores_login_error_verification)
        return try {
            GameJoltAuth.save(activity, cookies, user)
            null
        } catch (e: Exception) {
            Log.w(TAG, "saving the sign-in failed: ${e.javaClass.simpleName}")
            activity.getString(R.string.stores_login_error_generic)
        }
    }

    companion object {
        private const val TAG = "GameJoltLogin"
        private const val HOST = "gamejolt.com"
        private val AUTH_PATHS = listOf("/login", "/join", "/auth", "/forgot", "/terms", "/privacy", "/help", "/legal", "/site-api", "/x", "/oauth")
    }
}
