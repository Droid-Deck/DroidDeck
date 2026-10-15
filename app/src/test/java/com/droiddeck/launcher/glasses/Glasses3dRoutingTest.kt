package com.droiddeck.launcher.glasses

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class Glasses3dRoutingTest {
    private val handler = Handler(Looper.getMainLooper())
    private val commands = mutableListOf<Pair<Int, Boolean>>()
    private val hardwareMessages = mutableListOf<Int>()
    private lateinit var stateBefore: GlassesState
    private lateinit var settingsBefore: GlassesSettings
    private val supportedModels: List<GlassesModel> =
        listOf("One", "Lite", "Pro", "Pro 2", "Luma", "Luma Pro", "Luma Cyber", "Luma Ultra", "Beast")
            .map { VitureProtocol.sdkModel(it)!! } + listOf(
                RayNeoModel.model(0x1bbb, 0xaf50, "RayNeo Air 4 Pro")!!,
                RayNeoModel.model(0x3941, 0xaf50, "RayNeo GT")!!,
                RayNeoModel.model(0x3941, 0xaf50, "RayNeo GT MAX")!!,
                XrealModel,
            )
    private fun field(name: String) = Glasses::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Before fun prepare() {
        stateBefore = Glasses.state.value
        settingsBefore = Glasses.settings.value
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("glasses", Context.MODE_PRIVATE).edit().clear().commit()
        field("app").set(null, context)
        field("handler").set(null, handler)
        field("model").set(null, VitureProtocol.model(0x1019, null))
        @Suppress("UNCHECKED_CAST")
        val state = field("_state").get(null) as MutableStateFlow<GlassesState>
        state.value = GlassesState(connected = true, controllable = true,
            displayId = 2, width = 1920, height = 1080)
        @Suppress("UNCHECKED_CAST")
        val settings = field("_settings").get(null) as MutableStateFlow<GlassesSettings>
        settings.value = GlassesSettings(gameOnGlasses = false)
        field("link").set(null, object : GlassesLink {
            override val hasImu = false
            override fun send(msgId: Int, payload: ByteArray): Boolean {
                hardwareMessages += msgId
                if (msgId == VitureProtocol.MSG_DISPLAY)
                    commands += (payload[0].toInt() and 255) to Glasses.settings.value.gameOnGlasses
                return true
            }
            override fun close() {}
        })
    }

    @After fun cleanUp() {
        handler.removeCallbacksAndMessages(null)
        field("link").set(null, null)
        (field("modeState").get(null) as GlassesModeState).apply { manual(); sent(false, 0) }
        @Suppress("UNCHECKED_CAST")
        val state = field("_state").get(null) as MutableStateFlow<GlassesState>
        state.value = stateBefore
        @Suppress("UNCHECKED_CAST")
        val settings = field("_settings").get(null) as MutableStateFlow<GlassesSettings>
        settings.value = settingsBefore
    }

    @Test fun manual3dSelectsAndSavesGlassesOutputBeforeSwitchingTheDisplay() {
        Glasses.set3d(true)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(0x32 to true), commands)
        assertTrue(Glasses.settings.value.gameOnGlasses)
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("glasses", Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean("gameOnGlasses", false))
    }

    @Test fun nativeThirtyHzIsSavedWithoutSendingAFabricatedHardwareMode() {
        @Suppress("UNCHECKED_CAST")
        val state = field("_state").get(null) as MutableStateFlow<GlassesState>
        Glasses.setGameOnGlasses(true)
        shadowOf(Looper.getMainLooper()).idle()
        state.value = state.value.copy(supports30Hz = true)

        Glasses.setRefreshHz(30)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(30, Glasses.settings.value.refreshHz)
        assertTrue(hardwareMessages.isEmpty())

        state.value = state.value.copy(sbs = true, width = 3840)
        Glasses.setSbsHz(30)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(30, Glasses.settings.value.sbsHz)
        assertTrue(hardwareMessages.isEmpty())
        val saved = Glasses::class.java.getDeclaredMethod("load").apply { isAccessible = true }
            .invoke(Glasses) as GlassesSettings
        assertEquals(30, saved.refreshHz)
        assertEquals(30, saved.sbsHz)
    }

    @Test fun unsupportedThirtyHzRequestsLeavePreferencesAndHardwareUnchanged() {
        val before = Glasses.settings.value
        Glasses.setRefreshHz(30)
        Glasses.setSbsHz(30)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, Glasses.settings.value)
        assertTrue(hardwareMessages.isEmpty())
    }

    @Test fun automaticSteamVr3dAlsoSelectsGlassesOutput() {
        Glasses.set3dForVr(true)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(0x32 to true), commands)
        assertTrue(Glasses.settings.value.gameOnGlasses)
    }

    @Test fun returningTo2dKeepsTheGameOnGlasses() {
        Glasses.set3d(true)
        shadowOf(Looper.getMainLooper()).idle()
        Glasses.set3d(false)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(0x32 to true, 0x34 to true), commands)
        assertTrue(Glasses.settings.value.gameOnGlasses)
    }

    @Test fun turningOff3dDoesNotEnableGlassesOutput() {
        Glasses.set3d(false)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(0x34 to false), commands)
        assertFalse(Glasses.settings.value.gameOnGlasses)
    }

    @Test fun turningOffGlassesOutputRestores2dOnEverySupportedModel() {
        for (model in supportedModels) {
            selectModel(model)
            Glasses.set3dof(true)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(Glasses.settings.value.screen3dof)
            @Suppress("UNCHECKED_CAST")
            val state = field("_state").get(null) as MutableStateFlow<GlassesState>
            state.value = state.value.copy(sbs = true, width = 3840)
            commands.clear()

            Glasses.setGameOnGlasses(false)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(listOf(model.mode(120, false) to true), commands)
            assertFalse(Glasses.settings.value.gameOnGlasses)
            assertFalse(Glasses.settings.value.screen3dof)
            assertEquals(GlassesSettings.PICTURE_AUTO, Glasses.settings.value.picture3d)
            val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("glasses", Context.MODE_PRIVATE)
            assertFalse(prefs.getBoolean("gameOnGlasses", true))
        }
    }

    @Test fun turningOffGlassesOutputCancelsAnUnconfirmedStereoSwitch() {
        Glasses.set3d(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(Glasses.state.value.sbs)
        assertTrue(Glasses.state.value.switching3d)

        Glasses.setGameOnGlasses(false)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(0x32 to true, 0x34 to true), commands)
        assertFalse(Glasses.state.value.switching3d)
        assertFalse(Glasses.settings.value.gameOnGlasses)
    }

    @Test fun turningOffGlassesOutputTakesOwnershipFromAutomaticVr() {
        Glasses.set3dForVr(true)
        shadowOf(Looper.getMainLooper()).idle()
        Glasses.setGameOnGlasses(false)
        shadowOf(Looper.getMainLooper()).idle()
        Glasses.set3dForVr(false)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(0x32 to true, 0x34 to true), commands)
        assertFalse(Glasses.state.value.switching3d)
        assertFalse(Glasses.settings.value.gameOnGlasses)
    }

    @Test fun togglingGlassesOutputIn2dDoesNotSendDisplayCommands() {
        Glasses.setGameOnGlasses(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(Glasses.settings.value.gameOnGlasses)
        Glasses.setGameOnGlasses(false)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(Glasses.settings.value.gameOnGlasses)
        assertTrue(hardwareMessages.isEmpty())
    }

    @Test fun turningOffGlassesOutputDisablesVirtualScreenAndSavesTheChoice() {
        selectModel(supportedModels.first())
        Glasses.set3dof(true)
        shadowOf(Looper.getMainLooper()).idle()
        Glasses.setGameOnGlasses(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(Glasses.settings.value.screen3dof)

        Glasses.setGameOnGlasses(false)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(Glasses.settings.value.screen3dof)
        assertEquals(GlassesSettings.PICTURE_AUTO, Glasses.settings.value.picture3d)
        val saved = Glasses::class.java.getDeclaredMethod("load").apply { isAccessible = true }
            .invoke(Glasses) as GlassesSettings
        assertFalse(saved.gameOnGlasses)
        assertFalse(saved.screen3dof)
        assertEquals(GlassesSettings.PICTURE_AUTO, saved.picture3d)

        Glasses.setGameOnGlasses(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(Glasses.settings.value.screen3dof)
        assertTrue(hardwareMessages.isEmpty())
    }

    @Test fun turningOffGlassesOutputDisablesVirtualScreenWithoutDeviceControl() {
        selectModel(supportedModels.first())
        Glasses.set3dof(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(Glasses.settings.value.screen3dof)
        @Suppress("UNCHECKED_CAST")
        val state = field("_state").get(null) as MutableStateFlow<GlassesState>
        state.value = state.value.copy(connected = false, supportsScreen3dof = false)

        Glasses.setGameOnGlasses(false)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(Glasses.settings.value.gameOnGlasses)
        assertFalse(Glasses.settings.value.screen3dof)
        assertEquals(GlassesSettings.PICTURE_AUTO, Glasses.settings.value.picture3d)
        assertTrue(hardwareMessages.isEmpty())
    }

    @Test fun enabling3dofRoutesFlatContentWithoutSendingAStereoCommandAndSurvivesReload() {
        for (model in supportedModels) {
            selectModel(model)
            Glasses.set3dof(true)
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(hardwareMessages.isEmpty())
            assertFalse(Glasses.state.value.sbs)
            assertFalse(Glasses.state.value.switching3d)
            assertTrue(Glasses.settings.value.screen3dof)
            assertTrue(Glasses.settings.value.gameOnGlasses)
            val saved = Glasses::class.java.getDeclaredMethod("load").apply { isAccessible = true }
                .invoke(Glasses) as GlassesSettings
            assertTrue(saved.screen3dof)
            assertTrue(saved.gameOnGlasses)
        }
    }

    @Test fun stereoAnd3dofCanBeToggledIndependentlyOnEverySupportedModel() {
        for (model in supportedModels) {
            selectModel(model)
            Glasses.set3dof(true)
            Glasses.set3d(true)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(Glasses.settings.value.screen3dof)

            Glasses.set3d(false)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(Glasses.settings.value.screen3dof)
            assertEquals(listOf(model.mode(60, true) to true, model.mode(120, false) to true), commands)

            commands.clear()
            hardwareMessages.clear()
            Glasses.set3dof(false)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(hardwareMessages.isEmpty())
            assertFalse(Glasses.settings.value.screen3dof)
            assertEquals(GlassesSettings.PICTURE_AUTO, Glasses.settings.value.picture3d)
            assertTrue(Glasses.settings.value.gameOnGlasses)
        }
    }

    @Test fun enabling3dofWithoutHeadTrackingSupportLeavesSettingsUnchanged() {
        val before = Glasses.settings.value
        Glasses.set3dof(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, Glasses.settings.value)
        assertTrue(hardwareMessages.isEmpty())
    }

    private fun selectModel(model: GlassesModel) {
        field("model").set(null, model)
        @Suppress("UNCHECKED_CAST")
        val state = field("_state").get(null) as MutableStateFlow<GlassesState>
        state.value = state.value.copy(model = model.name, supportsScreen3dof = true)
        @Suppress("UNCHECKED_CAST")
        val settings = field("_settings").get(null) as MutableStateFlow<GlassesSettings>
        settings.value = GlassesSettings(gameOnGlasses = false, headTracking = false)
        assertFalse(settings.value.screen3dof)
    }
}
