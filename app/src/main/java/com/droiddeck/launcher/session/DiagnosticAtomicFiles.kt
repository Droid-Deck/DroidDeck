package com.droiddeck.launcher.session

import android.system.ErrnoException
import android.system.OsConstants
import java.io.IOException

internal class AtomicPublicationUnsupportedException(val errno: Int) :
    IOException("Atomic no-replace publication is unsupported (errno $errno)")

internal fun checkAtomicRenameResult(error: Int) {
    if (error == 0) return
    if (error == OsConstants.ENOSYS || error == OsConstants.EOPNOTSUPP || error == OsConstants.EINVAL) {
        throw AtomicPublicationUnsupportedException(error)
    }
    throw ErrnoException("renameat2", error)
}

internal object DiagnosticAtomicFiles {
    init {
        System.loadLibrary("sdnative")
    }

    fun renameNoReplace(
        oldDirectoryFd: Int,
        oldName: String,
        newDirectoryFd: Int,
        newName: String,
    ): Int {
        require(safeComponent(oldName) && safeComponent(newName)) { "Unsafe atomic file name" }
        return nativeRenameNoReplace(oldDirectoryFd, oldName, newDirectoryFd, newName)
    }

    @JvmStatic private external fun nativeRenameNoReplace(
        oldDirectoryFd: Int,
        oldName: String,
        newDirectoryFd: Int,
        newName: String,
    ): Int

    private fun safeComponent(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name
}
