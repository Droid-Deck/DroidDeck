package com.droiddeck.launcher.stores.steam

object SteamNative {
    @JvmStatic external fun nativeSignIn(steamRootsJson: String, registryVdfPath: String, caBundlePath: String): String
    @JvmStatic external fun nativeSignOut()
    @JvmStatic external fun nativeLibrary(): String
    @JvmStatic external fun nativePlan(appId: Int, language: String): String
    @JvmStatic external fun nativeStart(appId: Int, language: String, steamRoot: String, gameLibraryHost: String, gameLibraryGuest: String, libraryRootsJson: String, journalDir: String, caBundlePath: String, maxWorkers: Int, processWorkers: Int, listener: Any): Long
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeRelease(handle: Long)
    @JvmStatic external fun nativeUninstall(appId: Int, steamRoot: String, gameLibraryHost: String, gameLibraryGuest: String, libraryRootsJson: String): String
}
