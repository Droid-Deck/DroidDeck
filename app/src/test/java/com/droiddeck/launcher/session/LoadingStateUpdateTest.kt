package com.droiddeck.launcher.session

import android.app.Application
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "en")
class LoadingStateUpdateTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun extractingReplacesAnIncompleteDownload() {
        assertApplying(read("Downloading update (50 of 100 KB)", "Extracting package..."))
    }

    @Test fun installingClearsTheCompletedDownloadPercentage() {
        assertApplying(read("Downloading update (100 of 100 KB)", "Installing update..."))
    }

    @Test fun acceptsForwardedMessagesAndBootstrapPrefixes() {
        for (message in listOf(
            "Installing update...",
            "[2026-10-25 12:00:02] Extracting package...",
            "[----] Installing update...\r",
            "[2026-10-25 12:00:02] [----] Extracting package...",
        )) assertApplying(read(message))
    }

    @Test fun aNewDownloadReplacesAnOlderApplyingStage() {
        val state = read("Installing update...", "Downloading update (25 of 100 KB)")
        assertEquals(25, state.percent)
        assertEquals("Downloading the Steam client update · 25%", state.step)
    }

    @Test fun aNewScriptStepReplacesAnOlderApplyingStage() {
        val state = read("Installing update...", "== STEP 12:00:03 checking the Linux runtime")
        assertEquals("checking the Linux runtime", state.step)
        assertEquals(LoadingState.Topic.RUNTIME, state.topic)
        assertEquals(-1, state.percent)
    }

    @Test fun unrelatedOutputKeepsTheApplyingStage() {
        assertApplying(read("Installing update...", "Loading cached metrics", ""))
    }

    @Test fun similarlyNamedMessagesDoNotReplaceTheDownload() {
        val state = read(
            "Downloading update (50 of 100 KB)",
            "worker: Installing update...",
            "Installing update for Proton...",
            "[12:00:02] error: Extracting package...",
        )
        assertEquals(50, state.percent)
        assertEquals("Downloading the Steam client update · 50%", state.step)
    }

    @Test fun anOlderRuntimeCanReturnToItsPartCountAfterApplying() {
        val state = read("Installing update...", "== STEP 12:00:03 downloading Steam: a_all (1/2)")
        assertEquals(50, state.percent)
        assertEquals("Downloading the Steam client · a_all", state.step)
    }

    @Test fun desktopStartupDoesNotConsumeSteamApplyingMessages() {
        val state = read(
            "== STEP 12:00:01 starting the desktop", "Installing update...", steam = false,
        )
        assertEquals("starting the desktop", state.step)
        assertEquals(LoadingState.Topic.DESKTOP, state.topic)
    }

    @Test fun anExistingLoadingStateFollowsUpdatesAcrossPolls() {
        val context = RuntimeEnvironment.getApplication()
        val log = log("Downloading update (100 of 100 KB)")
        val state = LoadingState(context)
        state.update(context, log)
        assertEquals(100, state.percent)
        log.appendText("\n[12:00:02] Installing update...")
        state.update(context, log)
        assertApplying(state)
        log.appendText("\nDownloading update (20 of 100 KB)")
        state.update(context, log)
        assertEquals(20, state.percent)
    }

    private fun read(vararg lines: String, steam: Boolean = true): LoadingState {
        val context = RuntimeEnvironment.getApplication()
        return LoadingState(context, steam).apply { update(context, log(*lines)) }
    }

    private fun log(vararg lines: String): File = temp.newFile().apply {
        writeText((listOf("== STEP 12:00:00 starting the Steam client") + lines).joinToString("\n"))
    }

    private fun assertApplying(state: LoadingState) {
        assertEquals(-1, state.percent)
        assertEquals("Applying the Steam client update…", state.step)
        assertEquals(LoadingState.Topic.STEAM, state.topic)
        assertTrue(state.readable)
    }
}
