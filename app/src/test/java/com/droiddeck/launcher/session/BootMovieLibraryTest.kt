package com.droiddeck.launcher.session

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BootMovieLibraryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val movies get() = BootMovieLibrary.moviesDir(app)

    @Before fun clear() {
        app.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        File(app.filesDir, "boot-movie").deleteRecursively()
        movies.deleteRecursively()
        BootMovieLibrary.libraryDir(app).deleteRecursively()
        File(BootMovieLibrary.steamRoot(app), "steamui").deleteRecursively()
        File(BootMovieLibrary.steamRoot(app), "appcache").deleteRecursively()
        BootMovieLibrary.forgetScan()
        BootMovieLibrary.steamConfig(app).delete()
    }

    private fun movie(name: String) = File(movies, name).also { it.parentFile!!.mkdirs(); it.writeText("movie") }

    /** A movie DroidDeck itself imported: kept in the library, copied into Steam's folder and noted in the list. */
    private fun imported(title: String, source: String = BootMovieLibrary.SOURCE_IMPORT, repo: String? = null): String {
        val staged = File(BootMovieLibrary.libraryDir(app), ".t-${System.nanoTime()}-${Thread.currentThread().id}.staged").also { it.parentFile!!.mkdirs(); it.writeText("movie") }
        return BootMovieLibrary.commit(app, staged, title, source, repo)!!
    }

    private fun steamPicksAt(path: String) {
        BootMovieLibrary.steamConfig(app).also { it.parentFile!!.mkdirs() }.writeText(
            "\"InstallConfigStore\"\n{\n\t\"StartupMovie\"\n\t{\n\t\t\"MovieID\"\t\t\"x\"\n\t\t\"LocalPath\"\t\t\"$path\"\n\t}\n}\n",
        )
    }

    private fun steamPicks(name: String) = steamPicksAt("/uioverrides/movies/$name")

    @Test fun listsTheDroidDeckMoviesAndEveryWebmButNotTheStagedCopies() {
        movie("Intro.webm")
        movie("bigpicture_startup.webm")
        movie("steam_os_startup.webm")
        movie("notes.txt")
        movie(".hidden.webm")
        val ids = BootMovieLibrary.list(app).map { it.id }
        assertEquals(listOf(BootMovie.DROIDDECK_30, BootMovie.DROIDDECK_60, "file:Intro.webm"), ids)
    }

    @Test fun usesTheTitleItWasRegisteredWithAndFallsBackToTheFileName() {
        imported("Fancy", BootMovieLibrary.SOURCE_REPO, "id1"); movie("b.webm")
        val list = BootMovieLibrary.list(app).associateBy { it.id }
        assertEquals("Fancy", list["file:Fancy.webm"]!!.title)
        assertEquals(BootMovieLibrary.SOURCE_REPO, list["file:Fancy.webm"]!!.source)
        assertTrue(list["file:Fancy.webm"]!!.removable)
        assertTrue(list["file:b.webm"]!!.removable)
        assertEquals("b", list["file:b.webm"]!!.title)
        assertEquals(BootMovieLibrary.SOURCE_IMPORT, list["file:b.webm"]!!.source)
        assertEquals(setOf("id1"), BootMovieLibrary.installedRepoIds(app))
        assertEquals("Fancy.webm", BootMovieLibrary.nameForRepo(app, "id1"))
    }

    @Test fun aChosenFileMovieIsKeptAndFallsBackOnceItIsGone() {
        val name = imported("Mine")
        BootMovie.select(app, "file:$name")
        assertEquals("file:$name", BootMovie.choice(app))
        assertTrue(BootMovieLibrary.remove(app, "file:$name"))
        assertEquals(BootMovie.DROIDDECK_30, BootMovie.choice(app))
        assertFalse(File(movies, name).exists())
        assertFalse(File(BootMovieLibrary.libraryDir(app), name).exists())
    }

    @Test fun aMovieInSteamsCustomFolderCanBeRemovedEvenWithoutANote() {
        movie("by-hand.webm")
        assertTrue(BootMovieLibrary.list(app).first { it.id == "file:by-hand.webm" }.removable)
        assertTrue(BootMovieLibrary.remove(app, "file:by-hand.webm"))
        assertFalse(File(movies, "by-hand.webm").exists())
    }

    @Test fun steamsOwnMoviesAreNeverDeleted() {
        val own = File(BootMovieLibrary.steamRoot(app), "steamui/movies/deck_startup.webm").also { it.parentFile!!.mkdirs(); it.writeText("movie") }
        val entry = BootMovieLibrary.list(app).first { it.id == "steam:/movies/deck_startup.webm" }
        assertFalse(entry.removable)
        assertFalse(BootMovieLibrary.remove(app, entry.id))
        assertTrue(own.exists())
    }

    @Test fun anImportSurvivesTheRuntimeBeingReset() {
        val name = imported("Keep")
        movies.deleteRecursively()
        assertTrue(BootMovieLibrary.list(app).any { it.id == "file:$name" })
        assertTrue(File(movies, name).isFile)
    }

    @Test fun importsAtTheSameTimeAllLandInTheList() {
        val names = java.util.Collections.synchronizedList(ArrayList<String>())
        (1..8).map { n -> Thread { names.add(imported("Movie")) } }.onEach { it.start() }.forEach { it.join() }
        assertEquals(8, names.toSet().size)
        assertEquals(8, BootMovieLibrary.list(app).count { it.source == BootMovieLibrary.SOURCE_IMPORT })
    }

    @Test fun steamIsPointedBackAtItsDefaultWhenTheStagedMovieIsGone() {
        steamPicksAt(SessionFiles.BOOT_MOVIE_PATH)
        assertTrue(SessionFiles.resetMissingStartupMovie(BootMovieLibrary.steamConfig(app), false))
        assertNull(SessionFiles.startupMoviePath(BootMovieLibrary.steamConfig(app)))
    }

    @Test fun aMissingConfigNeedsNoReset() {
        assertTrue(SessionFiles.resetMissingStartupMovie(BootMovieLibrary.steamConfig(app), true))
    }

    @Test fun aStagedMovieThatIsThereIsLeftAlone() {
        movie("bigpicture_startup.webm")
        steamPicksAt(SessionFiles.BOOT_MOVIE_PATH)
        assertFalse(SessionFiles.resetMissingStartupMovie(BootMovieLibrary.steamConfig(app), false))
        assertEquals(SessionFiles.BOOT_MOVIE_PATH, SessionFiles.startupMoviePath(BootMovieLibrary.steamConfig(app)))
    }

    @Test fun aPendingPickResetsASteamMovieThatWasRemoved() {
        steamPicksAt("/uioverrides/movies/gone.webm")
        assertFalse(SessionFiles.resetMissingStartupMovie(BootMovieLibrary.steamConfig(app), false))
        assertTrue(SessionFiles.resetMissingStartupMovie(BootMovieLibrary.steamConfig(app), true))
        assertNull(SessionFiles.startupMoviePath(BootMovieLibrary.steamConfig(app)))
    }

    @Test fun onlyTheThreeStartupMoviesOfSteamAreListed() {
        listOf("deck_startup", "oled_startup", "startup_machine", "controller_generic_thumbstick_move").forEach {
            File(BootMovieLibrary.steamRoot(app), "steamui/movies/$it.webm").also { f -> f.parentFile!!.mkdirs(); f.writeText("movie") }
        }
        val ids = BootMovieLibrary.list(app).map { it.id }.filter { it.startsWith("steam:") }
        assertEquals(
            listOf("steam:/movies/deck_startup.webm", "steam:/movies/oled_startup.webm", "steam:/movies/startup_machine.webm"),
            ids.sorted(),
        )
    }

    @Test fun aMovieBoughtInThePointsShopIsListedAndAdopted() {
        val file = File(BootMovieLibrary.steamRoot(app), "appcache/startupmovies/Bought.webm").also { it.parentFile!!.mkdirs(); it.writeText("movie") }
        val entry = BootMovieLibrary.list(app).first { it.id == "steam:/appcache/startupmovies/Bought.webm" }
        assertFalse(entry.removable)
        assertEquals(BootMovieLibrary.SOURCE_STEAM, entry.source)
        steamPicksAt("/appcache/startupmovies/Bought.webm")
        assertTrue(BootMovie.syncFromSteam(app))
        assertEquals("steam:/appcache/startupmovies/Bought.webm", BootMovie.choice(app))
        assertEquals(file, BootMovie.currentFile(app))
        assertNull(BootMovieLibrary.file(app, "steam:/../x.webm"))
    }

    @Test fun pathTricksAreNotMovies() {
        movie("a.webm")
        assertNull(BootMovieLibrary.file(app, "file:../a.webm"))
        assertNull(BootMovieLibrary.file(app, "file:bigpicture_startup.webm"))
        assertFalse(BootMovieLibrary.remove(app, "file:../a.webm"))
    }

    @Test fun freeNameNeverReusesAFileOrAStagedName() {
        movie("Intro.webm")
        assertEquals("Intro (2).webm", BootMovieLibrary.freeName(app, "Intro"))
        assertEquals("movie.webm", BootMovieLibrary.freeName(app, "///"))
    }

    @Test fun aMovieChosenInsideSteamIsAdoptedOnce() {
        movie("a.webm"); movie("b.webm")
        BootMovie.select(app, "file:a.webm")
        steamPicks("b.webm")
        assertTrue(BootMovie.syncFromSteam(app))
        assertEquals("file:b.webm", BootMovie.choice(app))
        // Picking another one here is not undone by the same Steam setting.
        BootMovie.select(app, "file:a.webm")
        assertFalse(BootMovie.syncFromSteam(app))
        assertEquals("file:a.webm", BootMovie.choice(app))
        // A new pick inside Steam is adopted again.
        movie("c.webm")
        steamPicks("c.webm")
        assertTrue(BootMovie.syncFromSteam(app))
        assertEquals("file:c.webm", BootMovie.choice(app))
    }

    @Test fun aPickMadeHereBeatsAnOlderSteamPickThatWasNeverSeen() {
        movie("a.webm"); movie("b.webm")
        steamPicks("b.webm")
        BootMovie.select(app, "file:a.webm")
        assertFalse(BootMovie.syncFromSteam(app))
        assertEquals("file:a.webm", BootMovie.choice(app))
    }

    @Test fun steamPointingAtAMovieThatIsNotThereChangesNothing() {
        steamPicks("gone.webm")
        assertFalse(BootMovie.syncFromSteam(app))
        assertEquals(BootMovie.DROIDDECK_30, BootMovie.choice(app))
    }

    @Test fun volumeAndLengthDefaultToTheWholeMovieAtFullVolume() {
        assertEquals(100, SessionPrefs.bootMovieVolume(app))
        assertEquals(0, SessionPrefs.bootMovieSeconds(app))
        SessionPrefs.setBootMovieVolume(app, 40)
        SessionPrefs.setBootMovieSeconds(app, 3)
        assertEquals(40, SessionPrefs.bootMovieVolume(app))
        assertEquals(3, SessionPrefs.bootMovieSeconds(app))
    }
}
