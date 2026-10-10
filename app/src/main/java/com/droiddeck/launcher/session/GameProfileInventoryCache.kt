package com.droiddeck.launcher.session

internal class GameProfileInventoryCache<T : Any> {
    private val lock = Any()

    private var revision: Long? = null
    private var value: List<T>? = null

    // Called only by the refresh worker; the UI invalidates through request revisions.
    fun get(requestedRevision: Long, load: () -> List<T>): List<T> = synchronized(lock) {
        value?.takeIf { revision == requestedRevision } ?: load().toList().also {
            value = it
            revision = requestedRevision
        }
    }
}
