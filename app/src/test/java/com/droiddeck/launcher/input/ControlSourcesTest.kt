package com.droiddeck.launcher.input

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ControlSourcesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { context.getSharedPreferences("community-control-sources", Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun hasRemoteArmsSourceWithoutBundlingItsArtwork() {
        assertEquals(listOf(ControlSources.DROIDDECK, ControlSources.ARMSX2), ControlSources.read(context))
        assertTrue(ControlSources.ARMSX2.manifest.contains("bagasromadon/ARMSX2-CustomControllerSkins"))
    }
    @Test fun githubRepositoriesAndFileLinksResolveToManifests() {
        val repo = ControlSources.candidates("https://github.com/example/controls")
        assertEquals(listOf("https://raw.githubusercontent.com/example/controls/main/catalog.json",
            "https://raw.githubusercontent.com/example/controls/main/manifest.json"), repo.map { it.manifest })
        assertEquals("https://raw.githubusercontent.com/example/controls/dev/skins/manifest.json",
            ControlSources.candidates("https://github.com/example/controls/blob/dev/skins/manifest.json").single().manifest)
    }
    @Test fun customSourcesPersistAndCanBeRemovedWithoutLosingDefaults() {
        val source = ControlSources.candidates("https://example.com/controls/catalog.json").single()
        ControlSources.add(context, source)
        assertEquals(source, ControlSources.read(context).last())
        assertThrows(IllegalArgumentException::class.java) { ControlSources.add(context, source) }
        ControlSources.remove(context, source)
        assertEquals(2, ControlSources.read(context).size)
    }
    @Test fun pathsStayWithinSourceAndEncodeSpaces() {
        assertEquals("https://raw.githubusercontent.com/bagasromadon/ARMSX2-CustomControllerSkins/main/Skins/Black%20Gold.zip",
            ControlSources.ARMSX2.url("Skins/Black Gold.zip"))
        for (path in listOf("../other.zip", "packs/../x.zip", "https://other.com/x", "/x", "x%2f..zip", "x?bad", "x\\bad"))
            assertThrows(IllegalArgumentException::class.java) { ControlSources.ARMSX2.url(path) }
        for (url in listOf("http://example.com/catalog.json", "https://user:secret@example.com/catalog.json", "file:///tmp/catalog.json", "https://example.com"))
            assertThrows(IllegalArgumentException::class.java) { ControlSources.candidates(url) }
    }
    @Test fun parsesArmsCatalogAndKeepsSourceAndOptionalLicenseSeparate() {
        val raw = """{"skins":[{"name":"Black Gold", "author":"maker", "file":"Skins/Black Gold.zip", "preview":"Previews/Black_Gold.png"}]}"""
        val entry = CommunityControls.catalog(raw, ControlSources.ARMSX2).single()
        assertTrue(entry.skin)
        assertEquals("", entry.license)
        assertEquals("", entry.sha256)
        assertEquals(ControlSources.ARMSX2, entry.source)
        assertNotEquals(entry.key, entry.copy(source = ControlSources.DROIDDECK).key)
    }
}
