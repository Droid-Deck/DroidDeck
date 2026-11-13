package com.droiddeck.launcher.frontend

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SteamCollectionTest {
    @Test fun matchingAccountsCollectionCanBeReadOffline() {
        val snapshot = JSONObject("""{"account":"76561197960265851","games":[{"id":620,"name":"Portal 2"}]}""")
        assertEquals("Portal 2", SteamCollection.readSnapshot(snapshot, "123", File("/missing-steam-root")).single().name)
    }

    @Test(expected = IllegalStateException::class)
    fun anotherAccountsCollectionIsRejected() {
        SteamCollection.readSnapshot(JSONObject("""{"account":"76561197960266184","games":[]}"""), "123", File("/missing-steam-root"))
    }

    @Test fun cachedCollectionKeepsOnlyValidUniqueGamesAndDoesNotInventInstalls() {
        val entries = JSONArray("""[
            {"id":440,"name":"Team Fortress 2","lastPlayed":123},
            {"id":440,"name":"Duplicate"},
            {"id":0,"name":"Invalid"},
            {"id":570,"name":" "},
            {"id":620,"name":"Portal 2"}
        ]""")
        val games = SteamCollection.read(entries, File("/missing-steam-root"))
        assertEquals(listOf(440, 620), games.map { it.appId })
        assertEquals(123L, games.first().lastPlayed)
        assertEquals(Library.STEAM_OWNED, games.first().library)
        assertNull(games.first().gameFiles)
        assertNull(games.first().protonPrefix)
    }
}
