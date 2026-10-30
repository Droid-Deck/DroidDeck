package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionLaunchFailureTest {
    private fun find(vararg lines: String) = SessionLaunchFailure.find(lines.asSequence())

    @Test fun installedSteamThatExitsWithSigillHasACpuFailureHint() {
        assertEquals(SessionLaunchFailure.STEAM_ILLEGAL_INSTRUCTION, find(
            "== STEP 06:35:36.987 Steam client ready",
            "== STEP 06:35:41.058 starting the Steam client",
            "/usr/local/bin/droiddeck-session: line 309: 1192 Illegal instruction steam",
            "== steam exited rc=132",
            "(EE) failed to read Wayland events: Broken pipe",
        ))
    }

    @Test fun gamescopeWithoutAPhysicalDeviceHasAGraphicsFailureHint() {
        assertEquals(SessionLaunchFailure.VULKAN_DEVICE_MISSING, find(
            "[gamescope] [\u001b[0;31mError\u001b[0m] \u001b[0;37mvulkan:\u001b[0m failed to find physical device",
            "SDL_Vulkan_CreateSurface failed: VK_KHR_wayland_surface extension is not enabled in the Vulkan instance.Failed to create backend.",
        ))
    }

    @Test fun unrelatedIllegalInstructionsAndExitCodesAreNotBlamedOnSteam() {
        assertNull(find("game: Illegal instruction", "== steam exited rc=132"))
        assertNull(find("== STEP 12:00:00 starting the Steam client", "== steam exited rc=139"))
        assertNull(find("== STEP 12:00:00 starting the Steam client", "== steam exited rc=1320"))
        assertNull(find("== STEP 12:00:00 starting the Steam client", "== steam exited rc=0"))
    }

    @Test fun onlyTheLatestClientAttemptCounts() {
        assertNull(find(
            "== STEP 12:00:00 starting the Steam client", "== steam exited rc=132",
            "== STEP 12:01:00 starting the Steam client", "== steam exited rc=0",
        ))
    }

    @Test fun aLaterSuccessfulDeviceSelectionClearsTheGraphicsFailure() {
        assertNull(find(
            "[gamescope] [Error] vulkan: failed to find physical device",
            "[gamescope] [Info] vulkan: selecting physical device 'Turnip Adreno (TM) 610': queue family 0",
        ))
    }

    @Test fun aNewSessionDoesNotReuseAnOldFailure() {
        assertNull(find(
            "== STEP 12:00:00 starting the Steam client", "== steam exited rc=132",
            "== droiddeck-session Fri Oct 23 12:01:00 mode=steam",
        ))
    }
}
