package com.droiddeck.launcher.runtime

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import java.security.MessageDigest

object ProotFastPath {
    private const val TAG = "ProotFastPath"
    const val LIBRARY = "/usr/local/lib/libblfastpath.so"
    const val MAX_BINDS = 256

    /**
     * The fast path's trampoline is let through only by the proot built with patches/0014.
     * The rootfs copy (Termux's stock proot) is not that binary, so the library stays out.
     */
    fun allows(preference: Boolean, noSeccomp: Boolean, source: String): Boolean =
        preference && !noSeccomp && source == LinuxRuntime.PROOT_PACK

    fun enabled(context: Context): Boolean {
        val preference = SessionPrefs.prootFastPath(context)
        val noSeccomp = SessionPrefs.prootNoSeccomp(context)
        val source = LinuxRuntime.prootSource(context)
        val on = allows(preference, noSeccomp, source)
        if (preference && !noSeccomp && !on)
            Log.i(TAG, "proot: fast path off - binary is $source, not the patched proot")
        return on
    }

    fun key(root: File, binds: List<String>): String? {
        if (binds.size > MAX_BINDS || binds.any { it.contains('|') } || root.path.contains('|')) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((root.path + "\n" + binds.joinToString("\n")).toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    fun hostEnv(key: String): Pair<String, String> = "PROOT_FASTPATH" to key

    fun guestEnv(root: File, binds: List<String>, key: String): List<String> = listOf(
        "PROOT_FP_ROOT=" + root.path,
        "PROOT_FP_BINDS=" + binds.joinToString("|"),
        "PROOT_FP_KEY=$key",
    )
}
