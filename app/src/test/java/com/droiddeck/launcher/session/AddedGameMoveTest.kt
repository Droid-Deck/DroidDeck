package com.droiddeck.launcher.session

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AddedGameMoveTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val from = "/data/root/Games/Stores/Epic/Fig"
    private val to = "/storage/emulated/0/Games/Games/Epic/Fig"

    @Test
    fun settingsOfTheOldFolderMoveToTheNewOne() {
        val all = mapOf(
            "addedAppId:$from" to 3703311242L,
            "addedExe:$from" to "$from/Fig.exe",
            "addedExeSeen:$from" to 2,
            "addedArt:hero:$from" to "https://cdn/hero.png",
            "addedAdopted:exe:$from" to from,
            "addedAppId:$from-2" to 5L,
            "addedName:/other" to "Other",
            "theme" to "graphite",
        )
        assertEquals(
            mapOf(
                "addedAppId:$from" to ("addedAppId:$to" to 3703311242L),
                "addedExe:$from" to ("addedExe:$to" to "$to/Fig.exe"),
                "addedExeSeen:$from" to ("addedExeSeen:$to" to 2),
                "addedArt:hero:$from" to ("addedArt:hero:$to" to "https://cdn/hero.png"),
                "addedAdopted:exe:$from" to ("addedAdopted:exe:$to" to to),
            ),
            SessionPrefs.movedAddedGame(all, from, to),
        )
    }

    @Test
    fun nothingMovesWithoutSettings() {
        assertEquals(emptyMap<String, Pair<String, Any?>>(), SessionPrefs.movedAddedGame(mapOf("theme" to "x"), from, to))
    }

    @Test
    fun aMovedStoreGameKeepsItsAppIdAndRemoval() {
        SessionPrefs.followStoreGame(app, "epic:fig", from) { true }
        assertEquals(3703311242L, SessionPrefs.addedGameAppId(app, from, 3703311242L))
        SessionPrefs.setAddedGameExe(app, from, "$from/Fig.exe")
        SessionPrefs.setAddedGameRemoved(app, from, true)
        SessionPrefs.followStoreGame(app, "epic:fig", to) { true }
        assertEquals(3703311242L, SessionPrefs.addedGameAppId(app, to, 1L))
        assertEquals("$to/Fig.exe", SessionPrefs.addedGameExe(app, to))
        assertEquals(listOf(to), SessionPrefs.removedAddedGames(app))
        assertEquals(false, prefs.contains("addedAppId:$from"))
    }

    @Test
    fun aCopyThatIsStillThereKeepsItsOwnSettings() {
        SessionPrefs.followStoreGame(app, "epic:fig", from) { false }
        SessionPrefs.addedGameAppId(app, from, 7L)
        SessionPrefs.followStoreGame(app, "epic:fig", to) { false }
        assertEquals(9L, SessionPrefs.addedGameAppId(app, to, 9L))
        assertEquals(7L, SessionPrefs.addedGameAppId(app, from, 1L))
    }
}
