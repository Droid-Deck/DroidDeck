package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.frontend.AddedGameArt.Slot
import com.droiddeck.launcher.session.SessionPrefs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AddedGameArtFillTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()

    private val coverBytes = byteArrayOf(9, 9, 9)

    private fun game(name: String, appId: Long, cover: Boolean = true, source: String = Library.ADDED): AddedGames.Game {
        val dir = tmp.newFolder(name)
        File(dir, "steam_appid.txt").writeText("504230")
        if (cover) File(dir, "cover.jpg").writeBytes(coverBytes)
        val exe = File(dir, "$name.exe").apply { writeText("x") }
        return AddedGames.Game(dir, name, exe, "/g/$name.exe", "/g", appId, appId, emptyList(), source = source)
    }

    private class Calls {
        val steam = ArrayList<Set<Slot>>()
        val sgdb = ArrayList<Set<Slot>>()
        var lookups = 0
        var store = 0
    }

    private val steamNames = mapOf(Slot.COVER to "p.jpg", Slot.WIDE to "header.jpg", Slot.BACKGROUND to "hero.jpg", Slot.LOGO to "logo.png")

    private fun sources(
        calls: Calls, steamHas: Set<Slot> = emptySet(), sgdbHas: Set<Slot> = emptySet(),
        storeHas: Boolean = false, sgdbFails: Boolean = false,
    ) = AddedGameArt.Sources(
        storeLogo = { g ->
            calls.store++
            if (storeHas) File(g.folder, "logo.png").writeBytes(byteArrayOf(4))
            storeHas
        },
        steamSearch = { fail("searched although the game names its appid"); null },
        steam = { id, cache, slots ->
            assertEquals("504230", id)
            calls.steam += slots
            (slots intersect steamHas).onEach { File(cache, steamNames.getValue(it)).writeBytes(byteArrayOf(1)) }.isNotEmpty()
        },
        sgdbGame = { _, steamId, _ ->
            calls.lookups++
            assertEquals(504230, steamId)
            77
        },
        sgdb = { _, id, cache, slots ->
            assertEquals(77, id)
            if (sgdbFails) throw SteamGridDb.Failed(SteamGridDb.Status.OFFLINE)
            calls.sgdb += slots
            (slots intersect sgdbHas).onEach { File(cache, SteamGridDb.cacheName(it)).writeBytes(byteArrayOf(2)) }.isNotEmpty()
        },
    )

    @Test fun aGameWithItsOwnCoverStillGetsItsLogoAndIcon() {
        val g = game("Celeste", 0x81000001L)
        val calls = Calls()
        assertTrue(AddedGameArt.fetchMissing(app, listOf(g), "key", sources(calls, steamHas = setOf(Slot.LOGO), sgdbHas = setOf(Slot.ICON))))
        assertEquals(listOf(setOf(Slot.LOGO)), calls.steam)
        assertEquals(listOf(setOf(Slot.ICON)), calls.sgdb)
        assertEquals(0, calls.store)
        val cache = AddedGameArt.cacheDir(app, g)
        assertFalse(File(cache, "p.jpg").exists())
        assertArrayEquals(coverBytes, File(g.folder, "cover.jpg").readBytes())
        val art = AddedGameArt.resolve(app, g)
        assertEquals(File(g.folder, "cover.jpg"), art.portrait)
        assertEquals(File(cache, "logo.png"), art.logo)
        assertEquals(File(cache, "icon-sgdb.png"), art.icon)
        assertFalse(File(cache, AddedGameArt.EXTRAS_TRIED).exists())
    }

    @Test fun filledOrChosenSlotsAreNeverAskedFor() {
        val g = game("Hades", 0x81000002L)
        File(g.folder, "logo.png").writeBytes(byteArrayOf(5))
        val picked = File(tmp.root, "mine.png").apply { writeBytes(byteArrayOf(6)) }
        assertTrue(AddedGameArt.store(AddedGameArt.cacheDir(app, g), Slot.ICON, AddedGameArt.Option(AddedGameArt.FILE, picked.path, picked.path)))
        val calls = Calls()
        assertFalse(AddedGameArt.fetchMissing(app, listOf(g), "key", sources(calls, steamHas = Slot.values().toSet(), sgdbHas = Slot.values().toSet())))
        assertTrue(calls.steam.isEmpty() && calls.sgdb.isEmpty() && calls.lookups == 0)
        assertArrayEquals(byteArrayOf(5), File(g.folder, "logo.png").readBytes())
    }

    @Test fun withoutAKeyAMissingIconAloneAsksNothing() {
        val g = game("Inside", 0x81000003L)
        File(g.folder, "logo.png").writeBytes(byteArrayOf(5))
        val calls = Calls()
        assertFalse(AddedGameArt.fetchMissing(app, listOf(g), "", sources(calls)))
        assertTrue(calls.steam.isEmpty() && calls.sgdb.isEmpty() && calls.lookups == 0)
        assertFalse(AddedGameArt.cacheDir(app, g).exists())
    }

    @Test fun aMissIsAskedAgainOnlyAfterAWeek() {
        val g = game("Limbo", 0x81000004L)
        val calls = Calls()
        assertFalse(AddedGameArt.fetchMissing(app, listOf(g), "key", sources(calls)))
        assertEquals(listOf(setOf(Slot.LOGO)), calls.steam)
        assertEquals(listOf(setOf(Slot.LOGO, Slot.ICON)), calls.sgdb)
        AddedGameArt.fetchMissing(app, listOf(g), "key", sources(calls))
        assertEquals(1, calls.steam.size)
        assertEquals(1, calls.sgdb.size)
        val tried = File(AddedGameArt.cacheDir(app, g), AddedGameArt.EXTRAS_TRIED)
        assertTrue(tried.setLastModified(System.currentTimeMillis() - 8L * 24 * 3600 * 1000))
        assertTrue(AddedGameArt.fetchMissing(app, listOf(g), "key", sources(calls, steamHas = setOf(Slot.LOGO), sgdbHas = setOf(Slot.ICON))))
        assertEquals(2, calls.steam.size)
        assertEquals(setOf(Slot.ICON), calls.sgdb.last())
        assertFalse(tried.exists())
    }

    @Test fun theStoresOwnLogoComesFirst() {
        val g = game("Metalstorm", 0x81000005L, source = "gog")
        val calls = Calls()
        assertTrue(AddedGameArt.fetchMissing(app, listOf(g), "key", sources(calls, storeHas = true, steamHas = setOf(Slot.LOGO), sgdbHas = setOf(Slot.ICON))))
        assertEquals(1, calls.store)
        assertTrue(calls.steam.isEmpty())
        assertEquals(listOf(setOf(Slot.ICON)), calls.sgdb)
        assertEquals(File(g.folder, "logo.png"), AddedGameArt.resolve(app, g).logo)
    }

    @Test fun aGameWithoutACoverStillGetsEverySlot() {
        val g = game("Tunic", 0x81000006L, cover = false)
        val calls = Calls()
        assertTrue(AddedGameArt.fetchMissing(app, listOf(g), "key",
            sources(calls, steamHas = setOf(Slot.COVER, Slot.WIDE, Slot.BACKGROUND), sgdbHas = setOf(Slot.LOGO, Slot.ICON))))
        assertEquals(listOf(setOf(Slot.COVER, Slot.WIDE, Slot.BACKGROUND, Slot.LOGO)), calls.steam)
        assertEquals(listOf(setOf(Slot.LOGO, Slot.ICON)), calls.sgdb)
        assertTrue(AddedGameArt.missing(app, g).isEmpty())
    }

    @Test fun aSteamGridDbFailureIsNotRememberedAndStopsThePass() {
        val first = game("Gris", 0x81000007L)
        val second = game("Hollow", 0x81000008L)
        val calls = Calls()
        AddedGameArt.fetchMissing(app, listOf(first, second), "key", sources(calls, sgdbFails = true))
        assertEquals(2, calls.steam.size)
        assertEquals(1, calls.lookups)
        assertFalse(File(AddedGameArt.cacheDir(app, first), AddedGameArt.EXTRAS_TRIED).exists())
        assertFalse(File(AddedGameArt.cacheDir(app, second), AddedGameArt.EXTRAS_TRIED).exists())
    }

    @Test fun nothingIsFetchedWhenArtIsOff() {
        SessionPrefs.setAddedGamesArt(app, false)
        val calls = Calls()
        assertFalse(AddedGameArt.fetchMissing(app, listOf(game("Off", 0x81000009L)), "key", sources(calls)))
        assertTrue(calls.steam.isEmpty())
    }
}
