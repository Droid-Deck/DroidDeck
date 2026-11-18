package com.droiddeck.launcher.session

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SessionStartTimeTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SessionPaths.take()?.let { SessionPaths.release(context, it) }
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().putBoolean("logs", false).commit()
    }

    @After fun tearDown() {
        SessionPaths.take()?.let { SessionPaths.release(context, it) }
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().remove("logs").commit()
    }

    @Test fun theCurrentFolderKeepsTheTimeItWasMade() {
        val before = System.currentTimeMillis()
        val folder = SessionPaths.beginOrCurrent(context, "start-time")
        val after = System.currentTimeMillis()
        val started = SessionPaths.startedAt(folder)
        assertTrue(started in before..after)
        Thread.sleep(5)
        assertEquals(folder, SessionPaths.beginOrCurrent(context, "start-time"))
        assertEquals(started, SessionPaths.startedAt(folder))
    }

    @Test fun otherAndEndedFoldersHaveNoStartTime() {
        val folder = SessionPaths.beginOrCurrent(context, "start-time")
        assertEquals(0L, SessionPaths.startedAt(File(folder.parentFile, "elsewhere")))
        SessionPaths.take()?.let { SessionPaths.release(context, it) }
        assertEquals(0L, SessionPaths.startedAt(folder))
    }

    @Test fun theDriverLoaderLinesReachTheGraphicsReport() {
        val loader = "11-09 06:09:04.930 20010 24772 I hook_impl: hook_android_dlopen_ext: hook failed: failed to load custom driver: dlopen failed!"
        assertTrue(GraphicsReport.COMPOSITOR.containsMatchIn(loader))
        assertTrue(GraphicsReport.COMPOSITOR.containsMatchIn("11-09 06:09:04.931 20010 24772 E adrenotools: Couldn't preload the hook implementation"))
        assertFalse(GraphicsReport.COMPOSITOR.containsMatchIn("11-09 06:09:05.563 20010 24802 I SessionService: game thread hints: Odin 3 only"))
    }
}
