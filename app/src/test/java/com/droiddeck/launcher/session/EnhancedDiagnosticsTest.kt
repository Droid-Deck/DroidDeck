package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class EnhancedDiagnosticsTest {
    private lateinit var context: Context
    private lateinit var guest: File

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SessionPaths.take()?.let { SessionPaths.release(context, it) }
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit()
            .remove("logs")
            .remove("gameDebugLogsEnabled")
            .remove("enhancedDiagnosticsNext")
            .remove("enhancedDiagnosticsActiveSessions")
            .commit()
        guest = File(LinuxRuntime.rootDir(context), "root/.config/droiddeck/game-environment.json")
    }

    @After fun tearDown() {
        SessionPaths.take()?.let { folder ->
            SessionPaths.release(context, folder)
            folder.deleteRecursively()
        }
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit()
            .remove("logs")
            .remove("gameDebugLogsEnabled")
            .remove("enhancedDiagnosticsNext")
            .remove("enhancedDiagnosticsActiveSessions")
            .commit()
        GameEnvironmentStore.publish(context)
    }

    @Test fun armedSessionIsRetainedAndConsumesTheOneShotPreference() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().putBoolean("logs", false).commit()
        assertTrue(EnhancedDiagnostics.setArmed(context, true))

        val folder = SessionPaths.beginOrCurrent(context, "enhanced-test")

        assertEquals(SessionFiles.logDirectory(context), folder.parentFile)
        assertFalse(EnhancedDiagnostics.isArmed(context))
        assertTrue(EnhancedDiagnostics.isActive(context, folder))
        val manifest = JSONObject(File(folder, EnhancedDiagnostics.MANIFEST_FILE).readText())
        assertEquals(1, manifest.getInt("schema"))
        assertEquals("enhanced", manifest.getString("profile"))
        assertEquals(folder.name, manifest.getString("sessionId"))
        assertTrue(manifest.getBoolean("exportBundlesBounded"))
        assertFalse(manifest.getBoolean("rawCaptureByteBounded"))
    }

    @Test fun activeProfileForcesGameDiagnosticsWithoutChangingUserPreferences() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit()
            .putBoolean("logs", false)
            .putBoolean("gameDebugLogsEnabled", false)
            .commit()
        assertTrue(EnhancedDiagnostics.setArmed(context, true))
        SessionPaths.beginOrCurrent(context, "enhanced-game-logs")

        GameEnvironmentStore.publish(context)

        assertTrue(JSONObject(guest.readText()).getBoolean(GameEnvironmentStore.GAME_DEBUG_LOGS))
        assertFalse(SessionPrefs.logsEnabled(context))
        assertFalse(SessionPrefs.gameDebugLogsEnabled(context))
    }

    @Test fun cancellingTheOneShotLeavesOrdinaryLogRetentionUnchanged() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().putBoolean("logs", false).commit()
        assertTrue(EnhancedDiagnostics.setArmed(context, true))
        assertTrue(EnhancedDiagnostics.setArmed(context, false))

        val folder = SessionPaths.beginOrCurrent(context, "ordinary-test")

        assertEquals(File(context.cacheDir, "session-logs"), folder.parentFile)
        assertFalse(EnhancedDiagnostics.isActive(context, folder))
    }

    @Test fun guestCannotForgeEnhancedDiagnosticsByWritingAMarker() {
        val folder = SessionPaths.beginOrCurrent(context, "forged-marker")
        File(folder, EnhancedDiagnostics.MANIFEST_FILE).writeText(
            """{"schema":1,"profile":"enhanced"}""",
        )

        assertFalse(EnhancedDiagnostics.isActive(context, folder))
        assertNull(EnhancedDiagnostics.manifest(context, folder))
    }

    @Test fun trustedStateSurvivesGuestMarkerDeletionAndSessionRecovery() {
        assertTrue(EnhancedDiagnostics.setArmed(context, true))
        val folder = SessionPaths.beginOrCurrent(context, "deleted-marker")
        assertTrue(File(folder, EnhancedDiagnostics.MANIFEST_FILE).delete())

        assertTrue(EnhancedDiagnostics.isActive(context, File(folder.path)))
        assertEquals(folder.name, EnhancedDiagnostics.manifest(context, folder)?.getString("sessionId"))
        assertEquals(folder, SessionPaths.take())
        assertTrue(EnhancedDiagnostics.isActive(context, folder))
    }

    @Test fun informationalMarkerFailureDoesNotRearmTheNextSession() {
        val parent = SessionFiles.logDirectory(context).apply { mkdirs() }
        val folder = File(parent, "2026-11-04-99-marker-failure").apply { mkdirs() }
        File(folder, EnhancedDiagnostics.MANIFEST_FILE).mkdirs()
        assertTrue(EnhancedDiagnostics.setArmed(context, true))

        assertTrue(EnhancedDiagnostics.consumeInto(context, folder))

        assertTrue(EnhancedDiagnostics.isActive(context, folder))
        assertFalse(EnhancedDiagnostics.isArmed(context))
        folder.deleteRecursively()
    }

    @Test fun aReusedSessionPathDoesNotInheritTrustedState() {
        assertTrue(EnhancedDiagnostics.setArmed(context, true))
        val original = SessionPaths.beginOrCurrent(context, "reused-path")
        assertTrue(EnhancedDiagnostics.isActive(context, original))
        assertEquals(original, SessionPaths.take())
        original.deleteRecursively()

        val replacement = SessionPaths.beginOrCurrent(context, "reused-path")

        assertEquals(original.path, replacement.path)
        assertFalse(EnhancedDiagnostics.isActive(context, replacement))
    }
}
