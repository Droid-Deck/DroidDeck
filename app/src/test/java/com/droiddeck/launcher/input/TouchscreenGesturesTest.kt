package com.droiddeck.launcher.input

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TouchscreenGesturesTest {
    private val events = mutableListOf<String>()
    private data class ButtonEvent(val time: Long, val down: Boolean, val position: Pair<Float, Float>)
    private val leftClicks = mutableListOf<ButtonEvent>()
    private val rightClicks = mutableListOf<ButtonEvent>()
    private val timedKeys = mutableListOf<Triple<Long, Int, Boolean>>()
    private val absoluteMoves = mutableListOf<Pair<Float, Float>>()
    private val relativeMoves = mutableListOf<Pair<Float, Float>>()
    private val recognized = mutableListOf<TouchscreenGestures.Gesture>()
    private var cursor = 0f to 0f
    private lateinit var gestures: TouchscreenGestures
    private var downTime = 0L
    private val a = 7 to (400f to 300f)
    private val b = 12 to (600f to 300f)
    private val c = 19 to (800f to 300f)
    private val d = 24 to (1000f to 300f)

    @Before fun setUp() {
        advance(1000)
        gestures = TouchscreenGestures(ViewConfiguration.get(RuntimeEnvironment.getApplication()).scaledTouchSlop.toFloat(), object : TouchscreenGestures.Output {
            override fun moveTo(x: Float, y: Float) {
                cursor = x to y; absoluteMoves += cursor; events += "move"
            }
            override fun moveBy(dx: Float, dy: Float, relative: Boolean) {
                if (relative) relativeMoves += dx to dy
                events += "delta $relative"
            }
            override fun button(code: Int, down: Boolean) {
                events += "button $code $down"
                if (code == 272) leftClicks += ButtonEvent(SystemClock.uptimeMillis(), down, cursor)
                if (code == 273) rightClicks += ButtonEvent(SystemClock.uptimeMillis(), down, cursor)
            }
            override fun key(code: Int, down: Boolean) {
                events += "key $code $down"
                timedKeys += Triple(SystemClock.uptimeMillis(), code, down)
            }
            override fun wheel(steps: Int) { events += "wheel $steps" }
            override fun showKeyboard() { events += "keyboard" }
            override fun prepareRelativePan() { events += "prepare pan" }
            override fun gesture(gesture: TouchscreenGestures.Gesture) { recognized += gesture }
        })
    }

    @Test fun tapHasOnePressAndADelayedRelease() {
        tap()
        assertEquals(listOf("button 272 true"), buttons())
        advance(60)
        assertEquals(listOf("button 272 true", "button 272 false"), buttons())
    }

    @Test fun recognizedFeedbackIsTypedAndOnlyEmittedWhenEnabled() {
        tap(); advance(150)
        assertTrue(recognized.isEmpty())
        gestures.configure(TouchGestureConfig(showGestureDebugOverlay = true))
        tap()
        assertEquals(listOf(TouchscreenGestures.Gesture.TAP), recognized)
    }

    @Test fun doubleTapProducesTwoClicksRatherThanThree() {
        tap(); advance(80); tap(); advance(80)
        assertEquals(listOf("button 272 true", "button 272 false", "button 272 true", "button 272 false"), buttons())
    }

    @Test fun aVeryFastDoubleTapKeepsFullPressesAndAReleaseGap() {
        tap(); tap(); advance(250)
        assertEquals(4, leftClicks.size)
        assertTrue(leftClicks[1].time - leftClicks[0].time >= TouchTiming.CLICK_MS)
        assertTrue(leftClicks[2].time - leftClicks[1].time >= TouchTiming.CLICK_MS)
        assertTrue(leftClicks[3].time - leftClicks[2].time >= TouchTiming.CLICK_MS)
    }

    @Test fun doubleTapUsesTheFirstClickLocationDespiteNormalFingerDrift() {
        tap(); advance(80)
        touch(MotionEvent.ACTION_DOWN, 0, 7 to (404f to 303f))
        touch(MotionEvent.ACTION_MOVE, 0, 7 to (406f to 304f))
        advance(30)
        touch(MotionEvent.ACTION_UP, 0, 7 to (406f to 304f))
        advance(200)
        assertEquals(4, leftClicks.size)
        assertTrue(leftClicks.all { it.position == a.second })
    }

    @Test fun quickTapsOnVerticallyAdjacentIconsClickEachIconOnce() {
        val below = 7 to (400f to 345f)
        tap(); tap(below); advance(250)
        assertEquals(listOf(a.second, below.second), leftClicks.filter { it.down }.map { it.position })
    }

    @Test fun quickTapsOnHorizontallyAdjacentIconsClickEachIconOnce() {
        val beside = 7 to (445f to 300f)
        tap(); tap(beside); advance(250)
        assertEquals(listOf(a.second, beside.second), leftClicks.filter { it.down }.map { it.position })
    }

    @Test fun fastClicksAcrossThreeIconsDoNotSnapBackToEarlierIcons() {
        val below = 7 to (400f to 345f)
        val bottom = 7 to (400f to 390f)
        tap(); tap(below); tap(bottom); advance(350)
        assertEquals(listOf(a.second, below.second, bottom.second), leftClicks.filter { it.down }.map { it.position })
    }

    @Test fun aDoubleTapWithoutASingleTapHasAReleaseGapBetweenSynthesizedClicks() {
        gestures.configure(TouchGestureConfig(tapEnabled = false))
        tap(); tap(); advance(250)
        assertEquals(4, leftClicks.size)
        assertTrue(leftClicks[2].time - leftClicks[1].time >= TouchTiming.CLICK_MS)
    }

    @Test fun aRapidThirdTapDoesNotDiscardThePendingSecondClick() {
        tap(); tap(); tap(); advance(350)
        assertEquals(6, leftClicks.size)
        assertEquals(listOf(true, false, true, false, true, false), leftClicks.map { it.down })
    }

    @Test fun cancellingBetweenDoubleClicksStopsTheQueuedClick() {
        tap(); tap()
        gestures.cancel()
        events.clear(); advance(2000)
        assertTrue(events.isEmpty())
    }

    @Test fun aTouchCancelDoesNotPrimeTheFollowingTapAsADoubleTap() {
        tap(); advance(80)
        touch(MotionEvent.ACTION_CANCEL, 0, a)
        events.clear()
        touch(MotionEvent.ACTION_DOWN, 0, a)
        advance(100)
        assertTrue(buttons().isEmpty())
    }

    @Test fun firstTapNearOriginIsNotMistakenForADoubleTap() {
        touch(MotionEvent.ACTION_DOWN, 0, 1 to (1f to 1f))
        assertTrue(buttons().isEmpty())
    }

    @Test fun doubleClickIsNotCommittedUntilTheSecondFingerLifts() {
        tap(); advance(80)
        touch(MotionEvent.ACTION_DOWN, 0, a)
        advance(30)
        assertEquals(2, leftClicks.size)
        touch(MotionEvent.ACTION_UP, 0, a)
        advance(60)
        assertEquals(listOf(true, false, true, false), leftClicks.map { it.down })
    }

    @Test fun aTapFollowedByADragAtTheSamePlaceDoesNotSwallowTheDrag() {
        tap(); advance(80)
        touch(MotionEvent.ACTION_DOWN, 0, a)
        assertEquals(2, leftClicks.size)
        touch(MotionEvent.ACTION_MOVE, 0, 7 to (480f to 300f))
        assertEquals(3, leftClicks.size)
        touch(MotionEvent.ACTION_MOVE, 0, 7 to (520f to 300f))
        assertEquals(520f to 300f, cursor)
        touch(MotionEvent.ACTION_UP, 0, 7 to (520f to 300f))
        advance(500)
        assertEquals(listOf(true, false, true, false), leftClicks.map { it.down })
    }

    @Test fun aTapFollowedByARelativeDragStartsAtTheNewTouchOrigin() {
        gestures.configure(TouchGestureConfig(mouseDragMovementMode = "relative"))
        tap(); advance(80)
        val second = 7 to (404f to 303f)
        touch(MotionEvent.ACTION_DOWN, 0, second)
        touch(MotionEvent.ACTION_MOVE, 0, 7 to (484f to 303f))
        assertEquals(second.second, leftClicks.last().position)
        touch(MotionEvent.ACTION_MOVE, 0, 7 to (494f to 303f))
        assertEquals(10f to 0f, relativeMoves.last())
        gestures.cancel()
        assertEquals(4, leftClicks.size)
    }

    @Test fun aTapFollowedByALongPressUsesItsHoldBindingInsteadOfDoubleClicking() {
        gestures.configure(TouchGestureConfig(longPressEnabled = true, longPressAction = "right_click"))
        tap(); advance(80)
        val second = 7 to (404f to 303f)
        touch(MotionEvent.ACTION_DOWN, 0, second)
        advance(350)
        assertEquals(2, leftClicks.size)
        assertEquals(listOf("button 273 true"), buttons().filter { it.contains("273") })
        assertEquals(second.second, rightClicks.single().position)
        touch(MotionEvent.ACTION_UP, 0, second)
        advance(500)
        assertEquals(listOf(true, false), rightClicks.map { it.down })
        assertEquals(2, leftClicks.size)
    }

    @Test fun aLongSecondTouchIsNotASynthesizedDoubleTapWhenHoldsAreDisabled() {
        gestures.configure(TouchGestureConfig(tapEnabled = false, longPressEnabled = false))
        tap(); advance(80)
        touch(MotionEvent.ACTION_DOWN, 0, a)
        advance(ViewConfiguration.getLongPressTimeout().toLong() + 20)
        touch(MotionEvent.ACTION_UP, 0, a)
        advance(500)
        assertTrue(buttons().isEmpty())
    }

    @Test fun addingAnotherFingerToAPotentialDoubleTapDoesNotClickLeftAgain() {
        tap(); advance(80)
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        advance(30)
        touch(MotionEvent.ACTION_POINTER_UP, 1, a, b)
        touch(MotionEvent.ACTION_UP, 0, a)
        advance(80)
        assertEquals(2, leftClicks.size)
        assertEquals(listOf(true, false), rightClicks.map { it.down })
    }

    @Test fun movingTheSecondTouchToANeighboringIconKeepsThatTarget() {
        tap(); advance(80)
        touch(MotionEvent.ACTION_DOWN, 0, a)
        val beside = 7 to (445f to 300f)
        touch(MotionEvent.ACTION_MOVE, 0, beside)
        touch(MotionEvent.ACTION_UP, 0, beside)
        advance(80)
        assertEquals(listOf(a.second, beside.second), leftClicks.filter { it.down }.map { it.position })
    }

    @Test fun endingTheSecondTouchOnAnotherIconWithoutAMoveEventKeepsItsTarget() {
        tap(); advance(80)
        touch(MotionEvent.ACTION_DOWN, 0, a)
        val beside = 7 to (445f to 300f)
        touch(MotionEvent.ACTION_UP, 0, beside)
        advance(80)
        assertEquals(listOf(a.second, beside.second), leftClicks.filter { it.down }.map { it.position })
    }

    @Test fun cancellingAPotentialDoubleTapCannotLeaveAQueuedClick() {
        tap(); advance(80)
        touch(MotionEvent.ACTION_DOWN, 0, a)
        gestures.cancel(); advance(1000)
        assertEquals(listOf(true, false), leftClicks.map { it.down })
    }

    @Test fun doubleTapStillDoubleClicksWhenSingleTapIsDisabled() {
        gestures.configure(TouchGestureConfig(tapEnabled = false))
        tap(); advance(80); tap(); advance(150)
        assertEquals(listOf("button 272 true", "button 272 false", "button 272 true", "button 272 false"), buttons())
    }

    @Test fun doubleTapStillDoubleClicksWhenSingleTapIsAKeyboardAction() {
        gestures.configure(TouchGestureConfig(tapAction = "key_E"))
        tap(); advance(80); tap(); advance(150)
        assertEquals(listOf("button 272 true", "button 272 false", "button 272 true", "button 272 false"), buttons())
        assertEquals(listOf("key 18 true", "key 18 false"), events.filter { it.startsWith("key") })
    }

    @Test fun shortDragPressesBeforeLiftAndDoesNotPrimeADoubleTap() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_MOVE, 0, 7 to (480f to 300f))
        assertEquals(listOf("button 272 true"), buttons())
        touch(MotionEvent.ACTION_UP, 0, 7 to (480f to 300f))
        touch(MotionEvent.ACTION_DOWN, 0, 7 to (480f to 300f))
        assertEquals(listOf("button 272 true", "button 272 false"), buttons())
    }

    @Test fun twoFingerTapIsRightClickWithoutALeftClickOnFinalLift() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        advance(80)
        touch(MotionEvent.ACTION_POINTER_UP, 1, a, b)
        touch(MotionEvent.ACTION_UP, 0, a)
        advance(80)
        assertEquals(listOf("button 273 true", "button 273 false"), buttons())
    }

    @Test fun twoFingerTapKeepsItsPressAfterTheLastFingerLifts() {
        twoFingerTap()
        assertEquals(listOf("button 273 true"), buttons())
        advance(TouchTiming.CLICK_MS - 1)
        assertEquals(1, rightClicks.size)
        advance(1)
        assertEquals(listOf(true, false), rightClicks.map { it.down })
        assertEquals(TouchTiming.CLICK_MS, rightClicks[1].time - rightClicks[0].time)
    }

    @Test fun threeFingerKeyboardTapHasAnObservablePressAndReleases() {
        gestures.configure(TouchGestureConfig(threeFingerTapAction = "key_E"))
        threeFingerTap()
        assertEquals(listOf("key 18 true"), events.filter { it.startsWith("key") })
        advance(50)
        assertEquals(listOf("key 18 true", "key 18 false"), events.filter { it.startsWith("key") })
        assertEquals(TouchTiming.CLICK_MS, timedKeys.last().first - timedKeys.first().first)
    }

    @Test fun twoFingerKeyboardTapHasAnObservablePressAndReleases() {
        gestures.configure(TouchGestureConfig(twoFingerTapAction = "key_E"))
        twoFingerTap(); advance(50)
        assertEquals(listOf(true, false), timedKeys.map { it.third })
        assertEquals(TouchTiming.CLICK_MS, timedKeys.last().first - timedKeys.first().first)
    }

    @Test fun threeFingerMouseTapHasAnObservablePressAndReleases() {
        gestures.configure(TouchGestureConfig(threeFingerTapAction = "right_click"))
        threeFingerTap()
        assertEquals(listOf("button 273 true"), buttons())
        advance(50)
        assertEquals(listOf(true, false), rightClicks.map { it.down })
        assertEquals(TouchTiming.CLICK_MS, rightClicks[1].time - rightClicks[0].time)
    }

    @Test fun cancellingAMultiFingerTapReleasesItsButtonAndStopsCallbacks() {
        twoFingerTap(); advance(10)
        gestures.cancel()
        assertEquals(listOf("button 273 true", "button 273 false"), buttons())
        events.clear(); advance(2000)
        assertTrue(events.isEmpty())
    }

    @Test fun changingConfigDuringAThreeFingerKeyTapReleasesTheKey() {
        gestures.configure(TouchGestureConfig(threeFingerTapAction = "key_E"))
        threeFingerTap(); advance(10)
        gestures.configure(TouchGestureConfig())
        assertEquals(listOf("key 18 true", "key 18 false"), events.filter { it.startsWith("key") })
        events.clear(); advance(2000)
        assertTrue(events.isEmpty())
    }

    @Test fun rapidRightClicksKeepAReleaseGapBetweenThem() {
        gestures.configure(TouchGestureConfig(tapAction = "right_click", doubleTapEnabled = false))
        tap(); tap(); advance(250)
        assertEquals(listOf(true, false, true, false), rightClicks.map { it.down })
        assertTrue(rightClicks[2].time - rightClicks[1].time >= TouchTiming.CLICK_MS)
    }

    @Test fun aFastTwoFingerSwipeDuringSettlingDoesNotClick() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        advance(30)
        val movedA = 7 to (480f to 300f)
        val movedB = 12 to (680f to 300f)
        touch(MotionEvent.ACTION_MOVE, 0, movedA, movedB)
        touch(MotionEvent.ACTION_POINTER_UP, 1, movedA, movedB)
        touch(MotionEvent.ACTION_UP, 0, movedA)
        advance(1000)
        assertTrue(buttons().isEmpty())
        assertFalse(events.any { it.startsWith("key") || it.startsWith("wheel") })
    }

    @Test fun aFastPinchDuringSettlingDoesNotClick() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        advance(30)
        val movedA = 7 to (370f to 300f)
        val movedB = 12 to (630f to 300f)
        touch(MotionEvent.ACTION_MOVE, 0, movedA, movedB)
        touch(MotionEvent.ACTION_POINTER_UP, 1, movedA, movedB)
        touch(MotionEvent.ACTION_UP, 0, movedA)
        advance(1000)
        assertTrue(buttons().isEmpty())
    }

    @Test fun earlySwipeMovementCancelsTheHoldEvenIfFingersThenStop() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        advance(30)
        touch(MotionEvent.ACTION_MOVE, 0, 7 to (480f to 300f), 12 to (680f to 300f))
        advance(600)
        assertTrue(buttons().isEmpty())
        gestures.cancel()
    }

    @Test fun movementOnlyReportedOnLiftCannotBecomeATwoFingerTap() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        advance(30)
        touch(MotionEvent.ACTION_POINTER_UP, 1, 7 to (480f to 300f), 12 to (680f to 300f))
        touch(MotionEvent.ACTION_UP, 0, 7 to (480f to 300f))
        advance(600)
        assertTrue(buttons().isEmpty())
    }

    @Test fun smallTwoFingerPlacementJitterStillAllowsATap() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        advance(20)
        val movedA = 7 to (404f to 303f)
        val movedB = 12 to (596f to 297f)
        touch(MotionEvent.ACTION_MOVE, 0, movedA, movedB)
        touch(MotionEvent.ACTION_POINTER_UP, 1, movedA, movedB)
        touch(MotionEvent.ACTION_UP, 0, movedA)
        advance(80)
        assertEquals(listOf("button 273 true", "button 273 false"), buttons())
    }

    @Test fun smallTwoFingerPlacementJitterStillAllowsAHold() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        advance(20)
        val movedA = 7 to (404f to 303f)
        val movedB = 12 to (596f to 297f)
        touch(MotionEvent.ACTION_MOVE, 0, movedA, movedB)
        advance(350)
        assertEquals(listOf("button 274 true"), buttons())
        touch(MotionEvent.ACTION_POINTER_UP, 1, movedA, movedB)
        touch(MotionEvent.ACTION_UP, 0, movedA)
        assertEquals(listOf("button 274 true", "button 274 false"), buttons())
    }

    @Test fun smallThreeFingerPlacementJitterStillAllowsAHold() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        touch(MotionEvent.ACTION_POINTER_DOWN, 2, a, b, c)
        advance(20)
        val movedA = 7 to (404f to 303f)
        val movedB = 12 to (604f to 303f)
        val movedC = 19 to (804f to 303f)
        touch(MotionEvent.ACTION_MOVE, 0, movedA, movedB, movedC)
        advance(350)
        assertEquals(listOf("key 1 true"), events.filter { it.startsWith("key") })
        touch(MotionEvent.ACTION_POINTER_UP, 2, movedA, movedB, movedC)
        touch(MotionEvent.ACTION_POINTER_UP, 1, movedA, movedB)
        touch(MotionEvent.ACTION_UP, 0, movedA)
        assertEquals(listOf("key 1 true", "key 1 false"), events.filter { it.startsWith("key") })
        assertFalse(events.contains("keyboard"))
    }

    @Test fun aFastThreeFingerSwipeDuringSettlingDoesNotTapOrHold() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        touch(MotionEvent.ACTION_POINTER_DOWN, 2, a, b, c)
        advance(20)
        val movedA = 7 to (460f to 300f)
        val movedB = 12 to (660f to 300f)
        val movedC = 19 to (860f to 300f)
        touch(MotionEvent.ACTION_MOVE, 0, movedA, movedB, movedC)
        touch(MotionEvent.ACTION_POINTER_UP, 2, movedA, movedB, movedC)
        touch(MotionEvent.ACTION_POINTER_UP, 1, movedA, movedB)
        touch(MotionEvent.ACTION_UP, 0, movedA)
        advance(1000)
        assertFalse(events.any { it == "keyboard" || it.startsWith("key") || it.startsWith("button") })
    }

    @Test fun addingAThirdFingerResetsTheMovementOriginsForItsTap() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        advance(20)
        val movedA = 7 to (435f to 300f)
        val movedB = 12 to (635f to 300f)
        touch(MotionEvent.ACTION_MOVE, 0, movedA, movedB)
        touch(MotionEvent.ACTION_POINTER_DOWN, 2, movedA, movedB, c)
        advance(20)
        touch(MotionEvent.ACTION_MOVE, 0, 7 to (440f to 300f), 12 to (640f to 300f), 19 to (805f to 300f))
        touch(MotionEvent.ACTION_POINTER_UP, 2, 7 to (440f to 300f), 12 to (640f to 300f), 19 to (805f to 300f))
        touch(MotionEvent.ACTION_POINTER_UP, 1, 7 to (440f to 300f), 12 to (640f to 300f))
        touch(MotionEvent.ACTION_UP, 0, 7 to (440f to 300f))
        advance(80)
        assertEquals(1, events.count { it == "keyboard" })
        assertTrue(buttons().isEmpty())
    }

    @Test fun replacingAFingerAfterAPanCannotRestartATapBeforeAllFingersLift() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        advance(150)
        val movedA = 7 to (480f to 300f)
        val movedB = 12 to (680f to 300f)
        touch(MotionEvent.ACTION_MOVE, 0, movedA, movedB)
        touch(MotionEvent.ACTION_POINTER_UP, 1, movedA, movedB)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, movedA, b)
        advance(30)
        touch(MotionEvent.ACTION_POINTER_UP, 1, movedA, b)
        touch(MotionEvent.ACTION_UP, 0, movedA)
        advance(600)
        assertTrue(buttons().isEmpty())
        assertEquals(listOf("key 106 true", "key 106 false"), events.filter { it.startsWith("key") })
    }

    @Test fun cancellingAnActiveLongPressReleasesItsButton() {
        gestures.configure(TouchGestureConfig(longPressEnabled = true, longPressAction = "right_click"))
        touch(MotionEvent.ACTION_DOWN, 0, a)
        advance(350)
        assertEquals(listOf("button 273 true"), buttons())
        gestures.cancel(); advance(2000)
        assertEquals(listOf("button 273 true", "button 273 false"), buttons())
    }

    @Test fun changingConfigurationReleasesAnActiveHold() {
        gestures.configure(TouchGestureConfig(longPressEnabled = true, longPressAction = "left_click"))
        touch(MotionEvent.ACTION_DOWN, 0, a)
        advance(350)
        gestures.configure(TouchGestureConfig())
        assertEquals(listOf("button 272 true", "button 272 false"), buttons())
    }

    @Test fun relativeDragEmitsDeltasInsteadOfAbsoluteMotion() {
        gestures.configure(TouchGestureConfig(mouseDragMovementMode = "relative"))
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_MOVE, 0, 7 to (480f to 300f))
        events.clear()
        touch(MotionEvent.ACTION_MOVE, 0, 7 to (490f to 300f))
        assertEquals(listOf("delta true"), events)
    }

    @Test fun directSelectionBoxTracksEveryMoveBackThroughItsStartingPoint() {
        checkDirectDragThroughOrigin("left_click_drag", 272)
    }

    @Test fun directRightDragAlsoTracksEveryMoveBackThroughItsStartingPoint() {
        checkDirectDragThroughOrigin("right_click_drag", 273)
    }

    private fun checkDirectDragThroughOrigin(action: String, code: Int) {
        gestures.configure(TouchGestureConfig(dragAction = action))
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_MOVE, 0, 7 to (480f to 330f))
        absoluteMoves.clear()
        val path = listOf(430f to 315f, 410f to 305f, 400f to 300f, 390f to 295f, 370f to 285f, 320f to 270f)
        path.forEach { touch(MotionEvent.ACTION_MOVE, 0, 7 to it) }
        assertEquals(path, absoluteMoves)
        assertEquals(listOf("button $code true"), buttons())
        touch(MotionEvent.ACTION_UP, 0, 7 to path.last())
        advance(100)
        assertEquals(listOf("button $code true", "button $code false"), buttons())
    }

    @Test fun relativeDragReportsSmallMovementsWhileCrossingItsStartingPoint() {
        gestures.configure(TouchGestureConfig(mouseDragMovementMode = "relative"))
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_MOVE, 0, 7 to (480f to 300f))
        relativeMoves.clear()
        listOf(430f, 410f, 400f, 390f, 370f, 320f).forEach {
            touch(MotionEvent.ACTION_MOVE, 0, 7 to (it to 300f))
        }
        assertEquals(listOf(-50f, -20f, -10f, -10f, -20f, -50f), relativeMoves.map { it.first })
        assertTrue(relativeMoves.all { it.second == 0f })
        gestures.cancel()
        assertEquals(listOf("button 272 true", "button 272 false"), buttons())
    }

    @Test fun longPressCanSendAClickInsteadOfHolding() {
        gestures.configure(TouchGestureConfig(longPressEnabled = true, longPressMouseBehavior = "click"))
        touch(MotionEvent.ACTION_DOWN, 0, a)
        advance(400)
        assertEquals(listOf("button 273 true", "button 273 false"), buttons())
        touch(MotionEvent.ACTION_UP, 0, a); advance(80)
        assertEquals(2, buttons().size)
    }

    @Test fun aThirdFingerReleasesTheTwoFingerHold() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        advance(350)
        assertEquals(listOf("button 274 true"), buttons())
        touch(MotionEvent.ACTION_POINTER_DOWN, 2, a, b, c)
        assertEquals(listOf("button 274 true", "button 274 false"), buttons())
        gestures.cancel()
    }

    @Test fun threeFingerTapOpensKeyboardOnceAndDoesNotClick() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        touch(MotionEvent.ACTION_POINTER_DOWN, 2, a, b, c)
        advance(80)
        touch(MotionEvent.ACTION_POINTER_UP, 0, a, b, c)
        touch(MotionEvent.ACTION_POINTER_UP, 0, b, c)
        touch(MotionEvent.ACTION_UP, 0, c)
        advance(400)
        assertEquals(1, events.count { it == "keyboard" })
        assertTrue(buttons().isEmpty())
    }

    @Test fun fourToThreeFingersDoesNotReleaseTheThreeFingerHold() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        touch(MotionEvent.ACTION_POINTER_DOWN, 2, a, b, c)
        advance(350)
        assertTrue(events.contains("key 1 true"))
        touch(MotionEvent.ACTION_POINTER_DOWN, 3, a, b, c, d)
        touch(MotionEvent.ACTION_POINTER_UP, 3, a, b, c, d)
        assertFalse(events.contains("key 1 false"))
        gestures.cancel()
        assertTrue(events.contains("key 1 false"))
    }

    @Test fun cancelStopsPendingHoldAndTapCallbacks() {
        gestures.configure(TouchGestureConfig(longPressEnabled = true))
        touch(MotionEvent.ACTION_DOWN, 0, a)
        gestures.cancel()
        events.clear(); advance(2000)
        assertTrue(events.isEmpty())
    }

    @Test fun twoFingerPanHoldsTheArrowAndReleasesItOnLift() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        advance(150)
        touch(MotionEvent.ACTION_MOVE, 0, 7 to (480f to 300f), 12 to (680f to 300f))
        assertTrue(events.contains("key 106 true"))
        assertFalse(events.any { it.startsWith("wheel") })
        touch(MotionEvent.ACTION_POINTER_UP, 1, 7 to (480f to 300f), 12 to (680f to 300f))
        assertTrue(events.contains("key 106 false"))
        touch(MotionEvent.ACTION_UP, 0, 7 to (480f to 300f))
        advance(400)
        assertTrue(buttons().isEmpty())
    }

    @Test fun pinchZoomDoesNotAlsoStartAPan() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        advance(150)
        touch(MotionEvent.ACTION_MOVE, 0, 7 to (320f to 300f), 12 to (680f to 300f))
        assertTrue(events.contains("wheel -1"))
        assertFalse(events.any { it.startsWith("key") })
        gestures.cancel(); advance(400)
        assertTrue(buttons().isEmpty())
    }

    @Test fun threeFingerRightDragReleasesOnCancel() {
        gestures.configure(TouchGestureConfig(threeFingerDragEnabled = true, threeFingerDragAction = "right_click_drag"))
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        touch(MotionEvent.ACTION_POINTER_DOWN, 2, a, b, c)
        advance(100)
        touch(MotionEvent.ACTION_MOVE, 0, 7 to (480f to 300f), 12 to (680f to 300f), 19 to (880f to 300f))
        assertEquals(listOf("button 273 true"), buttons())
        gestures.cancel()
        assertEquals(listOf("button 273 true", "button 273 false"), buttons())
    }

    @Test fun slowMiddlePanRetainsOnePixelMovesAfterRecognition() {
        startTwoFingerPan("middle_mouse_pan")
        relativeMoves.clear()
        for (offset in 81..90) {
            advance(8)
            moveTwoFingerPan(offset.toFloat())
        }
        assertEquals(List(10) { 1f to 0f }, relativeMoves)
        gestures.cancel()
        assertEquals("button 274 false", buttons().last())
    }

    @Test fun slowInvertedMiddlePanRetainsInvertedOnePixelMoves() {
        startTwoFingerPan("inverted_middle_mouse_pan")
        relativeMoves.clear()
        for (offset in 81..90) moveTwoFingerPan(offset.toFloat())
        assertEquals(List(10) { -1f }, relativeMoves.map { it.first })
        assertTrue(relativeMoves.all { it.second == 0f })
        gestures.cancel()
    }

    @Test fun slowThreeFingerMiddlePanRetainsSmallMoves() {
        gestures.configure(TouchGestureConfig(threeFingerDragEnabled = true, threeFingerDragAction = "middle_mouse_pan"))
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        touch(MotionEvent.ACTION_POINTER_DOWN, 2, a, b, c)
        advance(100)
        touch(MotionEvent.ACTION_MOVE, 0, 7 to (480f to 300f), 12 to (680f to 300f), 19 to (880f to 300f))
        relativeMoves.clear()
        for (offset in 81..85) {
            touch(MotionEvent.ACTION_MOVE, 0, 7 to (400f + offset to 300f), 12 to (600f + offset to 300f), 19 to (800f + offset to 300f))
        }
        assertEquals(List(5) { 1f to 0f }, relativeMoves)
        gestures.cancel()
    }

    @Test fun slowKeyboardPanCanStartUsingOnlySmallMovementEvents() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        advance(150)
        for (offset in 1..45) { advance(8); moveTwoFingerPan(offset.toFloat()) }
        assertTrue(events.contains("key 106 true"))
        gestures.cancel()
        assertEquals("key 106 false", events.filter { it.startsWith("key") }.last())
    }

    @Test fun slowKeyboardPanReversalReleasesTheOldDirection() {
        startTwoFingerPan("arrow_keys")
        events.clear()
        for (offset in 79 downTo 75) moveTwoFingerPan(offset.toFloat())
        assertEquals(listOf("key 106 false", "key 105 true"), events.filter { it.startsWith("key") })
        gestures.cancel()
        assertEquals("key 105 false", events.filter { it.startsWith("key") }.last())
    }

    @Test fun slowDiagonalKeyboardPanCanHoldBothDirections() {
        startTwoFingerPan("arrow_keys")
        events.clear()
        for (i in 1..4) {
            touch(MotionEvent.ACTION_MOVE, 0, 7 to (480f + i to 300f + i), 12 to (680f + i to 300f + i))
        }
        assertEquals(listOf("key 108 true"), events.filter { it.startsWith("key") })
        gestures.cancel()
        assertTrue(events.contains("key 106 false"))
        assertTrue(events.contains("key 108 false"))
    }

    @Test fun keyboardPanRemainderDoesNotSurviveCancellation() {
        startTwoFingerPan("arrow_keys")
        for (offset in 79 downTo 76) moveTwoFingerPan(offset.toFloat())
        gestures.cancel()
        startTwoFingerPan("arrow_keys")
        events.clear()
        moveTwoFingerPan(79f)
        assertTrue(events.none { it.startsWith("key") })
        gestures.cancel()
    }

    @Test fun slowPinchAccumulatesFractionalMovementIntoAWheelStep() {
        startPinch()
        events.clear()
        for (i in 1..20) { advance(8); movePinch(i * .25f) }
        assertEquals(listOf("wheel -1"), events.filter { it.startsWith("wheel") })
        gestures.cancel()
    }

    @Test fun slowPinchCanStartUsingOnlySmallMovementEvents() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        advance(150)
        for (offset in 1..25) {
            advance(8)
            touch(MotionEvent.ACTION_MOVE, 0, 7 to (400f - offset to 300f), 12 to (600f + offset to 300f))
        }
        assertEquals(listOf("wheel -1"), events.filter { it.startsWith("wheel") })
        assertTrue(events.none { it.startsWith("key") })
        gestures.cancel()
    }

    @Test fun pinchReversalDoesNotConsumeOldDirectionRemainder() {
        startPinch()
        events.clear()
        movePinch(4.5f) // Nine pixels toward zooming in, short of a step.
        assertTrue(events.none { it.startsWith("wheel") })
        for (i in 1..10) movePinch(4.5f - i * .5f)
        assertEquals(listOf("wheel 1"), events.filter { it.startsWith("wheel") })
        gestures.cancel()
    }

    @Test fun pinchRemainderDoesNotLeakIntoTheNextGesture() {
        startPinch()
        movePinch(4.5f)
        gestures.cancel()
        startPinch()
        events.clear()
        movePinch(.5f)
        assertTrue(events.none { it.startsWith("wheel") })
        gestures.cancel()
    }

    @Test fun aLargePinchStillEmitsOneStepWithoutQueuedZoomAfterRelease() {
        startPinch()
        events.clear()
        movePinch(40f)
        assertEquals(listOf("wheel -1"), events.filter { it.startsWith("wheel") })
        gestures.cancel()
        events.clear(); advance(1000)
        assertTrue(events.isEmpty())
    }

    @Test fun slowPinchKeyboardBindingsReleaseTheirKeys() {
        startPinch("plus_minus")
        events.clear(); timedKeys.clear()
        for (i in 1..20) { advance(8); movePinch(i * .25f) }
        assertEquals(listOf("key 13 true"), events.filter { it.startsWith("key") })
        advance(50)
        assertEquals(listOf("key 13 true", "key 13 false"), events.filter { it.startsWith("key") })
        gestures.cancel()
        events.clear(); advance(1000)
        assertTrue(events.isEmpty())
    }

    @Test fun middlePanDoesNotJumpBackToTheTouchAnchorAfterTheClientRecenters() {
        checkRecenteredPan("middle_mouse_pan", 1040f)
    }

    @Test fun invertedMiddlePanUsesOnlyInvertedFingerDeltasAfterRecentering() {
        checkRecenteredPan("inverted_middle_mouse_pan", 880f)
    }

    private fun checkRecenteredPan(action: String, expected: Float) {
        var cachedX = 0f
        var clientX = 0f
        var middleDown = false
        gestures = TouchscreenGestures(ViewConfiguration.get(RuntimeEnvironment.getApplication()).scaledTouchSlop.toFloat(), object : TouchscreenGestures.Output {
            override fun moveTo(x: Float, y: Float) { cachedX = x; clientX = x }
            override fun prepareRelativePan() { cachedX = 960f; clientX = 960f }
            override fun moveBy(dx: Float, dy: Float, relative: Boolean) {
                // Before pointer lock, the compositor delivers an absolute
                // position even when we inject a relative delta. The game
                // only requests lock after seeing this first pan movement.
                cachedX += dx
                clientX = cachedX
            }
            override fun button(code: Int, down: Boolean) {
                if (code == 274) {
                    middleDown = down
                    if (down) clientX = 960f // The client warps to the middle when pan starts.
                }
            }
            override fun key(code: Int, down: Boolean) {}
            override fun wheel(steps: Int) {}
            override fun showKeyboard() {}
        })
        gestures.configure(TouchGestureConfig(twoFingerDragAction = action))
        touch(MotionEvent.ACTION_DOWN, 0, 7 to (1200f to 300f))
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, 7 to (1200f to 300f), 12 to (1400f to 300f))
        advance(150)
        touch(MotionEvent.ACTION_MOVE, 0, 7 to (1280f to 300f), 12 to (1480f to 300f))
        assertTrue(middleDown)
        assertEquals(expected, clientX, 0.01f)
        touch(MotionEvent.ACTION_POINTER_UP, 1, 7 to (1280f to 300f), 12 to (1480f to 300f))
        assertFalse(middleDown)
    }

    @Test fun middlePanPreparesItsOriginBeforePressAndOnlyOncePerDrag() {
        gestures.configure(TouchGestureConfig(twoFingerDragAction = "middle_mouse_pan"))
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        advance(150)
        events.clear()
        touch(MotionEvent.ACTION_MOVE, 0, 7 to (480f to 300f), 12 to (680f to 300f))
        touch(MotionEvent.ACTION_MOVE, 0, 7 to (490f to 300f), 12 to (690f to 300f))
        assertEquals(listOf("prepare pan", "button 274 true", "delta true", "delta true"), events)
        gestures.cancel()
        assertEquals("button 274 false", events.last())
    }

    @Test fun leftClickDragDoesNotPrepareARelativePanOrigin() {
        gestures.configure(TouchGestureConfig(twoFingerDragAction = "left_click_drag"))
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        advance(150)
        events.clear()
        touch(MotionEvent.ACTION_MOVE, 0, 7 to (480f to 300f), 12 to (680f to 300f))
        assertFalse(events.contains("prepare pan"))
        assertTrue(buttons().contains("button 272 true"))
    }

    @Test fun keyboardActionsUseEvdevCodesAndReleaseOnCancel() {
        gestures.configure(TouchGestureConfig(longPressEnabled = true, longPressAction = "key_CTRL_R"))
        touch(MotionEvent.ACTION_DOWN, 0, a); advance(350)
        assertTrue(events.contains("key 97 true"))
        gestures.cancel()
        assertTrue(events.contains("key 97 false"))
        assertEquals(96, GestureKeys.code("KP_ENTER"))
        assertEquals(99, GestureKeys.code("PRTSCN"))
    }


    @Test fun replacingAnOriginalFingerDoesNotInjectThreeFingerPanMotion() {
        gestures.configure(TouchGestureConfig(threeFingerDragEnabled = true, threeFingerDragAction = "middle_mouse_pan"))
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        touch(MotionEvent.ACTION_POINTER_DOWN, 2, a, b, c)
        advance(100)
        val movedA = 7 to (480f to 300f)
        val movedB = 12 to (680f to 300f)
        val movedC = 19 to (880f to 300f)
        touch(MotionEvent.ACTION_MOVE, 0, movedA, movedB, movedC)
        touch(MotionEvent.ACTION_POINTER_DOWN, 3, movedA, movedB, movedC, d)
        relativeMoves.clear()
        touch(MotionEvent.ACTION_POINTER_UP, 0, movedA, movedB, movedC, d)
        touch(MotionEvent.ACTION_MOVE, 0, movedB, movedC, d)
        assertTrue("No finger moved; observed deltas: $relativeMoves", relativeMoves.isEmpty())
        touch(MotionEvent.ACTION_MOVE, 0, 12 to (690f to 300f), 19 to (890f to 300f), 24 to (1010f to 300f))
        assertEquals(listOf(10f to 0f), relativeMoves)
        assertEquals(1, events.count { it == "prepare pan" })
        gestures.cancel()
    }

    @Test fun twoFingerDirectDragStartsAtItsOwnCentroid() {
        gestures.configure(TouchGestureConfig(twoFingerDragAction = "left_click_drag"))
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        advance(150)
        touch(MotionEvent.ACTION_MOVE, 0, 7 to (480f to 300f), 12 to (680f to 300f))
        assertEquals(500f to 300f, leftClicks.single().position)
        gestures.cancel()
    }

    @Test fun threeFingerDirectDragStartsAtItsOwnCentroid() {
        gestures.configure(TouchGestureConfig(threeFingerDragEnabled = true, threeFingerDragAction = "right_click_drag"))
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        touch(MotionEvent.ACTION_POINTER_DOWN, 2, a, b, c)
        advance(100)
        touch(MotionEvent.ACTION_MOVE, 0, 7 to (480f to 300f), 12 to (680f to 300f), 19 to (880f to 300f))
        assertEquals(600f to 300f, rightClicks.single().position)
        gestures.cancel()
    }

    @Test fun batchedTwoFingerExcursionCannotBecomeATap() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        advance(20)
        batchedReturn(7 to (480f to 300f), 12 to (680f to 300f))
        touch(MotionEvent.ACTION_POINTER_UP, 1, a, b)
        touch(MotionEvent.ACTION_UP, 0, a)
        advance(1000)
        assertTrue("An out-and-back swipe generated: ${buttons()}", buttons().isEmpty())
    }

    @Test fun batchedSingleFingerExcursionCannotBecomeATapWithDragDisabled() {
        gestures.configure(TouchGestureConfig(dragEnabled = false))
        touch(MotionEvent.ACTION_DOWN, 0, a)
        advance(20)
        batchedReturn(7 to (480f to 300f))
        touch(MotionEvent.ACTION_UP, 0, a)
        advance(1000)
        assertTrue("An out-and-back swipe generated: ${buttons()}", buttons().isEmpty())
    }

    @Test fun replacingAFingerDuringDirectDragPreservesPositionAndContinuesMoving() {
        gestures.configure(TouchGestureConfig(threeFingerDragEnabled = true, threeFingerDragAction = "right_click_drag"))
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        touch(MotionEvent.ACTION_POINTER_DOWN, 2, a, b, c)
        advance(100)
        val movedA = 7 to (480f to 300f)
        val movedB = 12 to (680f to 300f)
        val movedC = 19 to (880f to 300f)
        touch(MotionEvent.ACTION_MOVE, 0, movedA, movedB, movedC)
        val before = cursor
        touch(MotionEvent.ACTION_POINTER_DOWN, 3, movedA, movedB, movedC, d)
        touch(MotionEvent.ACTION_POINTER_UP, 0, movedA, movedB, movedC, d)
        touch(MotionEvent.ACTION_MOVE, 0, movedB, movedC, d)
        assertEquals(before, cursor)
        touch(MotionEvent.ACTION_MOVE, 0, 12 to (692f to 300f), 19 to (892f to 300f), 24 to (1012f to 300f))
        assertEquals(before.first + 12f to before.second, cursor)
        gestures.cancel()
        assertEquals(listOf("button 273 true", "button 273 false"), buttons())
    }

    @Test fun batchedPinchExcursionCancelsTapEvenWhenEachFingerStaysInsidePanTolerance() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        advance(20)
        batchedReturn(7 to (375f to 300f), 12 to (625f to 300f))
        advance(350)
        touch(MotionEvent.ACTION_POINTER_UP, 1, a, b)
        touch(MotionEvent.ACTION_UP, 0, a)
        advance(100)
        assertTrue(events.none { it.startsWith("button") || it.startsWith("key") || it == "keyboard" })
    }

    @Test fun batchedSingleFingerExcursionCancelsPendingLongPress() {
        gestures.configure(TouchGestureConfig(dragEnabled = false, longPressEnabled = true, longPressAction = "key_E"))
        touch(MotionEvent.ACTION_DOWN, 0, a)
        batchedReturn(7 to (480f to 300f))
        advance(350)
        assertTrue(timedKeys.isEmpty())
        gestures.cancel()
    }

    @Test fun batchedJitterPreservesTwoFingerHold() {
        gestures.configure(TouchGestureConfig(twoFingerHoldAction = "key_E"))
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        batchedReturn(7 to (403f to 302f), 12 to (603f to 302f))
        advance(350)
        assertTrue(events.contains("key 18 true"))
        gestures.cancel()
        assertEquals("key 18 false", events.last())
    }

    @Test fun batchedThreeFingerExcursionCannotBecomeATapOrHold() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        touch(MotionEvent.ACTION_POINTER_DOWN, 2, a, b, c)
        batchedReturn(7 to (480f to 300f), 12 to (680f to 300f), 19 to (880f to 300f))
        advance(350)
        touch(MotionEvent.ACTION_POINTER_UP, 2, a, b, c)
        touch(MotionEvent.ACTION_POINTER_UP, 1, a, b)
        touch(MotionEvent.ACTION_UP, 0, a)
        advance(100)
        assertTrue(events.none { it.startsWith("button") || it.startsWith("key") || it == "keyboard" })
    }

    private fun batchedReturn(vararg excursion: Pair<Int, Pair<Float, Float>>) {
        val originals = listOf(a, b, c).take(excursion.size)
        val properties = excursion.map { (id, _) -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        fun coords(points: List<Pair<Int, Pair<Float, Float>>>) = points.map { (_, pos) -> MotionEvent.PointerCoords().apply { x = pos.first; y = pos.second; pressure = 1f; size = 1f } }.toTypedArray()
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, excursion.size,
            properties, coords(excursion.toList()), 0, 0, 1f, 1f, 0, 0, 0, 0)
        advance(10)
        event.addBatch(SystemClock.uptimeMillis(), coords(originals), 0)
        assertEquals(1, event.historySize)
        gestures.onTouch(event)
        event.recycle()
    }

    private fun buttons() = events.filter { it.startsWith("button") }
    private fun startTwoFingerPan(action: String) {
        gestures.configure(TouchGestureConfig(twoFingerDragAction = action))
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        advance(150)
        moveTwoFingerPan(80f)
        advance(110)
    }
    private fun moveTwoFingerPan(offset: Float) {
        touch(MotionEvent.ACTION_MOVE, 0, 7 to (400f + offset to 300f), 12 to (600f + offset to 300f))
    }
    private fun startPinch(action: String = "scroll_wheel") {
        gestures.configure(TouchGestureConfig(pinchAction = action))
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        advance(150)
        movePinch(0f)
        advance(110)
    }
    private fun movePinch(offset: Float) {
        touch(MotionEvent.ACTION_MOVE, 0, 7 to (370f - offset to 300f), 12 to (630f + offset to 300f))
    }
    private fun twoFingerTap() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        advance(30)
        touch(MotionEvent.ACTION_POINTER_UP, 1, a, b)
        touch(MotionEvent.ACTION_UP, 0, a)
    }
    private fun threeFingerTap() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        touch(MotionEvent.ACTION_POINTER_DOWN, 2, a, b, c)
        advance(30)
        touch(MotionEvent.ACTION_POINTER_UP, 2, a, b, c)
        touch(MotionEvent.ACTION_POINTER_UP, 1, a, b)
        touch(MotionEvent.ACTION_UP, 0, a)
    }
    private fun tap(point: Pair<Int, Pair<Float, Float>> = a) {
        touch(MotionEvent.ACTION_DOWN, 0, point)
        advance(30)
        touch(MotionEvent.ACTION_UP, 0, point)
    }
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun touch(action: Int, index: Int, vararg points: Pair<Int, Pair<Float, Float>>) {
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        val properties = points.map { (id, _) -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val coords = points.map { (_, pos) -> MotionEvent.PointerCoords().apply { x = pos.first; y = pos.second; pressure = 1f; size = 1f } }.toTypedArray()
        MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), points.size,
            properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0).also { event ->
            gestures.onTouch(event); event.recycle()
        }
    }
}
