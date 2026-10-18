package com.droiddeck.launcher.frontend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryCacheClientTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val games = listOf(Library.SteamGame(440, "Team Fortress 2", null, "Steam library"))

    @Test fun aCacheFromBeforeTheClientWasRecordedIsArm64s() {
        java.io.File(app.filesDir, "library-cache.json").writeText("""[{"appId":440,"name":"Team Fortress 2","library":"Steam library","gameId":440}]""")
        val (games, x64) = LibraryCache.snapshot(app)
        assertEquals(listOf("Team Fortress 2"), games.map { it.name })
        assertFalse(x64)
    }

    @Test fun theListAndItsClientAreOneFile() {
        LibraryCache.save(app, games, x64 = true)
        assertEquals(listOf("library-cache.json"), app.filesDir.list().orEmpty().filter { it.startsWith("library-cache") })
        assertEquals(listOf("Team Fortress 2") to true, LibraryCache.snapshot(app).let { (g, x) -> g.map { it.name } to x })
    }

    @Test fun theListsClientIsKeptUntilAListForAnotherIsSaved() {
        LibraryCache.save(app, games, x64 = true)
        assertTrue(LibraryCache.listedX64(app))
        LibraryCache.save(app, emptyList())
        assertTrue(LibraryCache.listedX64(app))
        LibraryCache.save(app, games, x64 = false)
        assertFalse(LibraryCache.listedX64(app))
    }
}
