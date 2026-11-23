package com.droiddeck.launcher.runtime

import com.droiddeck.launcher.ui.deckyStoreMatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class DeckyStoreTest {
    private val hash = "a".repeat(64)
    private val listing = """[
        {"id":1,"name":"Alpha","author":"Ann","description":"Does alpha things","tags":["audio"],
         "versions":[{"name":"2.0.0","hash":"$hash","downloads":10},{"name":"1.0.0","hash":"${"b".repeat(64)}","downloads":5}]},
        {"id":2,"name":"Beta","author":"Bob","description":"Themes","tags":["css"],"versions":[{"name":"1.0.0","hash":"${"c".repeat(64)}","downloads":100}]},
        {"id":3,"name":"NoVersions","author":"X","description":"","tags":[],"versions":[]},
        {"id":4,"name":"BadHash","author":"X","description":"","tags":[],"versions":[{"name":"1","hash":"nope","downloads":1}]}
    ]"""

    @Test fun listingKeepsOnlyPluginsWithAUsableVersionAndSumsDownloads() {
        val plugins = DeckyStore.parseListing(listing)
        assertEquals(listOf("Alpha", "Beta"), plugins.map { it.name })
        assertEquals("2.0.0", plugins[0].latest?.name)
        assertEquals(15L, plugins[0].downloads)
    }

    @Test fun searchMatchesNameAuthorDescriptionAndTagsAndSortsByDownloads() {
        val plugins = DeckyStore.parseListing(listing)
        assertEquals(listOf("Beta", "Alpha"), deckyStoreMatches(plugins, "").map { it.name })
        assertEquals(listOf("Alpha"), deckyStoreMatches(plugins, "ann audio").map { it.name })
        assertEquals(listOf("Beta"), deckyStoreMatches(plugins, "THEMES").map { it.name })
        assertTrue(deckyStoreMatches(plugins, "missing").isEmpty())
    }

    @Test fun anUpdateNeedsAKnownInstalledVersionThatDiffersFromTheLatest() {
        val alpha = DeckyStore.parseListing(listing).first()
        assertTrue(DeckyStore.updateAvailable(DeckyStore.Installed("Alpha", "Alpha", "Ann", "1.0.0"), alpha))
        assertFalse(DeckyStore.updateAvailable(DeckyStore.Installed("Alpha", "Alpha", "Ann", "2.0.0"), alpha))
        assertFalse(DeckyStore.updateAvailable(DeckyStore.Installed("Alpha", "Alpha", "Ann", ""), alpha))
        assertFalse(DeckyStore.updateAvailable(DeckyStore.Installed("Alpha", "Alpha", "Ann", "1.0.0"), null))
        assertNull(DeckyStore.parseListing("[]").firstOrNull())
    }
}
