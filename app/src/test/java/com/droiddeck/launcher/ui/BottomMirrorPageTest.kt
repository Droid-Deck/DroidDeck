package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BottomMirrorPageTest {
    @Test
    fun regionsRoundTripAndNormalize() {
        val r = MirrorRegion.normalize(0.9f, 0.3f, 0.75f, 0.05f)!!
        assertEquals(0.75f, r.left, 0.0001f)
        assertEquals(0.05f, r.top, 0.0001f)
        assertEquals(0.9f, r.right, 0.0001f)
        assertEquals(0.3f, r.bottom, 0.0001f)
        val back = MirrorRegion.parse(MirrorRegion.format(r))!!
        assertEquals(r.left, back.left, 0.0001f)
        assertEquals(r.bottom, back.bottom, 0.0001f)
        assertEquals(1f, MirrorRegion.normalize(-1f, -1f, 2f, 2f)!!.right, 0f)
    }

    @Test
    fun tinyOrBrokenRegionsAreRejected() {
        assertNull(MirrorRegion.normalize(0.5f, 0.5f, 0.505f, 0.9f))
        assertNull(MirrorRegion.parse("0.1,0.2,0.3"))
        assertNull(MirrorRegion.parse(null))
        assertNull(MirrorRegion.parse("a,b,c,d"))
    }

    @Test
    fun outputKeepsTheRegionsAspect() {
        val minimap = MirrorRegion.normalize(0.8f, 0.0f, 1.0f, 0.3555f)!!
        val (w, h) = MirrorRegion.outputSize(minimap, 16f / 9f, 720)
        assertEquals(720, w)
        assertEquals(720, h)
        val bar = MirrorRegion.normalize(0.3f, 0.9f, 0.7f, 1.0f)!!
        assertEquals(720 to 101, MirrorRegion.outputSize(bar, 16f / 9f, 720))
    }
}
