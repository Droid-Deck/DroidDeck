package com.droiddeck.launcher.ui

import android.app.Application
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h640dp-land")
class PcKeyboardGestureTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val sent = mutableListOf<Pair<Int, Boolean>>()

    @Before fun enableLongPress() {
        context.getSharedPreferences("session", 0).edit().clear().commit()
        context.getSharedPreferences("session", 0).edit()
            .putBoolean("pcKeyboardLongPress", true)
            .putInt("pcKeyboardLongPressMs", 300)
            .commit()
    }

    private fun setKeyboard() {
        compose.setContent {
            DroidDeckTheme {
                PcKeyboard(sendKey = { c, d -> sent.add(c to d) }, onAndroidKeyboard = {}, onClose = {})
            }
        }
    }

    @Test fun immediateKeySendsOnDownAndUp() {
        setKeyboard()
        compose.onNodeWithText("F1").performTouchInput { down(center) }
        compose.runOnIdle {
            assertTrue("down should send immediately", (59 to true) in sent)
            assertFalse("no up before lift", (59 to false) in sent)
        }
        compose.onNodeWithText("F1").performTouchInput { up() }
        compose.runOnIdle { assertTrue((59 to false) in sent) }
    }

    @Test fun delayedKeyTapSendsNothingUntilLift() {
        setKeyboard()
        compose.onNodeWithText("a").performTouchInput { down(center); up() }
        compose.runOnIdle { assertEquals(listOf(30 to true, 30 to false), sent) }
    }

    @Test fun delayedKeyLongPressSendsShiftWrappedSequence() {
        setKeyboard()
        compose.onNodeWithText("a").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle {
            assertTrue((42 to true) in sent)
            assertTrue((30 to true) in sent)
            assertFalse("key stays down while held", (30 to false) in sent)
        }
        compose.onNodeWithText("a").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(listOf(42 to true, 30 to true, 30 to false, 42 to false), sent)
        }
    }
}
