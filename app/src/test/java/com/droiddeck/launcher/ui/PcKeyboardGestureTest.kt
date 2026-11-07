package com.droiddeck.launcher.ui

import android.app.Application
import androidx.compose.ui.test.onAllNodesWithText
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

    @Test fun letterKeySendsOnDownAndNeverWrapsShift() {
        setKeyboard()
        compose.onNodeWithText("a").performTouchInput { down(center) }
        compose.runOnIdle {
            assertTrue("letters stay immediate", (30 to true) in sent)
            assertFalse("no shift wrap for letters", (42 to true) in sent)
        }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertFalse("holding a letter must not add shift", (42 to true) in sent) }
        compose.onNodeWithText("a").performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(30 to true, 30 to false), sent) }
    }

    @Test fun delayedKeyTapSendsNothingUntilLift() {
        setKeyboard()
        compose.onNodeWithText("2").performTouchInput { down(center) }
        compose.runOnIdle { assertEquals("delayed key silent while held", emptyList<Pair<Int, Boolean>>(), sent) }
        compose.onNodeWithText("2").performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(3 to true, 3 to false), sent) }
    }

    @Test fun delayedKeyLongPressSendsShiftWrappedSequence() {
        setKeyboard()
        compose.onNodeWithText("2").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle {
            assertTrue((42 to true) in sent)
            assertTrue((3 to true) in sent)
            assertFalse("key stays down while held", (3 to false) in sent)
        }
        compose.onNodeWithText("2").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(listOf(42 to true, 3 to true, 3 to false, 42 to false), sent)
        }
    }

    @Test fun punctuationTypesDirectlyEvenWhileShiftIsActive() {
        setKeyboard()
        val shift = compose.onAllNodesWithText("⇧ Shift")[0]
        shift.performTouchInput { down(center) }
        shift.performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(42 to true), sent) }
        compose.onNodeWithText(",").performTouchInput { down(center) }
        compose.runOnIdle {
            assertEquals("wire shift lifts before the plain key", listOf(42 to true, 42 to false, 51 to true), sent)
        }
        compose.onNodeWithText(",").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(listOf(42 to true, 42 to false, 51 to true, 51 to false, 42 to false), sent)
        }
    }

    @Test fun onlyDigitKeysKeepCornerHints() {
        setKeyboard()
        compose.onNodeWithText("!").assertExists()
        compose.onNodeWithText("<").assertDoesNotExist()
    }

    @Test fun symbolPageNonSyntheticKeysIgnoreLockedShift() {
        setKeyboard()
        val shift = compose.onAllNodesWithText("⇧ Shift")[0]
        shift.performTouchInput { down(center); up() }
        shift.performTouchInput { down(center); up() } // ONCE -> LOCKED, wire stays down
        compose.runOnIdle { assertEquals(listOf(42 to true), sent) }
        compose.onNodeWithText("?123").performTouchInput { down(center); up() }
        compose.onNodeWithText(",").performTouchInput { down(center) }
        compose.runOnIdle {
            assertEquals("wire shift lifts before the plain key", listOf(42 to true, 42 to false, 51 to true), sent)
        }
        compose.onNodeWithText(",").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(
                "locked shift restores after the last suppressed key",
                listOf(42 to true, 42 to false, 51 to true, 51 to false, 42 to true),
                sent,
            )
        }
        compose.onNodeWithText("1").performTouchInput { down(center) }
        compose.runOnIdle {
            assertEquals(
                "digits type their printed symbol too",
                listOf(42 to true, 42 to false, 51 to true, 51 to false, 42 to true, 42 to false, 2 to true),
                sent,
            )
        }
        compose.onNodeWithText("1").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(
                listOf(
                    42 to true, 42 to false, 51 to true, 51 to false, 42 to true,
                    42 to false, 2 to true, 2 to false, 42 to true,
                ),
                sent,
            )
        }
    }
}
