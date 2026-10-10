package com.droiddeck.launcher.input

import org.junit.Assert.*
import org.junit.Test

class InputSpaceTest {
    private fun sceneUnder(viewX: Float, viewY: Float, view: Pair<Int, Int>, scene: Pair<Int, Int>): Pair<Double, Double> {
        val k = minOf(view.first.toDouble() / scene.first, view.second.toDouble() / scene.second)
        val offX = (view.first - scene.first * k) / 2
        val offY = (view.second - scene.second * k) / 2
        val ox = InputSpace.x(viewX, view.first).toDouble() * view.first / InputSpace.WIDTH
        val oy = InputSpace.y(viewY, view.second).toDouble() * view.second / InputSpace.HEIGHT
        return ((ox - offX) / k).coerceIn(0.0, scene.first - 1.0) to ((oy - offY) / k).coerceIn(0.0, scene.second - 1.0)
    }

    private fun assertLandsUnderFinger(view: Pair<Int, Int>, scene: Pair<Int, Int>) {
        val k = minOf(view.first.toFloat() / scene.first, view.second.toFloat() / scene.second)
        val offX = (view.first - scene.first * k) / 2
        val offY = (view.second - scene.second * k) / 2
        val stepX = view.first.toDouble() / InputSpace.WIDTH / k
        val stepY = view.second.toDouble() / InputSpace.HEIGHT / k
        for (sx in listOf(10, scene.first / 4, scene.first / 2, scene.first - 10))
            for (sy in listOf(10, scene.second / 4, scene.second / 2, scene.second - 10)) {
                val (x, y) = sceneUnder(offX + sx * k, offY + sy * k, view, scene)
                assertEquals("x at $sx,$sy on $view showing $scene", sx.toDouble(), x, stepX + 0.01)
                assertEquals("y at $sx,$sy on $view showing $scene", sy.toDouble(), y, stepY + 0.01)
            }
    }

    @Test fun aTouchReachesTheScenePixelUnderTheFinger() {
        assertLandsUnderFinger(2400 to 1504, 1280 to 720)
        assertLandsUnderFinger(2400 to 1080, 1280 to 720)
        assertLandsUnderFinger(2400 to 1504, 1148 to 720)
        assertLandsUnderFinger(1080 to 2400, 1280 to 720)
    }

    @Test fun positionsOutsideTheViewStayInRange() {
        assertEquals(0, InputSpace.x(-50f, 2400))
        assertEquals(InputSpace.WIDTH - 1, InputSpace.x(2400f, 2400))
        assertEquals(InputSpace.HEIGHT - 1, InputSpace.y(5000f, 1504))
        assertEquals(0, InputSpace.y(10f, 0))
    }
}
