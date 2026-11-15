package com.droiddeck.launcher.session

import android.app.Application
import com.droiddeck.launcher.R
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.gpu.GpuInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [33], application = Application::class, qualifiers = "en-rUS",
    instrumentedPackages = ["com.droiddeck.launcher.gpu.GpuInfo"],
    shadows = [SessionEndHintTest.DetectedGpu::class],
)
class SessionEndHintTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var activity: SessionActivity
    private var previousArchitecture: String? = null

    @Before
    fun setUp() {
        previousArchitecture = SessionState.steamClientArch
        DetectedGpu.family = GpuInfo.Family.NOT_ADRENO
        activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        SessionState.steamClientArch = "arm64"
    }

    @After
    fun tearDown() {
        SessionState.steamClientArch = previousArchitecture
        DetectedGpu.family = GpuInfo.Family.NOT_ADRENO
    }

    @Test
    fun sigillOnArm64IncludesTheExperimentalX64Fallback() {
        SessionState.steamClientArch = "arm64"

        assertEquals(
            activity.getString(R.string.session_end_steam_sigill) + "\n" + activity.getString(R.string.session_end_steam_try_x64),
            hintText(sigillLog()),
        )
    }

    @Test
    fun sigillOnX86_64DoesNotSuggestSwitchingToItself() {
        SessionState.steamClientArch = "x86_64"

        assertEquals(activity.getString(R.string.session_end_steam_sigill), hintText(sigillLog()))
    }

    @Test
    fun sigillWithUnknownArchitectureDoesNotAssumeArm64() {
        SessionState.steamClientArch = null

        assertEquals(activity.getString(R.string.session_end_steam_sigill), hintText(sigillLog()))
    }

    @Test
    fun vulkanFailureOnAdrenoSelectsTheDriverHint() {
        for (family in listOf(GpuInfo.Family.A6XX, GpuInfo.Family.ADRENO_UNKNOWN)) {
            DetectedGpu.family = family

            assertEquals(family.name, activity.getString(R.string.session_end_vulkan_device), hintText(vulkanLog()))
        }
    }

    @Test
    fun vulkanFailureOnNonAdrenoExplainsTheMissingLinuxDriver() {
        DetectedGpu.family = GpuInfo.Family.NOT_ADRENO

        assertEquals(activity.getString(R.string.session_end_gpu_unsupported), hintText(vulkanLog()))
    }

    @Test
    fun missingEmptyAndUnrelatedLogsHaveNoHint() {
        assertNull(hint(null))
        assertNull(hint(File(temporary.root, "missing.log")))
        assertNull(hint(log("")))
        assertNull(hint(log("game: Illegal instruction\n== steam exited rc=132")))
    }

    private fun sigillLog() = log("== STEP 12:00:00 starting the Steam client\n== steam exited rc=132\n")

    private fun vulkanLog() = log("[gamescope] [Error] vulkan: failed to find physical device\n")

    private fun log(text: String): File = temporary.newFile().apply { writeText(text) }

    private fun hint(log: File?): Any? = SessionActivity::class.java
        .getDeclaredMethod("sessionEndHint", File::class.java).apply { isAccessible = true }.invoke(activity, log)

    private fun hintText(log: File): String {
        val result = hint(log)
        assertNotNull(result)
        return ReflectionHelpers.getField(result, "text")
    }

    @Implements(value = GpuInfo.Companion::class, isInAndroidSdk = false)
    class DetectedGpu {
        @Implementation
        fun detect(): GpuInfo = GpuInfo("test GPU", if (family == GpuInfo.Family.A6XX) 610 else 0, family, "", false)

        companion object {
            var family = GpuInfo.Family.NOT_ADRENO
        }
    }
}
