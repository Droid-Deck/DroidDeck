package com.droiddeck.launcher.glasses

import android.app.Application
import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LumaRgbDiagnosticTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val statusFile get() = File(context.filesDir, "spatial/rgb-diagnostic.json")

    @Before fun reset() { statusFile.delete(); statusFile.parentFile?.mkdirs() }

    @Test fun aDeadWorkerOrPreviousBootCannotLeaveCapturePermanentlyBusy() {
        for (started in listOf(SystemClock.elapsedRealtime() - 16_000, SystemClock.elapsedRealtime() + 5_000)) {
            statusFile.writeText("""{"state":"capturing","startedMs":$started}""")
            assertEquals("failed", LumaRgbDiagnostic.status(context).getString("state"))
        }
    }

    @Test fun onlyPendingCapturesExpire() {
        statusFile.writeText("""{"state":"completed","path":"saved-capture"}""")
        assertEquals("completed", LumaRgbDiagnostic.status(context).getString("state"))
        statusFile.writeText("""{"state":"capturing","startedMs":${SystemClock.elapsedRealtime()}}""")
        assertEquals("capturing", LumaRgbDiagnostic.status(context).getString("state"))
    }

    @Test fun longerReliabilityCaptureRemainsBusyUntilItsBoundedDeadline() {
        val started = SystemClock.elapsedRealtime() - 20_000
        statusFile.writeText("""{"state":"capturing","startedMs":$started,"timeoutMs":45000}""")
        assertEquals("capturing", LumaRgbDiagnostic.status(context).getString("state"))
        statusFile.writeText("""{"state":"capturing","startedMs":${started-30_000},"timeoutMs":999999}""")
        assertEquals("failed", LumaRgbDiagnostic.status(context).getString("state"))
    }

    @Test fun terminalResultWaitsForWorkerExitBeforeAnotherRequest() {
        for (result in listOf("completed","failed")) {
            val record=JSONObject().put("state",result).put("workerPid",123).put("startedMs",1000)
            val finishing=LumaRgbDiagnostic.resolveStatus(JSONObject(record.toString()),2000,true)
            assertEquals("capturing",finishing.getString("state"))
            assertEquals("finishing",finishing.getString("phase"))
            assertEquals(result,finishing.getString("resultState"))
            assertTrue(finishing.getBoolean("busy"))
            val exited=LumaRgbDiagnostic.resolveStatus(record,2000,false)
            assertEquals(result,exited.getString("state"))
            assertFalse(exited.getBoolean("busy"))
        }
    }

    @Test fun deadWorkerFailsImmediatelyAndExpiredWorkerRemainsBusyOnlyUntilExit() {
        val record=JSONObject().put("state","capturing").put("workerPid",123).put("startedMs",1000)
        val dead=LumaRgbDiagnostic.resolveStatus(JSONObject(record.toString()),2000,false)
        assertEquals("failed",dead.getString("state"))
        assertFalse(dead.getBoolean("busy"))
        val expired=LumaRgbDiagnostic.resolveStatus(JSONObject(record.toString()),17000,true)
        assertEquals("failed",expired.getString("state"))
        assertTrue(expired.getBoolean("busy"))
        val exited=LumaRgbDiagnostic.resolveStatus(record,17000,false)
        assertEquals("failed",exited.getString("state"))
        assertFalse(exited.getBoolean("busy"))
    }
}
