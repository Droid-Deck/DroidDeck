package com.droiddeck.launcher.session

import android.system.ErrnoException
import android.system.OsConstants

internal object DiagnosticAtomicFiles {
    internal enum class PublicationMethod { RENAME, LINK }

    private val nativeLoaded by lazy {
        System.loadLibrary("sdnative")
        true
    }

    fun renameNoReplace(
        oldDirectoryFd: Int,
        oldName: String,
        newDirectoryFd: Int,
        newName: String,
    ): Int {
        require(safeComponent(oldName) && safeComponent(newName)) { "Unsafe atomic file name" }
        check(nativeLoaded)
        return nativeRenameNoReplace(oldDirectoryFd, oldName, newDirectoryFd, newName)
    }

    internal fun publishNoReplace(
        rename: () -> Int,
        link: () -> Unit,
        onPublished: (PublicationMethod) -> Unit,
        verifyLinked: () -> Unit,
        unlinkStage: () -> Unit,
        verifyFinal: () -> Unit,
    ): PublicationMethod {
        val renameError = rename()
        if (renameError == 0) {
            onPublished(PublicationMethod.RENAME)
            return PublicationMethod.RENAME
        }
        // Older Android kernels can lack renameat2; every other error must stay fail-closed.
        if (renameError != OsConstants.ENOSYS && renameError != OsConstants.EOPNOTSUPP &&
            renameError != OsConstants.EINVAL) {
            throw ErrnoException("renameat2", renameError)
        }
        link()
        onPublished(PublicationMethod.LINK)
        verifyLinked()
        unlinkStage()
        verifyFinal()
        return PublicationMethod.LINK
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
