package com.droiddeck.launcher.gpu

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun selectedGameIsTheTarget() {
        assertEquals(42L, FrameGen.targetGame(42L, chosenManually = true) { 7L })
        assertEquals(42L, FrameGen.targetGame(42L, chosenManually = false) { 7L })
    }

    @Test fun manuallyChosenDefaultSavesGlobally() {
        var asked = false
        assertNull(FrameGen.targetGame(null, chosenManually = true) { asked = true; 7L })
        assertFalse(asked)
    }

    @Test fun followingSteamWithoutProfileUsesRunningGame() {
        assertEquals(7L, FrameGen.targetGame(null, chosenManually = false) { 7L })
        assertNull(FrameGen.targetGame(null, chosenManually = false) { null })
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
            .putString("engine", FrameGen.ENGINE_DDFG).putInt("multiplier", 4).putInt("target", 60).commit()
        assertEquals(FrameGen.Mode(FrameGen.ENGINE_DDFG, 4), FrameGen.mode(context))
    }

    @Test fun aSavedWinFgChoiceBecomesDdFg() {
        context.getSharedPreferences("frame_gen", Context.MODE_PRIVATE).edit()
            .putString("engine", "winfg").putInt("multiplier", 2).commit()
        assertEquals(FrameGen.Mode(FrameGen.ENGINE_DDFG, 2), FrameGen.mode(context))
    }

    @Test fun gameOverridesStaySeparateFromEachOtherAndGeneral() {
        FrameGen.set(context, FrameGen.Mode(FrameGen.ENGINE_DDFG, 2))
        FrameGen.setForGame(context, "101", FrameGen.Mode(FrameGen.ENGINE_LSFG, 4))
        FrameGen.setForGame(context, "202", FrameGen.Mode.OFF)

        assertEquals(FrameGen.Mode(FrameGen.ENGINE_DDFG, 2), FrameGen.mode(context))
        assertEquals(FrameGen.Mode(FrameGen.ENGINE_LSFG, 4), FrameGen.mode(context, "101"))
        assertEquals(FrameGen.Mode.OFF, FrameGen.mode(context, "202"))
        assertEquals(FrameGen.Mode(FrameGen.ENGINE_DDFG, 2), FrameGen.mode(context, "303"))
    }
}
