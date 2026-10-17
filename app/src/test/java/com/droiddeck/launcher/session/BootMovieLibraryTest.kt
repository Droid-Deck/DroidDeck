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
        BootMovieLibrary.steamConfig(app).delete()
    }

    private fun movie(name: String) = File(movies, name).also { it.parentFile!!.mkdirs(); it.writeText("movie") }

    private fun steamPicks(name: String) {
        BootMovieLibrary.steamConfig(app).also { it.parentFile!!.mkdirs() }.writeText(
            "\"InstallConfigStore\"\n{\n\t\"StartupMovie\"\n\t{\n\t\t\"MovieID\"\t\t\"x\"\n\t\t\"LocalPath\"\t\t\"/uioverrides/movies/$name\"\n\t}\n}\n",
        )
    }

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
        movie("a.webm"); movie("b.webm")
        BootMovieLibrary.register(app, "a.webm", "Fancy", BootMovieLibrary.SOURCE_REPO, "id1")
        val list = BootMovieLibrary.list(app).associateBy { it.id }
        assertEquals("Fancy", list["file:a.webm"]!!.title)
        assertEquals(BootMovieLibrary.SOURCE_REPO, list["file:a.webm"]!!.source)
        assertEquals("b", list["file:b.webm"]!!.title)
        assertEquals(BootMovieLibrary.SOURCE_STEAM, list["file:b.webm"]!!.source)
        assertEquals(setOf("id1"), BootMovieLibrary.installedRepoIds(app))
        assertEquals("a.webm", BootMovieLibrary.nameForRepo(app, "id1"))
    }

    @Test fun aChosenFileMovieIsKeptAndFallsBackOnceItIsGone() {
        movie("a.webm")
        BootMovie.select(app, "file:a.webm")
        assertEquals("file:a.webm", BootMovie.choice(app))
        assertTrue(BootMovieLibrary.remove(app, "file:a.webm"))
        assertEquals(BootMovie.DROIDDECK_30, BootMovie.choice(app))
        assertFalse(File(movies, "a.webm").exists())
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
        steamPicks("a.webm")
        assertFalse(BootMovie.syncFromSteam(app))
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
