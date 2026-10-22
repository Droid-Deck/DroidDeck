package com.droiddeck.launcher.runtime

import android.content.Context
import com.droiddeck.launcher.core.AddonPack
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import java.security.MessageDigest

object ProotFastPath {
    const val LIBRARY = "/usr/local/lib/libblfastpath.so"
    const val MAX_BINDS = 256

    fun enabled(context: Context): Boolean =
        SessionPrefs.prootFastPath(context) && !SessionPrefs.prootNoSeccomp(context) &&
            LinuxRuntime.prootBinary(context).parentFile == AddonPack.PROOT.dir(context)

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
