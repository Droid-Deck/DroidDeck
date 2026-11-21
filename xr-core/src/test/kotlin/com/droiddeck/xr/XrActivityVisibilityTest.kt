package com.droiddeck.xr

import org.junit.Test
import org.junit.Assert.assertEquals

class XrActivityVisibilityTest {
    @Test fun retiringActivityCannotHideItsReplacement() {
        val visibility = XrActivityVisibility()
        val old = Any()
        val replacement = Any()
        val published = mutableListOf<Boolean>()
        visibility.update(old, true, published::add)
        visibility.update(replacement, true, published::add)
        visibility.update(old, false, published::add)
        visibility.update(old, false, published::add)
        visibility.update(replacement, false, published::add)
        assertEquals(listOf(true, true, true, true, false), published)
    }

    @Test fun distinctActivitiesWithEqualValuesKeepIndependentOwnership() {
        val visibility = XrActivityVisibility()
        val first = String(charArrayOf('x'))
        val second = String(charArrayOf('x'))
        val published = mutableListOf<Boolean>()
        visibility.update(first, true, published::add)
        visibility.update(second, true, published::add)
        visibility.update(first, false, published::add)
        visibility.update(second, false, published::add)
        assertEquals(listOf(true, true, true, false), published)
    }
}
