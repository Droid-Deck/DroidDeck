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
class BootMovieTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val custom get() = File(app.filesDir, "boot-movie/custom.webm")

    @Before fun clear() {
        app.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        File(app.filesDir, "boot-movie").deleteRecursively()
    }

    @Test fun defaultsToThe30FpsMovieWithSound() {
        assertEquals(BootMovie.DROIDDECK_30, BootMovie.choice(app))
        assertTrue(SessionPrefs.bootMovieSound(app))
        assertNull(BootMovie.customName(app))
    }

    @Test fun customFallsBackWhenItsFileIsMissing() {
        SessionPrefs.setBootMovie(app, BootMovie.CUSTOM)
        assertEquals(BootMovie.DROIDDECK_30, BootMovie.choice(app))

        custom.parentFile!!.mkdirs()
        custom.writeText("movie")
        SessionPrefs.setBootMovieCustomName(app, "intro.webm")
        assertEquals(BootMovie.CUSTOM, BootMovie.choice(app))
        assertEquals("intro.webm", BootMovie.customName(app))
    }

    @Test fun unknownChoiceFallsBack() {
        SessionPrefs.setBootMovie(app, "dd120")
        assertEquals(BootMovie.DROIDDECK_30, BootMovie.choice(app))
        SessionPrefs.setBootMovie(app, BootMovie.DROIDDECK_60)
        assertEquals(BootMovie.DROIDDECK_60, BootMovie.choice(app))
    }

    @Test fun removingTheCustomMovieReturnsToTheDefault() {
        custom.parentFile!!.mkdirs()
        custom.writeText("movie")
        File(custom.parentFile, "custom-muted.webm").writeText("muted")
        SessionPrefs.setBootMovieCustomName(app, "intro.webm")
        SessionPrefs.setBootMovie(app, BootMovie.CUSTOM)

        BootMovie.removeCustom(app)

        assertFalse(custom.exists())
        assertFalse(File(custom.parentFile, "custom-muted.webm").exists())
        assertNull(BootMovie.customName(app))
        assertEquals(BootMovie.DROIDDECK_30, SessionPrefs.bootMovie(app))
    }

    @Test fun oversizedImportsAreRefusedBeforeReading() {
        val big = File(app.cacheDir, "big.webm")
        java.io.RandomAccessFile(big, "rw").use { it.setLength(BootMovie.MAX_BYTES + 1) }
        assertEquals(BootMovie.Import.TooLarge, BootMovie.import(app, big))
        assertFalse(custom.exists())
    }

    @Test fun resetCoversTheChoiceButNotTheImportedName() {
        assertTrue(SettingsReset.isSessionSetting("bootMovie"))
        assertTrue(SettingsReset.isSessionSetting("bootMovieSound"))
        assertFalse(SettingsReset.isSessionSetting("bootMovieCustomName"))
    }
}
