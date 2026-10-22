package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.session.QuestExperimental
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TurnipDriverAutoTest {
    @Before fun reset() {
        ShadowBuild.setModel("Phone")
        ShadowBuild.setDevice("phone")
    }

    @Test fun questModelsUseTheirPatchedDriverAsTheFallback() {
        val driver = TurnipDriver(RuntimeEnvironment.getApplication())
        for (model in listOf("Quest 3", "Quest 3S", "Quest 2")) {
            ShadowBuild.setModel(model)
            assertEquals(if (model == "Quest 2") QuestExperimental.DRIVER_A6XX else QuestExperimental.DRIVER,
                driver.autoId())
        }
    }

    @Test fun otherDevicesRetainTheGenericFallback() {
        assertEquals("turnip25.1.0", TurnipDriver(RuntimeEnvironment.getApplication()).autoId())
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
