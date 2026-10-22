package com.droiddeck.launcher.stores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Filling a grid one card at a time: what is on screen stays put, what is new lands where its title belongs. */
class StoreCatalogPatchTest {
    private fun item(id: String, title: String, image: String? = null) = CatalogItem(Store.GOG, id, title, imageUrl = image, owned = true)

    @Test fun onlyTheCardItWasGivenIsReplaced() {
        val alpha = item("1", "Alpha")
        val beta = item("2", "Beta")
        val next = patchItems(listOf(alpha, beta), listOf(item("2", "Beta", "wide.jpg")))
        assertEquals(2, next.size)
        assertSame(alpha, next[0])
        assertEquals("wide.jpg", next[1].imageUrl)
    }

    @Test fun aCardTheGridDoesNotHaveYetIsAddedInTitleOrder() {
        val next = patchItems(listOf(item("1", "Alpha"), item("3", "Gamma")), listOf(item("2", "Beta")))
        assertEquals(listOf("Alpha", "Beta", "Gamma"), next.map { it.title })
    }

    @Test fun anEmptyGridTakesTheCardAsItComes() {
        val next = patchItems(null, listOf(item("2", "Beta")))
        assertEquals(listOf("Beta"), next.map { it.title })
    }
}
