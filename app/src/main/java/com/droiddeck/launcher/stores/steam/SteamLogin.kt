package com.droiddeck.launcher.stores.steam

import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.StoreLoginActivity

internal class SteamLogin(override val userAgent: String) : StoreLoginActivity.Flow {
    @Volatile private var session: SteamWebSession? = null

    override val startUrl: String = "https://store.steampowered.com/login/?redir=/"
    override val readsPage: Boolean = true

    override fun isRedirect(url: String): Boolean {
        val uri = Uri.parse(url)
        if (uri.host != "store.steampowered.com" || uri.path.orEmpty().startsWith("/login")) return false
        val cookies = CookieManager.getInstance().getCookie("https://store.steampowered.com/")
        val found = SteamAccount.fromCookieHeader(cookies) ?: return false
        session = found
        CookieManager.getInstance().flush()
        return true
    }

    override fun finish(activity: StoreLoginActivity, view: WebView, url: String): String? {
        val found = session ?: return activity.getString(R.string.stores_login_error_verification)
        return runCatching {
            SteamAccount.save(activity, found)
            runCatching { SteamBackend.syncLibrary(activity, force = true) }
            null
        }.getOrElse {
            Log.w("SteamLogin", "could not save the Steam web session (${it.javaClass.simpleName})")
            activity.getString(R.string.stores_login_error_generic)
        }
    }
}
