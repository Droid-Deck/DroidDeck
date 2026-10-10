package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.stores.CredentialCipher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

/** Where an added game's art comes from: its own Steam appid, a matching name, SteamGridDB's key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AddedGameArtLookupTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()

    @After fun tearDown() {
        SteamGridDb.setUserKey(app, "")
        SteamGridDb.keys = CredentialCipher.Keystore
    }

    @Test fun anAppidFromTheGamesFilesIsUsedWithoutASearch() {
        val lookup = File(tmp.newFolder(), "steam-appid")
        val id = AddedGameArt.steamAppId("Palworld", 1623730, lookup) { fail("searched"); null }
        assertEquals("1623730", id)
        assertEquals("1623730", lookup.readText())
        // An earlier "none" from a search does not stand in its way.
        lookup.writeText("none")
        assertEquals("1623730", AddedGameArt.steamAppId("Palworld", 1623730, lookup) { fail("searched"); null })
    }

    @Test fun withoutAnAppidTheTitleIsSearchedOnceAWeek() {
        val lookup = File(tmp.newFolder(), "steam-appid")
        var searches = 0
        assertNull(AddedGameArt.steamAppId("Nothing Like It", null, lookup) { searches++; null })
        assertEquals("none", lookup.readText())
        assertNull(AddedGameArt.steamAppId("Nothing Like It", null, lookup) { searches++; "1" })
        assertEquals(1, searches)
        lookup.setLastModified(System.currentTimeMillis() - 8L * 24 * 3600 * 1000)
        assertEquals("42", AddedGameArt.steamAppId("Nothing Like It", null, lookup) { searches++; "42" })
        assertEquals(2, searches)
    }

    @Test fun aStoreResultIsTakenOnlyWhenItsNameMatches() {
        // The first result is no longer taken for being first.
        assertNull(AddedGameArt.pickStoreResult("My Obscure Game", listOf("10" to "Totally Different", "11" to "Another One")))
        assertEquals("20", AddedGameArt.pickStoreResult("Hades", listOf("19" to "Hades II Soundtrack Bundle Extra", "20" to "Hades")))
        assertEquals("30", AddedGameArt.pickStoreResult("Skyrim Special Edition Complete Edition", listOf("1" to "Unrelated", "30" to "Skyrim Special Edition")))
        assertEquals("40", AddedGameArt.pickStoreResult("Portal 2", listOf("40" to "Portal 2 - Perpetual Testing")))
        assertNull(AddedGameArt.pickStoreResult("", listOf("1" to "Anything")))
    }

    @Test fun aSteamGridDbSearchResultIsTakenOnlyWhenItsNameMatches() {
        assertNull(SteamGridDb.match("My Obscure Game", listOf(5 to "Something Else")))
        assertEquals(6, SteamGridDb.match("The Witcher 3 - Wild Hunt", listOf(5 to "Witcher", 6 to "The Witcher 3: Wild Hunt")))
    }

    @Test fun theBestScoredImageWinsAndAPngSlotTakesOnlyPng() {
        val items = JSONArray()
            .put(JSONObject().put("url", "https://cdn2.steamgriddb.com/a.png").put("mime", "image/png").put("score", 2))
            .put(JSONObject().put("url", "https://cdn2.steamgriddb.com/b.jpg").put("mime", "image/jpeg").put("score", 9))
        assertEquals("https://cdn2.steamgriddb.com/b.jpg", SteamGridDb.best(items, pngOnly = false))
        assertEquals("https://cdn2.steamgriddb.com/a.png", SteamGridDb.best(items, pngOnly = true))
        assertNull(SteamGridDb.best(JSONArray(), pngOnly = false))
    }

    @Test fun theUsersKeyWinsOverTheBuildsAndNoneMeansNone() {
        assertEquals("user", SteamGridDb.pick("user", "build"))
        assertEquals("build", SteamGridDb.pick("", "build"))
        assertEquals("build", SteamGridDb.pick("  ", "build"))
        assertEquals("", SteamGridDb.pick("", ""))
        // This test build carries no key.
        assertEquals("", SteamGridDb.builtInKey())
        assertEquals("", SteamGridDb.key(app))
    }

    @Test fun theUsersKeyIsSealedOnDiskAndClears() {
        val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        SteamGridDb.keys = CredentialCipher.KeyProvider { key }
        SteamGridDb.setUserKey(app, "abcdef0123456789secretkey")
        val file = File(app.filesDir, "sgdb/key.json")
        assertFalse(file.readText().contains("secretkey"))
        assertTrue(CredentialCipher.isEnvelope(JSONObject(file.readText())))
        assertEquals("abcdef0123456789secretkey", SteamGridDb.key(app))
        SteamGridDb.setUserKey(app, "")
        assertFalse(file.exists())
        assertEquals("", SteamGridDb.key(app))
    }
}
