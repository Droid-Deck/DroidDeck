package com.droiddeck.launcher.gpu

import android.os.Environment
import android.os.Build
import com.droiddeck.launcher.session.QuestExperimental
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TurnipDriverAutoTest {
    @Before fun reset() {
        ShadowBuild.setModel("Phone")
        ShadowBuild.setDevice("phone")
        overrideFile().delete()
    }

    @After fun cleanUp() {
        overrideFile().delete()
        SessionPrefs.setAndroidDriver(RuntimeEnvironment.getApplication(), TurnipDriver.AUTO)
        File(RuntimeEnvironment.getApplication().filesDir, "graphics_driver/test-pinned").deleteRecursively()
    }

    private fun overrideFile() = File(Environment.getExternalStorageDirectory(), "Download/droiddeck-driver")

    private fun gpu(model: Int, adreno: Boolean = true) = GpuInfo(
        "test GPU", model, GpuInfo.familyOf(adreno, model), "", false,
    )

    @Test fun questModelsUseTheirPatchedDriverAsTheFallback() {
        val driver = TurnipDriver(RuntimeEnvironment.getApplication()) { error("Quest must not use generic GPU detection") }
        for (model in listOf("Quest 3", "Quest 3S", "Quest 2")) {
            ShadowBuild.setModel(model)
            assertEquals(if (model == "Quest 2") QuestExperimental.DRIVER_A6XX else QuestExperimental.DRIVER,
                driver.autoId())
        }
    }

    @Test fun aNonAdrenoDeviceDoesNotAutomaticallyLoadTurnip() {
        assertEquals(GpuInfo.Family.NOT_ADRENO, GpuInfo.detect().family)
        assertEquals("system", TurnipDriver(RuntimeEnvironment.getApplication()).autoId())
    }

    @Test fun autoSelectionUsesTheDetectedFamilyAndModel() {
        val context = RuntimeEnvironment.getApplication()
        for ((detected, expected) in listOf(
            gpu(0, adreno = false) to "system",
            gpu(0) to "turnip25.1.0",
            gpu(650) to "turnip26.3.0-a6xx",
            gpu(730) to "turnip25.1.0",
            gpu(850) to "turnip-sdk36",
        )) {
            var calls = 0
            assertEquals(expected, TurnipDriver(context) { calls++; detected }.autoId())
            assertEquals(1, calls)
        }
    }

    @Test fun nonAdrenoDoesNotUseTheAndroid16TurnipFallback() {
        val context = RuntimeEnvironment.getApplication()
        val sdk = Build.VERSION.SDK_INT
        try {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 36)
            assertEquals("system", TurnipDriver(context) { gpu(0, adreno = false) }.autoId())
            assertEquals("turnip-sdk36", TurnipDriver(context) { gpu(0) }.autoId())
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdk)
        }
    }

    @Test fun externalOverrideStillTakesPriorityOverDetection() {
        val driver = TurnipDriver(RuntimeEnvironment.getApplication()) { error("override must precede detection") }
        overrideFile().parentFile!!.mkdirs()
        overrideFile().writeText("system")
        assertEquals("system", driver.autoId())
        overrideFile().writeText("a8")
        assertEquals("turnip-sdk36", driver.autoId())
    }

    @Test fun aManuallySelectedImportedDriverStillTakesPriorityOverDetection() {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.filesDir, "graphics_driver/test-pinned").apply { mkdirs() }
        File(dir, "meta.json").writeText("""{"libraryName":"test.so"}""")
        File(dir, "test.so").writeText("")
        SessionPrefs.setAndroidDriver(context, "test-pinned")
        assertEquals("test-pinned", TurnipDriver(context) { error("manual choice must precede detection") }.install())
    }

    @Test fun normalizedGpuModelsSelectExistingGenerationDrivers() {
        for (sdk in listOf(28, 36)) {
            assertEquals("turnip26.3.0-a6xx", TurnipDriver.chooseByModel(650, sdk))
            assertEquals("turnip25.1.0", TurnipDriver.chooseByModel(730, sdk))
            assertEquals("turnip-sdk36", TurnipDriver.chooseByModel(850, sdk))
        }
    }

    @Test fun unknownGpuModelsRetainTheSdkFallback() {
        assertEquals("turnip25.1.0", TurnipDriver.chooseByModel(0, 28))
        assertEquals("turnip-sdk36", TurnipDriver.chooseByModel(0, 36))
    }
}
