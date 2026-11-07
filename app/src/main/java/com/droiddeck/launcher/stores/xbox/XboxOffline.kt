package com.droiddeck.launcher.stores.xbox

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.droiddeck.launcher.core.AddonPack
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal object XboxOffline {
    data class Status(val enabled: Boolean, val remainingChanges: Int?)
    data class Launch(val accountHash: String, val cache: File, val offline: Boolean)

    fun supported(app: Context): Boolean =
        File(AddonPack.XBOX.dir(app), "offline-permissions-v1").isFile

    private fun online(app: Context): Boolean {
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return false
        return manager.getNetworkCapabilities(manager.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    private fun key(account: String, credentials: String): String =
        MessageDigest.getInstance("SHA-256").digest("$account\u0000$credentials".toByteArray())
            .take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun cache(app: Context, scope: String): File = File(app.filesDir, "xbox-offline/$scope")

    @Synchronized
    fun status(app: Context, desired: Boolean? = null): Status = status(app, desired, XboxRuntime::stop)

    private fun status(app: Context, desired: Boolean?, onRevoked: () -> Unit): Status {
        check(supported(app) && online(app)) { "Xbox offline permissions require a compatible runtime and an internet connection." }
        val (account, credentials) = XboxCredentials.lifecycle(app) { epoch ->
            (StoreAccounts.signedInAs(app, Store.XBOX) ?: error("Sign in to Xbox first")) to epoch
        }
        val preferences = app.getSharedPreferences("xbox-offline", Context.MODE_PRIVATE)
        val key = key(account, credentials)
        val oldScope = preferences.getString(key, null)
        // If a change reaches Microsoft but the reply is lost, the previous scope
        // must never become usable again.
        if (desired != null) {
            check(preferences.edit().remove(key).commit())
            XboxRuntime.stop()
        }
        val args = buildList {
            add("offline-pc")
            if (desired != null) addAll(listOf("--set", desired.toString(), "--confirm"))
            if (oldScope != null) addAll(listOf("--license-cache", cache(app, oldScope).path))
        }
        val result = Xodus.run(app, args, timeout = 15, credentialGeneration = credentials)
        check(result.optInt("schema") == 1 && result.opt("enabled") is Boolean) { "Invalid Xbox offline permission response" }
        val enabled = result.getBoolean("enabled")
        if (!enabled && oldScope != null) {
            check(preferences.edit().remove(key).commit())
            onRevoked()
        }
        check(desired == null || (result.opt("requestSuccess") == true && enabled == desired)) {
            "Xbox did not apply the offline permission change"
        }
        XboxCredentials.lifecycle(app, credentials) {
            check(StoreAccounts.signedInAs(app, Store.XBOX) == account) { "Xbox account changed" }
            val edit = preferences.edit()
            if (enabled) edit.putString(key, oldScope.takeIf { desired == null }
                ?: UUID.randomUUID().toString().replace("-", ""))
            else edit.remove(key)
            check(edit.commit())
        }
        return Status(enabled, (result.opt("remainingToggles") as? Number)?.toInt()?.takeIf { it in 0..10_000 })
    }

    @Synchronized
    fun launch(app: Context, account: String, credentials: String, onRevoked: () -> Unit): Launch? {
        if (!supported(app)) return null
        val preferences = app.getSharedPreferences("xbox-offline", Context.MODE_PRIVATE)
        val key = key(account, credentials)
        if (preferences.getString(key, null) == null) return null
        val offline = !online(app)
        if (!offline && !runCatching { status(app, null, onRevoked).enabled }.getOrDefault(false)) return null
        return XboxCredentials.lifecycle(app, credentials) {
            val scope = preferences.getString(key, null) ?: return@lifecycle null
            Launch(scope, cache(app, scope), offline)
        }
    }
}
