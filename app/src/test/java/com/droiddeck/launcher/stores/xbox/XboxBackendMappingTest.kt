package com.droiddeck.launcher.stores.xbox

import org.junit.Assert.*
import org.junit.Test

class XboxBackendMappingTest {
    private val path = "/runtime/xodus-cli"
    private fun mapping(permissions: String = "r-xp", inode: Long = 42, name: String = path) =
        "7a0000-7b0000 $permissions 00001000 00:31 $inode $name\n"

    @Test fun prootLoaderDoesNotHideTheVerifiedBackendImage() {
        val maps = mapping(name = "/android/libproot-loader.so", inode = 1) + mapping()
        assertTrue(XboxCredentials.mapsBackend(maps, path, 42))
    }
    @Test fun loaderAloneOrAnotherGuestExecutableCannotAuthenticate() {
        assertFalse(XboxCredentials.mapsBackend(mapping(name = "/android/libproot-loader.so"), path, 42))
        assertFalse(XboxCredentials.mapsBackend(mapping(name = "/runtime/other-cli"), path, 42))
        assertFalse(XboxCredentials.mapsBackend(mapping(inode = 43), path, 42))
        assertFalse(XboxCredentials.mapsBackend(mapping(permissions = "r--p"), path, 42))
        assertFalse(XboxCredentials.mapsBackend(mapping(name = "$path (deleted)"), path, 42))
        assertFalse(XboxCredentials.mapsBackend("malformed\n", path, 42))
    }
}
