package com.droiddeck.launcher.stores.xbox

import android.content.Context
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import com.droiddeck.launcher.stores.StoresState
internal object XboxGamertag {
    private const val TIMEOUT_SECONDS = 75L
    private val triedGeneration = java.util.concurrent.atomic.AtomicReference<String?>(null)

    internal fun valid(value: String): Boolean = value.isNotEmpty() && value.length <= 64 && value.none { it.isISOControl() }

    fun refresh(app: Context, credentialGeneration: String? = null): Boolean = try {
        val gamertag = Xodus.run(app, listOf("profile", "--json"), timeout = TIMEOUT_SECONDS, credentialGeneration = credentialGeneration)
            .optString("gamertag").trim()
        require(valid(gamertag))
        XboxCredentials.lifecycle(app, credentialGeneration) {
            val record = StoreAccounts.read(app, Store.XBOX) ?: error("Xbox credentials missing")
            record.put("gamertag", gamertag)
            StoreAccounts.write(app, Store.XBOX, record)
        }
        StoresState.logLine("Xbox: gamertag read")
        true
    } catch (e: Exception) {
        StoresState.logLine("Xbox: gamertag unavailable (${XboxLoginActivity.failure(e)})")
        false
    }

    fun ensure(app: Context) {
        Thread({
            runCatching {
                val generation = XboxCredentials.lifecycle(app) { it }
                if (!StoreAccounts.read(app, Store.XBOX)?.optString("gamertag").isNullOrBlank()) return@runCatching
                if (!shouldTryRefresh(generation)) return@runCatching
                if (refresh(app, generation)) StoresState.refresh(app)
            }.onFailure { StoresState.logLine("Xbox: gamertag check failed (${it.javaClass.simpleName})") }
        }, "xbox-gamertag").start()
    }

    internal fun shouldTryRefresh(generation: String): Boolean {
        while (true) {
            val previous = triedGeneration.get()
            if (previous == generation) return false
            if (triedGeneration.compareAndSet(previous, generation)) return true
        }
    }
}
