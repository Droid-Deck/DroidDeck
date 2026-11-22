package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.runtime.LinuxFex
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShaderPrecompManagerTest {
    @get:Rule val temp = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPrefs() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun findFossilizeReplayPrefersArm64OverX64() {
        val steam = temp.newFolder("steam")
        val rootfs = temp.newFolder("rootfs")
        val x64 = File(steam, "steamrt64/fossilize_replay").apply { parentFile!!.mkdirs(); writeText("x64") }
        assertEquals(ShaderPrecompManager.ReplayBinary(x64, isArm64 = false),
            ShaderPrecompManager.findFossilizeReplay(listOf(steam), rootfs))

        val arm = File(steam, "steamrtarm64/fossilize_replay").apply { parentFile!!.mkdirs(); writeText("arm") }
        assertEquals(ShaderPrecompManager.ReplayBinary(arm, isArm64 = true),
            ShaderPrecompManager.findFossilizeReplay(listOf(steam), rootfs))
    }

    @Test fun findFozArchivesWalksFozpipelinesv6AndSkipsEmptyFiles() {
        val shaderDir = temp.newFolder("shadercache-440")
        val fozDir = File(shaderDir, "fozpipelinesv6").apply { mkdirs() }
        val topFoz = File(fozDir, "cache.1.foz").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val emptyFoz = File(fozDir, "empty.foz").apply { writeBytes(byteArrayOf()) }
        val nestedDir = File(fozDir, "sub").apply { mkdirs() }
        val nestedFoz = File(nestedDir, "steamapp_pipeline_cache.foz").apply { writeBytes(byteArrayOf(4, 5)) }

        val found = ShaderPrecompManager.findFozArchives(shaderDir)
        assertTrue(topFoz in found)
        assertTrue(nestedFoz in found)
        assertFalse(emptyFoz in found)
    }

    @Test fun buildReplayCommandIncludesExpectedFlagsAndIdleWrapper() {
        val cmd = ShaderPrecompManager.buildReplayCommand(
            guestReplayPath = "/root/.local/share/Steam/steamrtarm64/fossilize_replay",
            guestFozPath = "/root/.local/share/Steam/steamapps/shadercache/440/fozpipelinesv6/a.foz",
            guestPipelineCachePath = "/root/.local/share/Steam/steamapps/shadercache/440/mesa_shader_cache_sf/dd.bin",
            guestEnv = mapOf("MESA_DISK_CACHE_DATABASE" to "1"),
            threads = 4,
            maxMemoryMb = 1024,
            skipCompiled = true,
            idleOnly = true,
            useFex = false,
        )
        assertEquals("/bin/sh", cmd[0])
        assertTrue(cmd.contains("MESA_DISK_CACHE_DATABASE=1"))
        assertTrue(cmd.contains("--num-threads"))
        assertTrue(cmd.contains("4"))
        assertTrue(cmd.contains("--shader-cache-size"))
        assertTrue(cmd.contains("1024"))
        assertTrue(cmd.contains("--skip-pipelines-on-compile-required"))
        assertTrue(cmd.contains("--progress"))
        assertFalse(cmd.contains(LinuxFex.TOOL))

        val fexCmd = ShaderPrecompManager.buildReplayCommand(
            guestReplayPath = "/root/.local/share/Steam/steamrt64/fossilize_replay",
            guestFozPath = "/tmp/a.foz",
            guestPipelineCachePath = "/tmp/cache.bin",
            guestEnv = emptyMap(),
            threads = 2,
            maxMemoryMb = 0,
            skipCompiled = false,
            idleOnly = false,
            useFex = true,
        )
        assertEquals("/usr/bin/env", fexCmd[0])
        assertEquals(LinuxFex.TOOL, fexCmd[1])
        assertFalse(fexCmd.contains("--shader-cache-size"))
        assertFalse(fexCmd.contains("--skip-pipelines-on-compile-required"))
    }

    @Test fun parseProgressLineExtractsFractionAndPercent() {
        assertEquals(12 to 200, ShaderPrecompManager.parseProgressLine("Replaying pipelines 12 / 200"))
        assertEquals(75 to 100, ShaderPrecompManager.parseProgressLine("Progress: 75%"))
        assertNull(ShaderPrecompManager.parseProgressLine("Fossilize INFO: Done"))
    }

    @Test fun publishAddsFossilizeLayerOnlyWhenEnabledAndRecordingMissing() {
        GameEnvironmentStore.save(context, GameEnvironment.Config())
        val guest = File(LinuxRuntime.rootDir(context), "root/.config/droiddeck/game-environment.json")

        SessionPrefs.setShaderPrecompGames(context, setOf("440"))
        SessionPrefs.setShaderPrecompEnabled(context, true)
        SessionPrefs.setShaderPrecompRecordMissing(context, true)
        val enabledJson = JSONObject(guest.readText())
        assertEquals(
            "1",
            enabledJson.getJSONObject("games").getJSONObject("440").getString("ENABLE_VK_LAYER_VALVE_steam_fossilize_1"),
        )

        SessionPrefs.setShaderPrecompRecordMissing(context, false)
        val disabledJson = JSONObject(guest.readText())
        assertFalse(disabledJson.getJSONObject("games").has("440"))
    }
}
