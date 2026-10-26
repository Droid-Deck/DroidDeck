package com.droiddeck.launcher.stores.epic

import com.droiddeck.launcher.stores.Store
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EpicLibraryTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After fun tearDown() { EpicPrefs.get(app).edit().clear().commit() }

    @Test fun cacheRoundTripsInTheStoredShape() {
        val game = EpicGame("Fig", "ns", "cat", "Fig Game", artCover = "tall", artSquare = "wide", developer = "Dev",
            description = "Text", installSize = 42L, canRunOffline = false, releaseDate = "2024-01-01", publisher = "Pub")
        val text = EpicLibrary.toCacheJson(listOf(game))
        val o = JSONArray(text).getJSONObject(0)
        for (key in listOf("appName", "namespace", "catalogItemId", "title", "artCover", "artSquare", "developer",
            "description", "version", "installSize", "canRunOffline", "releaseDate", "publisher")) assertTrue(key, o.has(key))
        assertEquals(listOf(game), EpicLibrary.fromCacheJson(text))
    }

    @Test fun readingSkipsDedupsSortsAndCapsSizes() {
        val text = JSONArray()
            .put(JSONObject().put("appName", "b").put("title", "beta").put("installSize", (1L shl 41)))
            .put(JSONObject().put("title", "no app name"))
            .put(JSONObject().put("appName", "a").put("title", "Alpha"))
            .put(JSONObject().put("appName", "b").put("title", "beta again"))
            .toString()
        val games = EpicLibrary.fromCacheJson(text)
        assertEquals(listOf("a", "b"), games.map { it.appName })
        assertEquals(0L, games[1].installSize)
        assertTrue(games[0].canRunOffline)
        assertTrue(EpicLibrary.fromCacheJson("not json").isEmpty())
    }

    @Test fun catalogItemMapping() {
        EpicPrefs.get(app).edit().putLong("size_Fig", 1234L).putString("release_Old", "2001").commit()
        val item = EpicLibrary.toCatalogItem(app, EpicGame("Fig", "ns", "cat", "Fig & Co", artCover = "tall", description = "<p>Hi</p>", installSize = 9L))
        assertEquals(Store.EPIC, item.store)
        assertEquals("Fig", item.id)
        assertEquals("tall", item.imageUrl)
        assertEquals("tall", item.tallImageUrl)
        assertEquals(1234L, item.sizeBytes)
        assertEquals("Hi", item.description)
        assertTrue(item.owned)
        assertEquals("https://store.epicgames.com/en-US/browse?q=Fig+%26+Co", item.storeUrl)
        assertEquals(mapOf("namespace" to "ns", "catalogItemId" to "cat", "version" to ""), item.extra)
        val old = EpicLibrary.toCatalogItem(app, EpicGame("Old", "ns", "c", "Old", artSquare = "wide", installSize = 9L))
        assertEquals("2001", old.releaseDate)
        assertEquals(9L, old.sizeBytes)
        assertEquals("wide", old.tallImageUrl)
    }

    @Test fun dlcAreHiddenUnlessEverythingIsDlc() {
        val base = EpicGame("base", "ns", "c1", "Base")
        val dlc = EpicGame("dlc", "ns", "c2", "Add-on", isDlc = true)
        assertEquals(listOf("base"), EpicLibrary.visible(listOf(dlc, base)).map { it.appName })
        assertEquals(listOf("dlc"), EpicLibrary.visible(listOf(dlc)).map { it.appName })
    }

    @Test fun syncedGamesKeepCachedValuesWhenDetailsAreMissing() {
        val record = JSONObject().put("appName", "Fig").put("namespace", "ns").put("catalogItemId", "cat")
        val before = EpicGame("Fig", "ns", "cat", "Cached Title", artCover = "c", version = "v1", installSize = 7L)
        val kept = EpicLibrary.gameFrom(record, null, before)
        assertEquals("Cached Title", kept.title)
        assertEquals("v1", kept.version)
        assertEquals(7L, kept.installSize)
        val fresh = EpicLibrary.gameFrom(record, JSONObject().put("title", "New").put("dlc", true).put("canRunOffline", false), before)
        assertEquals("New", fresh.title)
        assertTrue(fresh.isDlc)
        assertFalse(fresh.canRunOffline)
        assertEquals("Fig", EpicLibrary.gameFrom(record, null, null).title)
    }

    @Test fun theCatalogLogoIsKeptInTheCacheAndHandedToTheInstall() {
        val record = JSONObject().put("appName", "Fig").put("namespace", "ns").put("catalogItemId", "cat")
        val fresh = EpicLibrary.gameFrom(record, JSONObject().put("logo", "https://e/logo.png"), null)
        assertEquals("https://e/logo.png", fresh.artLogo)
        assertEquals("https://e/logo.png", EpicLibrary.gameFrom(record, JSONObject().put("title", "Fig"), fresh).artLogo)
        assertEquals(listOf(fresh), EpicLibrary.fromCacheJson(EpicLibrary.toCacheJson(listOf(fresh))))
        assertEquals("https://e/logo.png", EpicLibrary.toCatalogItem(app, fresh).logoUrl)
        val plain = EpicGame("Old", "ns", "c", "Old")
        assertFalse(JSONArray(EpicLibrary.toCacheJson(listOf(plain))).getJSONObject(0).has("artLogo"))
        assertEquals(null, EpicLibrary.toCatalogItem(app, plain).logoUrl)
    }

    @Test fun signOutForgetsOnlyTheLibrary() {
        EpicPrefs.get(app).edit().putString(EpicLibrary.KEY_CACHE, "[]").putLong(EpicLibrary.KEY_SYNCED_AT, 1L)
            .putString("cloud_template_Fig", "{AppData}/x").commit()
        EpicBackend.signOut(app)
        val prefs = EpicPrefs.get(app)
        assertFalse(prefs.contains(EpicLibrary.KEY_CACHE))
        assertFalse(prefs.contains(EpicLibrary.KEY_SYNCED_AT))
        assertTrue(prefs.contains("cloud_template_Fig"))
    }
}
