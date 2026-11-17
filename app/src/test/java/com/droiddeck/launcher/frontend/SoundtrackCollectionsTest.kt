package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.frontend.SoundtrackCollections.Collection
import com.droiddeck.launcher.frontend.SoundtrackCollections.TrackRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SoundtrackCollectionsTest {
    private fun ref(path: String) = TrackRef(path, path, "", "", 0)

    @Test fun toggledAddsThenRemoves() {
        val once = SoundtrackCollections.toggled(emptyList(), ref("a"))
        assertEquals(listOf("a"), once.map { it.path })
        assertTrue(SoundtrackCollections.toggled(once, ref("a")).isEmpty())
    }

    @Test fun mergedSkipsDuplicatesAndKeepsOrder() {
        val result = SoundtrackCollections.merged(listOf(ref("a"), ref("b")), listOf(ref("b"), ref("c"), ref("c")))
        assertEquals(listOf("a", "b", "c"), result.map { it.path })
    }

    @Test fun cleanNameTrimsAndCollapsesSpaces() = assertEquals("My mix", SoundtrackCollections.cleanName("  My    mix  "))

    @Test fun nameTakenIgnoresCaseAndExcludedId() {
        val list = listOf(Collection(2_000_000_001, "Chill", emptyList()))
        assertTrue(SoundtrackCollections.nameTaken(list, "chill"))
        assertFalse(SoundtrackCollections.nameTaken(list, "chill", except = 2_000_000_001))
        assertFalse(SoundtrackCollections.nameTaken(list, "Focus"))
    }

    @Test fun nextIdStartsAboveFavoritesAndGrows() {
        assertEquals(SoundtrackCollections.FAVORITES_ID + 1, SoundtrackCollections.nextId(emptyList()))
        val list = listOf(Collection(2_000_000_005, "x", emptyList()))
        assertEquals(2_000_000_006, SoundtrackCollections.nextId(list))
    }
}
