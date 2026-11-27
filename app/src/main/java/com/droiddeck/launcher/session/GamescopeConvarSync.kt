package com.droiddeck.launcher.session

internal class GamescopeConvarSync(
    private val name: String,
    private val wanted: () -> Int?,
    private val execute: (Runnable) -> Unit,
    private val retryLater: (Runnable) -> Unit,
    private val set: (String, Int) -> Boolean,
    private val maxRetries: Int,
    @Volatile var applied: Int? = null,
) {
    @Volatile private var retries = 0

    fun request() {
        execute(Runnable { send() })
    }

    fun restart() {
        retries = 0
        request()
    }

    private fun send() {
        val value = wanted() ?: return
        if (applied == value) return
        if (set(name, value)) applied = value
        else if (retries++ < maxRetries) retryLater(Runnable { request() })
        if (wanted() != value) request()
    }
}
