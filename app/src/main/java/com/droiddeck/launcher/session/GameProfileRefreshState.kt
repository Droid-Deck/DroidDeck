package com.droiddeck.launcher.session

internal data class GameProfileRefreshState<T>(
    val data: T? = null,
    val loaded: Boolean = false,
    val failed: Boolean = false,
) {
    fun succeeded(data: T) = GameProfileRefreshState(data, loaded = true)

    fun failed() = copy(failed = true)

    fun retrying() = copy(failed = false)
}
