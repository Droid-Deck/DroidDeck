package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Test

class LudusaviConfigTest {
    private val entry = "  - store: steam\n    path: /root/.droiddeck-x64/.local/share/Steam"

    @Test
    fun emptyConfigGetsX64SteamRoot() {
        assertEquals("roots:\n$entry\n", Ludusavi.withX64SteamRoot(""))
    }

    @Test
    fun existingRootsAreKept() {
        val config = "manifest:\n  enable: true\nroots:\n  - store: steam\n    path: ~/steam\n"
        assertEquals(
            "manifest:\n  enable: true\nroots:\n$entry\n  - store: steam\n    path: ~/steam\n",
            Ludusavi.withX64SteamRoot(config.trimEnd('\n')) + "\n")
    }

    @Test
    fun existingX64RootIsNotDuplicated() {
        val config = "roots:\n$entry\n"
        assertEquals(config, Ludusavi.withX64SteamRoot(config.trimEnd('\n')) + "\n")
    }

    @Test
    fun inlineEmptyRootsAreReplaced() {
        assertEquals("roots:\n$entry\nother: 1\n", Ludusavi.withX64SteamRoot("roots: []\nother: 1\n".trimEnd('\n')) + "\n")
    }
}
