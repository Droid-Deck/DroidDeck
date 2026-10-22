package com.droiddeck.launcher.stores.xbox

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import com.droiddeck.launcher.stores.StoresState
/** Keeps the account's Xbox gamertag in its record so the Stores page can show it instead of the sign-in email. */
internal object XboxGamertag {
    private const val TAG = "XboxGamertag"
    private val triedGeneration = java.util.concurrent.atomic.AtomicReference<String?>(null)

    internal fun valid(value: String): Boolean = value.isNotEmpty() && value.length <= 64 && value.none { it.isISOControl() }

    /** Reads the gamertag with `xodus-cli profile` and stores it; false when Xbox didn't return one. */
    fun refresh(app: Context, credentialGeneration: String? = null): Boolean = try {
        val gamertag = Xodus.run(app, listOf("profile", "--json"), timeout = 30, credentialGeneration = credentialGeneration)
            .optString("gamertag").trim()
        require(valid(gamertag))
        XboxCredentials.lifecycle(app, credentialGeneration) {
            val record = StoreAccounts.read(app, Store.XBOX) ?: error("Xbox credentials missing")
            record.put("gamertag", gamertag)
            StoreAccounts.write(app, Store.XBOX, record)
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "gamertag unavailable (${e.javaClass.simpleName})")
        false
    }

    /** Accounts signed in before the gamertag was kept get it once per app start. */
    fun ensure(app: Context) {
        val generation = XboxCredentials.lifecycle(app) { it }
        if (!StoreAccounts.read(app, Store.XBOX)?.optString("gamertag").isNullOrBlank()) return
        if (!shouldTryRefresh(generation)) return
        if (refresh(app, generation)) StoresState.refresh(app)
    }

    internal fun shouldTryRefresh(generation: String): Boolean {
        while (true) {
            val previous = triedGeneration.get()
            if (previous == generation) return false
            if (triedGeneration.compareAndSet(previous, generation)) return true
        }
    }
}
