package com.droiddeck.launcher.ui

import android.content.Context
import com.droiddeck.launcher.input.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1000dp-h900dp")
class InputLayoutSizeEdgeTest {
    @get:Rule val looper = MainLooperDrain()
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun aGroupInTheCornerKeepsItsShapeWhenTheLayoutGrows() {
        context.getSharedPreferences("input-profiles", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("input-surfaces", Context.MODE_PRIVATE).edit().clear().commit()
        val library = InputLibraries.read(context)
        val layout = library.layouts.first { it.id == InputLibraries.OVERLAY }
        // The left 5% stands in for a display cutout the controls must stay out of.
        val area = SurfaceBounds(.05f, 0f, 1f, 1f)
        fun draft() = SurfaceDraft(layout, library.preset(library.profile("game").binding(InputRole.PRIMARY)), null, GamepadScaler(context, 2400, 1080), area = area)
        fun SurfaceDraft.shape(ids: List<String>): List<Pair<Float, Float>> {
            val b = elements.boundsOf(ids)!!
            return elements.filter { it.id in ids }.map { (it.x - b.left) / b.width to (it.y - b.top) / b.height }
        }
        for (ids in listOf(draft().elements.groupOf("legacy:up"), draft().elements.groupOf("legacy:a"), draft().elements.groupOf("legacy:rb"))) {
            for ((percent, toward) in listOf(110, 125).flatMap { p -> listOf(p to 1f, p to -1f) }) {
                val free = draft().apply { scaleAll(percent) }
                val cornered = draft().apply {
                    elements = elements.moveSelection(ids, toward, 1f).let { moved -> if (toward < 0f) moved.keptIn(area) else moved }
                    scaleAll(percent)
                }
                val b = cornered.elements.boundsOf(ids)!!
                assertTrue("$ids $percent $b", b.left >= area.left - .0001f && b.top >= 0f && b.right <= 1.0001f && b.bottom <= 1.0001f)
                for ((now, was) in cornered.shape(ids).zip(free.shape(ids))) {
                    assertEquals("$ids $percent $toward", was.first, now.first, .001f)
                    assertEquals("$ids $percent $toward", was.second, now.second, .001f)
                }
            }
        }
    }
}
