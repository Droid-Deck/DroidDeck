package com.droiddeck.launcher.gpu

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SteamVrDeviceLossLogTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun oldErrorsAreIgnoredButFreshSplitLinesAreDetected() {
        val file = temp.newFile().apply { writeText("old vkerror=-4\n") }
        val log = SteamVrDeviceLossLog(file)
        assertFalse(log.poll())
        file.appendText("WaitForAcquire timed out\nFailed GetDeltas(Compositor): vkerror=")
        assertFalse(log.poll())
        file.appendText("-4\n")
        assertTrue(log.poll())
        assertTrue(log.poll()) // Keep pending until the activity is foreground.
    }

    @Test fun onlyExplicitDeviceLossTriggersRecovery() {
        assertTrue(SteamVrDeviceLossLog.isDeviceLoss("Failed GetDeltas: vkerror=-4\n"))
        assertTrue(SteamVrDeviceLossLog.isDeviceLoss("Submit returned VK_ERROR_DEVICE_LOST\n"))
        for (text in listOf("vkerror=-40", "vkerror=-1", "headset taken off", "WaitForAcquire timed out",
            "Unexpected failure when checking for timeline signal", "Failed to submit to queue")) {
            assertFalse(text, SteamVrDeviceLossLog.isDeviceLoss(text))
        }
    }

    @Test fun handlesMissingLogCreationAndTruncation() {
        val file = File(temp.root, "vrcompositor.txt")
        val log = SteamVrDeviceLossLog(file)
        assertFalse(log.poll())
        file.writeText("healthy line\n".repeat(100))
        assertFalse(log.poll())
        file.writeText("vkerror=-4\n")
        assertTrue(log.poll())
    }

    @Test fun detectsReplacementEvenWhenNewFileIsLargerThanTheOldOffset() {
        val file = temp.newFile().apply { writeText("old\n".repeat(50)) }
        val log = SteamVrDeviceLossLog(file)
        assertTrue(file.renameTo(File(temp.root, "previous.txt")))
        file.writeText("VK_ERROR_DEVICE_LOST\n" + "new\n".repeat(100))
        assertTrue(log.poll())
    }

    @Test fun scansBoundedTailOfRapidlyGrowingFaultLogs() {
        val file = temp.newFile()
        val log = SteamVrDeviceLossLog(file)
        file.writeText("ordinary line\n".repeat(100_000) + "vkerror=-4\n")
        assertTrue(log.poll())
    }

    @Test fun catchesTruncateAndRegrowBetweenPollsWithoutAnInodeChange() {
        val file = temp.newFile().apply { writeText("previous run\n".repeat(100)) }
        val log = SteamVrDeviceLossLog(file)
        file.writeText("new run vkerror=-4\n" + "new output\n".repeat(200))
        assertTrue(log.poll())
    }

    @Test fun cooldownSurvivesRepeatedFailuresAndClockRollback() {
        val now = 1_000_000L
        assertTrue(SteamVrRecoveryPolicy.canRestartAutomatically(0, now))
        assertFalse(SteamVrRecoveryPolicy.canRestartAutomatically(now, now))
        assertFalse(SteamVrRecoveryPolicy.canRestartAutomatically(now, now - 1))
        assertFalse(SteamVrRecoveryPolicy.canRestartAutomatically(now, now + 599_999))
        assertTrue(SteamVrRecoveryPolicy.canRestartAutomatically(now, now + 600_000))
    }
}
