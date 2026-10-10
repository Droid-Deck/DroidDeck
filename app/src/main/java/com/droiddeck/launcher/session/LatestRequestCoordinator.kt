package com.droiddeck.launcher.session

internal class LatestRequestCoordinator<T> {
    data class Request<T>(val generation: Long, val value: T)

    private val lock = Any()
    private var generation = 0L
    private var running = false
    private var latest: Request<T>? = null
    private var pending: Request<T>? = null

    fun submit(value: T): Boolean = synchronized(lock) {
        if (latest?.value != value) generation++
        pending = Request(generation, value).also { latest = it }
        if (running) {
            false
        } else {
            running = true
            true
        }
    }

    fun takeLatest(): Request<T>? = synchronized(lock) {
        pending.also {
            pending = null
            if (it == null) running = false
        }
    }

    fun isLatest(request: Request<T>): Boolean = synchronized(lock) {
        request.generation == latest?.generation
    }
}
