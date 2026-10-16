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

    @Test fun unreadableImportsAreRefused() {
        val junk = File(app.cacheDir, "junk.webm")
        junk.writeBytes(ByteArray(1024))
        assertEquals(BootMovie.Import.Unsupported(null), BootMovie.import(app, junk))
        assertFalse(custom.exists())
    }

    @Test fun convertedMoviesFitA720pBoxAndKeepTheirShape() {
        fun size(w: Int, h: Int, r: Int) = BootMovieTranscoder.layout(w, h, r).let { it.width to it.height }
        assertEquals(1280 to 720, size(3840, 2160, 0))
        assertEquals(640 to 480, size(640, 480, 180))
        assertEquals(960 to 720, size(1440, 1080, 0))
    }

    @Test fun portraitMoviesArePillarboxedInto16x9() {
        for (layout in listOf(BootMovieTranscoder.layout(1920, 1080, 90), BootMovieTranscoder.layout(1080, 1920, 0))) {
            assertEquals(1280, layout.width)
            assertEquals(720, layout.height)
            assertEquals(404 to 720, layout.contentWidth to layout.contentHeight)
            assertEquals(438, layout.x)
        }
    }

    @Test fun audioIsResampledTo48kHz() {
        val stereo = ShortArray(44_100 * 2) { if (it % 2 == 0) 1000 else -1000 }
        val out = BootMovieTranscoder.resample(stereo, 2, 44_100)
        assertEquals(48_000 * 2, out.size)
        assertTrue(out.withIndex().all { (i, v) -> v == (if (i % 2 == 0) 1000 else -1000).toShort() })
        assertSame(stereo, BootMovieTranscoder.resample(stereo, 2, 48_000))
    }

    @Test fun resetCoversTheChoiceButNotTheImportedName() {
        assertTrue(SettingsReset.isSessionSetting("bootMovie"))
        assertTrue(SettingsReset.isSessionSetting("bootMovieSound"))
        assertFalse(SettingsReset.isSessionSetting("bootMovieCustomName"))
    }
}
