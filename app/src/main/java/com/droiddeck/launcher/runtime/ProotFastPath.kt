package com.droiddeck.launcher.runtime

import android.content.Context
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import java.security.MessageDigest

/**
 * proot's fast path: the session's path lookups answered inside each process by libblfastpath
 * (tools/proot/fastpath), which issues the translated call from a trampoline page proot's seccomp
 * filter lets through (tools/proot/patches/0014). A stat or an open costs about a microsecond
 * instead of a 15-60 us round trip through the tracer.
 *
 * The library maps guest paths to host paths itself, so it has to be told the rootfs and the
 * binds exactly as proot was given them. Both sides carry a key derived from that view - proot
 * PROOT_FASTPATH=<key>, the guest PROOT_FP_KEY=<key> - and the library answers nothing unless the
 * process that traces it has the same key: a program that inherited the variables but runs under
 * another proot (a Flatpak sandbox, a one-off command) leaves every call to its own proot.
 *
 * Needs proot's seccomp filter, so "Run proot without seccomp" turns it off as well.
 */
object ProotFastPath {
    const val LIBRARY = "/usr/local/lib/libblfastpath.so"
    /** MAX_BINDS in fastpath.c: past it the library turns itself off, so the session keeps it off. */
    const val MAX_BINDS = 256

    /**
     * Only the app's own proot (libproot.so, built with patches/0014) lets the trampoline through.
     * The runtime's fallback copy (LinuxRuntime.prootBinary) is Termux's, which traps those calls
     * too and translates their host paths a second time: every lookup then fails inside the
     * process and goes to proot anyway, paying for both.
     */
    fun enabled(context: Context): Boolean =
        SessionPrefs.prootFastPath(context) && !SessionPrefs.prootNoSeccomp(context) &&
            LinuxRuntime.prootBinary(context).parent == context.applicationInfo.nativeLibraryDir

    /** The key for this rootfs and these binds, or null when the library cannot be told them. */
    fun key(root: File, binds: List<String>): String? {
        if (binds.size > MAX_BINDS || binds.any { it.contains('|') } || root.path.contains('|')) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((root.path + "\n" + binds.joinToString("\n")).toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    /** What proot's own environment carries. */
    fun hostEnv(key: String): Pair<String, String> = "PROOT_FASTPATH" to key

    /** The guest's assignments (for `env -i`), describing the view proot was given. */
    fun guestEnv(root: File, binds: List<String>, key: String): List<String> = listOf(
        "PROOT_FP_ROOT=" + root.path,
        "PROOT_FP_BINDS=" + binds.joinToString("|"),
        "PROOT_FP_KEY=$key",
    )
}
