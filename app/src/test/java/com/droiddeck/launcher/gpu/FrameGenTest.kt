package com.droiddeck.launcher.gpu

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class FrameGenTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("frame_gen", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun offUntilPicked() {
        assertEquals(FrameGen.Mode.OFF, FrameGen.mode(context))
    }

    @Test fun adaptiveTargetIsKept() {
        FrameGen.set(context, FrameGen.Mode(FrameGen.ENGINE_LSFG, target = 90))
        assertEquals(FrameGen.Mode(FrameGen.ENGINE_LSFG, target = 90), FrameGen.mode(context))
    }

    @Test fun fixedMultiplierReplacesATarget() {
        FrameGen.set(context, FrameGen.Mode(FrameGen.ENGINE_LSFG, target = 120))
        FrameGen.set(context, FrameGen.Mode(FrameGen.ENGINE_LSFG, 3))
        assertEquals(FrameGen.Mode(FrameGen.ENGINE_LSFG, 3), FrameGen.mode(context))
    }

    @Test fun onlyLsfgIsAdaptive() {
        context.getSharedPreferences("frame_gen", Context.MODE_PRIVATE).edit()
            .putString("engine", FrameGen.ENGINE_WINFG).putInt("multiplier", 4).putInt("target", 60).commit()
        assertEquals(FrameGen.Mode(FrameGen.ENGINE_WINFG, 4), FrameGen.mode(context))
    }
}
