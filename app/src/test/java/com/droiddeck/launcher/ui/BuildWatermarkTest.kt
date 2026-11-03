package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BuildWatermarkTest {
    @Test
    fun stableAndPreviewBuildsHaveNoWatermark() {
        assertNull(BuildWatermark.lines(pr = 0, ci = true, commit = "abcdef0123", title = "", branch = "", play = false))
    }

    @Test
    fun prBuildShowsNumberHashAndTitle() {
        assertEquals(
            listOf("PR-1234 | abcdef0", "Fix: foo the bar"),
            BuildWatermark.lines(pr = 1234, ci = true, commit = "abcdef0123", title = "Fix: foo the bar", branch = "x", play = false),
        )
    }

    @Test
    fun longTitlesTruncateAtEightyCharacters() {
        val lines = BuildWatermark.lines(pr = 7, ci = true, commit = "abcdef0", title = "a".repeat(120), branch = "", play = false)!!
        assertEquals(80, lines[1].length)
        assertEquals('…', lines[1].last())
    }

    @Test
    fun localBuildShowsBranch() {
        assertEquals(
            listOf("LOCAL | abcdef0", "feat/thing"),
            BuildWatermark.lines(pr = 0, ci = false, commit = "abcdef0123", title = "", branch = "feat/thing", play = false),
        )
    }

    @Test
    fun playBuildShowsOnlyTheShortSourceCommit() {
        for (ci in listOf(true, false)) for (pr in listOf(0, 1234)) {
            assertEquals(listOf("abcdef0"), BuildWatermark.lines(
                pr = pr, ci = ci, commit = "abcdef0123456789", title = "Build title", branch = "playstore", play = true,
            ))
        }
    }
}
