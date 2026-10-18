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
        assertFalse(list["file:b.webm"]!!.removable)
        assertEquals("b", list["file:b.webm"]!!.title)
        assertEquals(BootMovieLibrary.SOURCE_STEAM, list["file:b.webm"]!!.source)
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

    @Test fun moviesThatAreNotDroidDecksAreNeverDeleted() {
        movie("steam-own.webm")
        assertFalse(BootMovieLibrary.remove(app, "file:steam-own.webm"))
        assertTrue(File(movies, "steam-own.webm").exists())
        assertFalse(BootMovieLibrary.list(app).first { it.id == "file:steam-own.webm" }.removable)
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

    @Test fun aMovieSteamKeepsElsewhereIsListedAndAdopted() {
        val file = File(BootMovieLibrary.steamRoot(app), "steamui/movies/Bought.webm").also { it.parentFile!!.mkdirs(); it.writeText("movie") }
        val ids = BootMovieLibrary.list(app).map { it.id }
        assertTrue("steam:/movies/Bought.webm" in ids)
        steamPicksAt("/movies/Bought.webm")
        assertTrue(BootMovie.syncFromSteam(app))
        assertEquals("steam:/movies/Bought.webm", BootMovie.choice(app))
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

    @Test fun scaleQuietsAndCutsTheSoundAtTheSameTime() {
        val pcm = ShortArray(48_000 * 2) { 1000 }
        assertSame(pcm, BootMovieTranscoder.scale(pcm, 2, 1f, Long.MAX_VALUE))
        val quieter = BootMovieTranscoder.scale(pcm, 2, 0.5f, Long.MAX_VALUE)
        assertEquals(pcm.size, quieter.size)
        assertEquals(500.toShort(), quieter[0])
        val cut = BootMovieTranscoder.scale(pcm, 2, 1f, 500_000L)
        assertEquals(24_000 * 2, cut.size)
    }
}
