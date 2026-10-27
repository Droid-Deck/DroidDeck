package com.droiddeck.launcher.stores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GogCloudNamesTest {
    private fun files(vararg names: String) = names.map { CloudFile(it, 1000L) }

    @Test fun silksongLocationIsDroppedFromThePath() {
        val (location, mapped) = gogRelativeNames(
            files("saves/user1.dat", "saves/shared.dat", "saves/Restore_Points1/restoreData2.dat"), "saves")
        assertEquals("saves", location)
        assertEquals(listOf("user1.dat", "shared.dat", "Restore_Points1/restoreData2.dat"), mapped.map { it.name })
    }

    @Test fun anotherLocationIsFilteredOut() {
        val (_, mapped) = gogRelativeNames(files("saves/a.dat", "__default/settings.ini"), "saves")
        assertEquals(listOf("a.dat"), mapped.map { it.name })
    }

    @Test fun theOnlyLocationIsInferredWhenItsNameWasNotCached() {
        val (location, mapped) = gogRelativeNames(files("saves/a.dat", "saves/b.dat"), null)
        assertEquals("saves", location)
        assertEquals(listOf("a.dat", "b.dat"), mapped.map { it.name })
    }

    @Test fun namesWithoutALocationPrefixAreLeftAlone() {
        val (location, mapped) = gogRelativeNames(files("a.dat", "b.dat"), null)
        assertNull(location)
        assertEquals(listOf("a.dat", "b.dat"), mapped.map { it.name })
    }

    @Test fun aConfiguredLocationDoesNotYieldToAnotherLocation() {
        val (location, mapped) = gogRelativeNames(files("__default/settings.ini"), "saves")
        assertEquals("saves", location)
        assertTrue(mapped.isEmpty())
    }

    @Test fun aMixedListingWithOneSubfolderIsLeftAlone() {
        val (location, mapped) = gogRelativeNames(files("a.dat", "sub/b.dat"), "saves")
        assertNull(location)
        assertEquals(listOf("a.dat", "sub/b.dat"), mapped.map { it.name })
    }

    @Test fun aMissingCachedLocationDoesNotHideUnprefixedNames() {
        val (location, mapped) = gogRelativeNames(files("a.dat"), "saves")
        assertNull(location)
        assertEquals(listOf("a.dat"), mapped.map { it.name })
    }
}
