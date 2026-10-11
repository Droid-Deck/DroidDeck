package com.droiddeck.launcher.stores

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sizes kept per version, and a refresh that touches only the cards that changed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StoreCacheTest {
    private val dir = Files.createTempDirectory("sizes").toFile()

    @Test fun aSizeIsKeptForItsVersionAndAskedAgainForANewOne() {
        val cache = SizeCache.forStore(dir, Store.EPIC)
        cache.put("Metalstorm", "1.0.3", 4_400_000_000L)
        // Read back from disk by a new instance, as the next app start does.
        val again = SizeCache.forStore(dir, Store.EPIC)
        assertEquals(4_400_000_000L, again.get("Metalstorm", "1.0.3"))
        assertNull(again.get("Metalstorm", "1.0.4"))
        assertNull(again.get("Other", "1.0.3"))
    }

    @Test fun aFileInAnotherFormatIsDiscarded() {
        val file = File(dir, "stores/gog/sizes.json").apply { parentFile!!.mkdirs(); writeText("""{"format":0,"sizes":{"1":{"v":"a","b":5}}}""") }
        assertNull(SizeCache(file).get("1", "a"))
    }

    @Test fun aRefreshKeepsTheObjectsOfUnchangedCards() {
        val a = CatalogItem(Store.GOG, "1", "A", imageUrl = "x", owned = true)
        val b = CatalogItem(Store.GOG, "2", "B", imageUrl = "y", owned = true)
        val old = listOf(a, b)
        // Nothing changed: the very list shown stays.
        assertSame(old, mergeItems(old, listOf(a.copy(), b.copy())))
        // One card changed: the other is the same object, the changed one is new.
        val merged = mergeItems(old, listOf(a.copy(), b.copy(title = "B2")))
        assertSame(a, merged[0])
        assertNotSame(b, merged[1])
        assertEquals("B2", merged[1].title)
        // A first load is the new list as it is.
        val fresh = listOf(a)
        assertSame(fresh, mergeItems(null, fresh))
    }
}
