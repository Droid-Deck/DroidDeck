package com.droiddeck.launcher.glasses

import android.app.Application
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.compose.runtime.MutableState
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.gpu.Cardboard
import com.droiddeck.launcher.gpu.SteamVr
import com.droiddeck.launcher.runtime.FlatpakManager
import com.droiddeck.launcher.session.LoadingState
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class GlassesSessionModesTest {
    private data class Launch(val name: String, val mode: String, val program: String? = null, val args: Array<String>? = null)
    private val launches = listOf(
        Launch("Steam and store games", SessionService.MODE_STEAM),
        Launch("Desktop", SessionService.MODE_DESKTOP),
        Launch("Standalone", SessionService.MODE_RUN, "/usr/bin/example-game"),
        Launch("Emulator", SessionService.MODE_RUN, "/usr/bin/retroarch", arrayOf("/games/example.rom")),
        Launch("Flatpak", SessionService.MODE_RUN, FlatpakManager.LAUNCHER, arrayOf("org.example.Game")),
    )
    private lateinit var display: Display
    private lateinit var restore: () -> Unit
    private val saved = GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN,
        headTracking = true, screenWidthDm = 35, screenDistanceDm = 45)
    private val phone = Cardboard(enabled = true, source = Cardboard.SOURCE_SCREEN, ipdMm = 67,
        screenWidthDm = 20, screenDistanceDm = 30, headTracking = false)

    @Before fun setUp() {
        val running = SessionState.running
        val steamVr = SessionState.steamVr
        val mode = SessionState.mode
        val fields = listOf("context", "publisherAttached").associateWith {
            SpatialTracking::class.java.getDeclaredField(it).apply { isAccessible = true }
        }
        val values = fields.mapValues { it.value.get(null) }
        fields.getValue("context").set(null, null)
        fields.getValue("publisherAttached").set(null, false)
        restore = {
            fields.forEach { (name, field) -> field.set(null, values[name]) }
            SessionState.running = running
            SessionState.steamVr = steamVr
            SessionState.mode = mode
        }
        val id = ShadowDisplayManager.addDisplay("w1920dp-h1080dp")
        display = RuntimeEnvironment.getApplication().getSystemService(DisplayManager::class.java).getDisplay(id)
        SessionState.running = true
        SessionState.steamVr = false
    }

    @After fun tearDown() {
        SpatialTracking.useLegacyTracking()
        SpatialTracking.disconnect()
        ShadowDisplayManager.removeDisplay(display.displayId)
        restore()
    }

    @Test fun flatScreenSizeDistanceAndTrackingReachEveryGuestSessionWithoutSteamVr() {
        for (launch in launches) for (spatial in listOf(false, true)) {
            val activity = activity(launch, saved.copy(positionalTracking = spatial), output = true)
            SpatialTracking.connect(0, 101, null, spatial, 1.6f, false)
            val projection = projection(activity)

            assertTrue(launch.name, projection.enabled)
            assertTrue(launch.name, projection.headTracking)
            assertTrue(launch.name, projection.mono)
            assertEquals(launch.name, Cardboard.SOURCE_SCREEN, projection.source)
            assertEquals(launch.name, Cardboard.Viewer.VITURE.id, projection.viewer)
            assertEquals(launch.name, 35, projection.screenWidthDm)
            assertEquals(launch.name, 45, projection.screenDistanceDm)
            assertEquals(launch.name, phone.ipdMm, projection.ipdMm)
            assertEquals(launch.name, if (spatial) TrackingRecovery.LOST else null, recovery(activity))
        }
    }

    @Test fun positionalRecoverySurvivesUsbLossInEveryGuestSessionAndHonorsTrackingOff() {
        for (launch in launches) {
            val activity = activity(launch, saved.copy(positionalTracking = true), output = true)
            SpatialTracking.connect(0, 102, null, true, 1.6f, false)
            assertEquals(launch.name, TrackingRecovery.LOST, recovery(activity))

            SpatialTracking.disconnect()
            state(activity, "glasses", glasses().copy(connected = false, supportsSpatial = false))
            assertEquals(launch.name, TrackingRecovery.DISCONNECTED, recovery(activity))

            state(activity, "glassesSettings", saved.copy(positionalTracking = true, headTracking = false))
            assertFalse(launch.name, projection(activity).headTracking)
            assertNull(launch.name, recovery(activity))
        }
    }

    @Test fun savedGlassesTrackingDoesNotChangePhoneRenderingOrRecoveryInAnySessionMode() {
        for (launch in launches) {
            val activity = activity(launch, saved.copy(positionalTracking = true), output = false)
            SpatialTracking.connect(0, 103, null, true, 1.6f, false)

            assertSame(launch.name, phone, projection(activity))
            assertNull(launch.name, recovery(activity))
        }
    }

    private fun activity(launch: Launch, settings: GlassesSettings, output: Boolean): SessionActivity {
        val intent = Intent(RuntimeEnvironment.getApplication(), SessionActivity::class.java)
            .putExtra(SessionService.EXTRA_MODE, launch.mode)
            .putExtra(SessionService.EXTRA_PROGRAM, launch.program)
            .putExtra(SessionService.EXTRA_PROGRAM_ARGS, launch.args)
        SessionState.mode = launch.mode
        return Robolectric.buildActivity(SessionActivity::class.java, intent).get().also {
            ReflectionHelpers.setField(it, "loading", LoadingState(it).apply { visible = false })
            ReflectionHelpers.setField(it, "started", true)
            if (output) ReflectionHelpers.setField(it, "glassesScreen", GlassesScreen(it, display, 60, it))
            state(it, "glassesOutputActive", output)
            state(it, "glasses", glasses())
            state(it, "glassesSettings", settings)
            state(it, "steamVrStatus", SteamVr.Status.READY)
        }
    }

    private fun glasses() = GlassesState(present = true, connected = true, controllable = true,
        supportsSpatial = true, supportsScreen3dof = true, displayId = display.displayId, width = 1920, height = 1080)

    private fun projection(activity: SessionActivity): Cardboard = ReflectionHelpers.callInstanceMethod(
        activity, "effectiveCardboard", ClassParameter.from(Cardboard::class.java, phone))

    private fun recovery(activity: SessionActivity): TrackingRecovery? {
        ReflectionHelpers.callInstanceMethod<Void>(activity, "updateTrackingRecovery")
        return ReflectionHelpers.getField<MutableState<TrackingRecovery?>>(activity, "trackingRecovery\$delegate").value
    }

    private fun <T> state(activity: SessionActivity, name: String, value: T) {
        ReflectionHelpers.getField<MutableState<T>>(activity, name + "\$delegate").value = value
    }
}
