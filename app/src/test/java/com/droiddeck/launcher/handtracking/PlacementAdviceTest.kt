package com.droiddeck.launcher.handtracking

import com.droiddeck.launcher.glasses.Vec3
import org.junit.Assert.assertEquals
import org.junit.Test

class PlacementAdviceTest {
    private val hand = Vec3(0.2f, -0.4f, -0.3f)
    private fun body(span: Float = 0.2f, left: Vec3? = hand, right: Vec3? = hand, seen: Boolean = true, room: Float = 0.2f) =
        BodyFrame(arrayOf(left, right), 3f, 2f, span, seen, edgeRoom = room)

    @Test fun aFlatOrTiltedDeviceIsFixedFirst() {
        assertEquals(Advice.STAND_UP, PlacementAdvice.of(Placement.FACE_UP, body()))
        assertEquals(Advice.STAND_UP, PlacementAdvice.of(Placement.FACE_DOWN, body()))
        assertEquals(Advice.UPRIGHT, PlacementAdvice.of(Placement.POINTS_UP, null))
        assertEquals(Advice.UPRIGHT, PlacementAdvice.of(Placement.POINTS_DOWN, body()))
    }

    @Test fun thenThePersonDistanceAndHands() {
        assertEquals(Advice.NOT_SEEN, PlacementAdvice.of(Placement.STANDING, null))
        assertEquals(Advice.NOT_SEEN, PlacementAdvice.of(Placement.STANDING, body(seen = false)))
        assertEquals(Advice.MOVE_BACK, PlacementAdvice.of(Placement.STANDING, body(span = 0.5f)))
        assertEquals("a person 0.65 m from a wide camera is too close", Advice.MOVE_BACK, PlacementAdvice.of(Placement.STANDING, body(span = 0.3f)))
        assertEquals("hands at the edge of the picture", Advice.MOVE_BACK, PlacementAdvice.of(Placement.STANDING, body(room = 0.02f)))
        assertEquals(Advice.MOVE_CLOSER, PlacementAdvice.of(Placement.STANDING, body(span = 0.05f)))
        assertEquals(Advice.MOVE_CLOSER, PlacementAdvice.of(Placement.STANDING, body(span = 0.1f)))
        assertEquals(Advice.SHOW_HANDS, PlacementAdvice.of(Placement.STANDING, body(right = null)))
        assertEquals(Advice.GOOD, PlacementAdvice.of(Placement.STANDING, body()))
        assertEquals(Advice.GOOD, PlacementAdvice.of(Placement.UNKNOWN, body()))
    }
}
