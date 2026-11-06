package com.droiddeck.launcher.session

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
