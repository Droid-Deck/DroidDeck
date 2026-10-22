package com.droiddeck.launcher.gpu

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SteamVrRecoverySettingsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun recoveryUnblocksOnlyOurHeadsetAndKeepsUserSettings() {
        val file = temp.newFile().apply { writeText("""{
            "driver_droiddeck":{"blocked_by_safe_mode":true,"other":42},
            "driver_other":{"blocked_by_safe_mode":true},
            "steamvr":{"enableSafeMode":true,"environmentMode":0},
            "GpuSpeed":{"gpuSpeed0":450}
        }""") }
        assertTrue(SteamVrRecoverySettings.unblockHeadset(file))
        val after = JSONObject(file.readText())
        assertFalse(after.getJSONObject("driver_droiddeck").getBoolean("blocked_by_safe_mode"))
        assertEquals(42, after.getJSONObject("driver_droiddeck").getInt("other"))
        assertTrue(after.getJSONObject("driver_other").getBoolean("blocked_by_safe_mode"))
        assertTrue(after.getJSONObject("steamvr").getBoolean("enableSafeMode"))
        assertEquals(0, after.getJSONObject("steamvr").getInt("environmentMode"))
        assertEquals(450, after.getJSONObject("GpuSpeed").getInt("gpuSpeed0"))
        val unchanged = file.readText()
        assertFalse(SteamVrRecoverySettings.unblockHeadset(file))
        assertEquals(unchanged, file.readText())
    }

    @Test fun missingOrUnblockedConfigIsNotRewritten() {
        assertFalse(SteamVrRecoverySettings.unblockHeadset(File(temp.root, "missing")))
        for (text in listOf("{}", "{\"driver_droiddeck\":{\"blocked_by_safe_mode\":false}}")) {
            val file = temp.newFile().apply { writeText(text) }
            assertFalse(SteamVrRecoverySettings.unblockHeadset(file))
            assertEquals(text, file.readText())
        }
    }

    @Test fun malformedConfigIsPreservedAndRecoveryFailsInsteadOfReplacingIt() {
        val file = temp.newFile().apply { writeText("broken JSON") }
        assertTrue(runCatching { SteamVrRecoverySettings.unblockHeadset(file) }.isFailure)
        assertEquals("broken JSON", file.readText())
    }
}
