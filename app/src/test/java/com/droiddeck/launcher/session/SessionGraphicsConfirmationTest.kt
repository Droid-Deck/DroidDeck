package com.droiddeck.launcher.session

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SessionGraphicsConfirmationTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        for (field in listOf("dir", "ended", "endedGraphics")) ReflectionHelpers.setStaticField(SessionPaths::class.java, field, null)
    }

    @Test fun logsOffKeepsConfirmationButANewerUnconfirmedSessionReplacesIt() {
        SessionPrefs.setLogsEnabled(context, false)
        val first = SessionPaths.beginOrCurrent(context, "first")
        File(first, MaliGraphics.PATH_FILE).writeText("venus\n")
        SessionPaths.take()
        SessionPaths.release(context, first)
        assertFalse(first.exists())
        assertEquals(MaliGraphicsMode.VENUS, SessionPaths.graphicsConfirmation(context)?.path)
        SessionPaths.release(context, first)
        assertEquals(MaliGraphicsMode.VENUS, SessionPaths.graphicsConfirmation(context)?.path)
        val next = SessionPaths.beginOrCurrent(context, "first")
        assertNotEquals(first.name, next.name)
        assertEquals(next.name, SessionPaths.graphicsConfirmation(context)?.sessionId)
        assertNull(SessionPaths.graphicsConfirmation(context)?.path)
        SessionPaths.take()
        SessionPaths.release(context, first)
        SessionPaths.release(context, next)
        assertNull(SessionPaths.graphicsConfirmation(context)?.path)
    }

    @Test fun restartedIdleReadsLatestRetainedSessionAndActiveNeverUsesIt() {
        val root = SessionFiles.logDirectory(context).apply { mkdirs() }
        val old = File(root, "2026-11-18-01-steam").apply { mkdirs() }
        val recent = File(root, "2026-11-18-02-steam").apply { mkdirs() }
        File(old, MaliGraphics.PATH_FILE).writeText("bridge")
        File(recent, MaliGraphics.PATH_FILE).writeText("panvk")
        assertEquals(MaliGraphicsMode.PANVK, SessionPaths.graphicsConfirmation(context)?.path)
        File(recent, MaliGraphics.PATH_FILE).delete()
        assertNull(SessionPaths.graphicsConfirmation(context)?.path)
        SessionPaths.beginOrCurrent(context, "active")
        assertNull(SessionPaths.graphicsConfirmation(context)?.path)
    }
}
