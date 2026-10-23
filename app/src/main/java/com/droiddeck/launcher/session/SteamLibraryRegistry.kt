package com.droiddeck.launcher.session

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Steam libraries registered for moved games, including folders outside the configured card and games folders.
 * The guest path is stable so Steam keeps seeing the same library between sessions.
 */
object SteamLibraryRegistry {
    private const val GUEST_ROOT = "/mnt/droiddeck-libraries"
    private const val PREFS = "session"
    private const val KEY = "movedSteamLibraries"

    class Bind(val arch: String, val host: File, val guest: String, val label: String)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<Bind> = synchronized(this) {
        (prefs(context).getString(KEY, "") ?: "").lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 4 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) return@mapNotNull null
            Bind(parts[0], File(parts[1]), parts[2], parts[3].ifBlank { File(parts[1]).name })
        }.toList()
    }

    fun available(context: Context, arch: String): List<Bind> = all(context)
        .filter { it.arch == arch && it.host.isDirectory && it.host.canRead() }

    fun register(context: Context, arch: String, host: File, label: String): Bind = synchronized(this) {
        val path = host.absolutePath
        val clean = label.replace('"', ' ').replace('\\', ' ').replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
        val binds = all(context).toMutableList()
        val existing = binds.firstOrNull { it.arch == arch && canonical(it.host) == canonical(host) }
        if (existing != null) {
            if (existing.label == clean) return@synchronized existing
            binds[binds.indexOf(existing)] = Bind(arch, existing.host, existing.guest, clean)
            save(context, binds)
            return@synchronized binds.first { it.arch == arch && canonical(it.host) == canonical(host) }
        }
        // One guest per host: the arm64 and x86-64 clients can share a library mount.
        val guest = binds.firstOrNull { canonical(it.host) == canonical(host) }?.guest ?: guestFor(host)
        val bind = Bind(arch, File(path), guest, clean)
        binds.add(bind)
        save(context, binds)
        bind
    }

    fun unregister(context: Context, arch: String, host: File) = synchronized(this) {
        val path = canonical(host)
        save(context, all(context).filterNot { it.arch == arch && canonical(it.host) == path })
    }

    fun guestFor(host: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical(host).toByteArray())
        return GUEST_ROOT + "/" + digest.joinToString("") { "%02x".format(it.toInt() and 255) }.take(16)
    }

    private fun save(context: Context, binds: List<Bind>) {
        prefs(context).edit().putString(KEY, binds.distinctBy { it.arch to canonical(it.host) }
            .joinToString("\n") { "${it.arch}\t${it.host.absolutePath}\t${it.guest}\t${it.label}" }).apply()
    }

    private fun canonical(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
}
