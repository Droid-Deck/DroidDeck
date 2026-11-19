package com.droiddeck.launcher.session

import android.app.Application
import com.droiddeck.launcher.R
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.gpu.GpuInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    private var previousLog: File? = null
    private var previousDirectory: File? = null
    private var previousFailureCode: String? = null
    private var previousFailureMessage: String? = null

    @Before
    fun setUp() {
        previousArchitecture = SessionState.steamClientArch
        previousLog = SessionState.logFile
        previousDirectory = SessionState.logDirectory
        previousFailureCode = SessionState.failureCode
        previousFailureMessage = SessionState.failureMessage
        SessionState.logFile = null
        SessionState.logDirectory = null
        SessionState.failureCode = null
        SessionState.failureMessage = null
        DetectedGpu.family = GpuInfo.Family.NOT_ADRENO
        DetectedGpu.name = "test GPU"
        activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        SessionState.steamClientArch = "arm64"
    }

    @After
    fun tearDown() {
        SessionState.steamClientArch = previousArchitecture
        SessionState.logFile = previousLog
        SessionState.logDirectory = previousDirectory
        SessionState.failureCode = previousFailureCode
        SessionState.failureMessage = previousFailureMessage
        DetectedGpu.family = GpuInfo.Family.NOT_ADRENO
        DetectedGpu.name = "test GPU"
    }

    @Test
    fun maliFailureKeepsItsSpecificReason() {
        assertEquals("Venus lacks nullDescriptor required by Zink.",
            hintText(log("== mali graphics failed: Venus lacks nullDescriptor required by Zink.\n== steam exited rc=69\n")))
        try {
            SessionState.failureCode = "MALI_GRAPHICS_FAILED"
            SessionState.failureMessage = "MaliBridge is unavailable."
            assertEquals("MaliBridge is unavailable.", ReflectionHelpers.getField<String>(exitHint(-1), "text"))
        } finally {
            SessionState.failureCode = null
            SessionState.failureMessage = null
        }
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
    fun vulkanFailureOnNonAdrenoIdentifiesTheGpuAndOffersDriverGuidance() {
        for (family in GpuInfo.Family.entries.filter { it.mali || it == GpuInfo.Family.POWERVR || it == GpuInfo.Family.NOT_ADRENO }) {
            DetectedGpu.family = family
            val expected = "test GPU (${family.label("test GPU")})\n" + activity.getString(R.string.session_end_vulkan_device)
            assertEquals(family.name, expected, hintText(vulkanLog()))
        }
        DetectedGpu.family = GpuInfo.Family.POWERVR
        DetectedGpu.name = "Samsung Xclipse 940"
        assertEquals("Samsung Xclipse 940 (Samsung Xclipse)\n" + activity.getString(R.string.session_end_vulkan_device),
            hintText(vulkanLog()))
    }

    @Test
    fun cleanExitRetainsAnUnrecoveredPresentationFault() {
        SessionState.logDirectory = temporary.root
        File(temporary.root, "wayland.log").writeText("DD_PRESENT_FAULT: GPU completion is unknown\n")
        val hint = exitHint(0)
        assertNotNull(hint)
        assertEquals(activity.getString(R.string.session_end_present_fault, "GPU completion is unknown"),
            ReflectionHelpers.getField<String>(hint, "text"))
        assertTrue(ReflectionHelpers.getField(hint, "evenOnSuccess"))
    }

    @Test
    fun cleanExitIgnoresRecoveredFaultsAndUnrelatedSessionWarnings() {
        SessionState.logDirectory = temporary.root
        File(temporary.root, "wayland.log").writeText("DD_PRESENT_FAULT: old fault\npresentation recovered: fault cleared\n")
        SessionState.logFile = sigillLog()
        assertNull(exitHint(0))
    }

    @Test
    fun startFailureShowsTheRecordedMessage() {
        SessionState.failureCode = "POWERVR_MISSING"
        SessionState.failureMessage = "x"
        val hint = exitHint(-1)
        assertNotNull(hint)
        assertEquals("x", ReflectionHelpers.getField<String>(hint, "text"))
    }

    @Test
    fun guestExitMessageIsNotShownAsAStartFailure() {
        SessionState.failureCode = "GUEST_EXIT"
        SessionState.failureMessage = "Session guest exited with status 1"
        assertNull(exitHint(1))
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

    private fun exitHint(status: Int): Any? = SessionActivity::class.java
        .getDeclaredMethod("sessionExitHint", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, status)

    private fun hintText(log: File): String {
        val result = hint(log)
        assertNotNull(result)
        return ReflectionHelpers.getField(result, "text")
    }

    @Implements(value = GpuInfo.Companion::class, isInAndroidSdk = false)
    class DetectedGpu {
        @Implementation
        fun detect(): GpuInfo = GpuInfo(name, if (family == GpuInfo.Family.A6XX) 610 else 0, family, "", false)

        companion object {
            var family = GpuInfo.Family.NOT_ADRENO
            var name = "test GPU"
        }
    }
}
