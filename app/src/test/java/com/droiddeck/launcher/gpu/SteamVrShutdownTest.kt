package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.gpu.SteamVrRecoveryPolicy.Shutdown
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SteamVrShutdownTest {
    private var clock = 0L

    private fun session(phase: String, logs: Boolean = true, collected: Boolean = false) =
        JSONObject().put("phase", phase).put("artifactsAvailable", logs).put("artifactsComplete", collected)

    private fun await(state: (Long) -> JSONObject?) =
        SteamVrRecoveryPolicy.awaitShutdown({ state(clock) }, { clock }, { clock += it })

    @Test fun waitsForTheLogsOnceTheSessionHasStopped() {
        val result = await { t ->
            when {
                t < 4_000 -> session("STOPPING")
                t < 24_000 -> session("IDLE")
                else -> session("IDLE", collected = true)
            }
        }
        assertEquals(Shutdown.COMPLETE, result)
        assertEquals(24_000L, clock)
    }

    @Test fun aStuckTeardownGivesUpAfterTheStopDeadline() {
        assertEquals(Shutdown.STUCK, await { session("STOPPING") })
        assertEquals(SteamVrRecoveryPolicy.STOP_MS, clock)
    }

    @Test fun slowLogCollectionIsBounded() {
        assertEquals(Shutdown.LOGS_PENDING, await { if (it < 2_000) null else session("FAILED") })
        assertEquals(2_000L + SteamVrRecoveryPolicy.LOGS_MS, clock)
    }

    @Test fun aSessionWithoutLogsNeedsNoWait() {
        assertEquals(Shutdown.COMPLETE, await { session("IDLE", logs = false) })
        assertEquals(0L, clock)
    }

    @Test fun unreadableStateIsRetriedUntilTheDeadline() {
        var reads = 0
        assertEquals(Shutdown.STUCK, await { reads++; null })
        assertTrue(reads > 1)
    }
}
