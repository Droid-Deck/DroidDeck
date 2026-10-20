package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StartupMovieConfigTest {
    @get:Rule val folder = TemporaryFolder()

    private val older = "\t\t\t\t\"StartupMovie\"\n\t\t\t\t{\n\t\t\t\t\t\"MovieID\"\t\t\"0\"\n\t\t\t\t\t\"LocalPath\"\t\t\"%s\"\n\t\t\t\t}\n"
    private val customization = "\t\"Customization\"\n\t{\n\t\t\"StartupMovie\"\n\t\t{\n\t\t\t\"MovieID\"\t\t\"%s\"\n\t\t\t\"LocalPath\"\t\t\"%s\"\n\t\t}\n\t}\n"

    private fun config(olderPath: String?, id: String?, path: String?): File {
        val text = StringBuilder("\"InstallConfigStore\"\n{\n\t\"Software\"\n\t{\n\t\t\"Valve\"\n\t\t{\n\t\t\t\"Steam\"\n\t\t\t{\n")
        if (olderPath != null) text.append(older.format(olderPath))
        text.append("\t\t\t}\n\t\t}\n\t}\n")
        if (id != null && path != null) text.append(customization.format(id, path))
        text.append("}\n")
        return folder.newFile().also { it.writeText(text.toString()) }
    }

    @Test fun steamReadsTheMovieUnderCustomizationNotTheOlderOne() {
        val file = config(SessionFiles.BOOT_MOVIE_PATH, "39900045022", "/communityitemscache/startupmovies/39900045022_ab.webm")
        assertEquals("/communityitemscache/startupmovies/39900045022_ab.webm", SessionFiles.startupMoviePath(file))
    }

    @Test fun aPickIsWrittenWhereSteamReadsItAndTheOlderPlaceIsKeptInStep() {
        val file = config("/uioverrides/movies/x.webm", "39900045022", "/communityitemscache/startupmovies/39900045022_ab.webm")
        assertTrue(SessionFiles.ensureStartupMovieDefault(file, force = true))
        val text = file.readText()
        assertEquals(SessionFiles.BOOT_MOVIE_PATH, SessionFiles.startupMoviePath(file))
        assertFalse(text.contains("39900045022"))
        assertEquals(2, Regex("\"LocalPath\"\\s+\"" + Regex.escape(SessionFiles.BOOT_MOVIE_PATH) + "\"").findAll(text).count())
    }

    @Test fun aPickMadeInSteamIsLeftAloneWithoutAPendingPick() {
        val file = config(SessionFiles.BOOT_MOVIE_PATH, "0", "/uioverrides/movies/Undertale.webm")
        assertFalse(SessionFiles.ensureStartupMovieDefault(file, force = false))
        assertEquals("/uioverrides/movies/Undertale.webm", SessionFiles.startupMoviePath(file))
    }

    @Test fun aConfigWithoutCustomizationGetsOne() {
        val file = config(null, null, null)
        assertTrue(SessionFiles.ensureStartupMovieDefault(file, force = false))
        val text = file.readText()
        assertTrue(text.contains("\"Customization\""))
        assertEquals(SessionFiles.BOOT_MOVIE_PATH, SessionFiles.startupMoviePath(file))
        assertEquals(2, Regex("\"StartupMovie\"").findAll(text).count())
    }

    @Test fun aMissingStagedMovieResetsTheSectionSteamReads() {
        val file = config(SessionFiles.BOOT_MOVIE_PATH, "0", SessionFiles.BOOT_MOVIE_PATH)
        assertTrue(SessionFiles.resetMissingStartupMovie(file, false))
        assertNull(SessionFiles.startupMoviePath(file))
    }
}
