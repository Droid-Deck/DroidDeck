package com.droiddeck.launcher.frontend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SoundtrackTest {
    private fun raw(
        folder: String, name: String, disc: Int? = null, track: Int? = null, title: String? = null, size: Long = 1000L,
    ) = Soundtrack.Raw(File("/music/album/${if (folder.isEmpty()) "" else "$folder/"}$name"), folder, disc, track, title, null, 60_000L, size)

    @Test fun titlesDropArtistAlbumAndNumber() {
        assertEquals("Once Upon a Time", Soundtrack.cleanTitle("toby fox - UNDERTALE Soundtrack - 01 Once Upon a Time.mp3"))
        assertEquals("ANOTHER HIM", Soundtrack.cleanTitle("001 ANOTHER HIM.flac"))
        assertEquals("Chapter 5 Logo", Soundtrack.cleanTitle("166 Chapter 5 Logo.mp3"))
        assertEquals("Query-", Soundtrack.cleanTitle("094 Query-.mp3"))
        assertEquals("Ruins", Soundtrack.cleanTitle("Ruins.ogg"))
    }

    @Test fun flatAlbumIsOneDiscOrderedByNumber() {
        val disc = Soundtrack.group(
            listOf(raw("", "toby fox - X - 10 Ten.mp3"), raw("", "toby fox - X - 02 Two.mp3"), raw("", "toby fox - X - 01 One.mp3")),
        )
        assertEquals(listOf(0), disc.keys.toList())
        assertEquals(listOf("One", "Two", "Ten"), disc.getValue(0).map { it.title })
        assertEquals(listOf(1, 2, 3), disc.getValue(0).map { it.number })
    }

    @Test fun subfoldersBecomeDiscs() {
        val disc = Soundtrack.group(
            listOf(raw("2", "041 B.mp3"), raw("1", "001 A.mp3"), raw("10", "200 C.mp3"), raw("1", "002 A2.mp3")),
        )
        assertEquals(listOf(1, 2, 3), disc.keys.toList())
        assertEquals(listOf("A", "A2"), disc.getValue(1).map { it.title })
        assertEquals(listOf("B"), disc.getValue(2).map { it.title })
        assertEquals(listOf("C"), disc.getValue(3).map { it.title })
    }

    @Test fun losslessCopyReplacesLossyDuplicateAndKeepsItsFolder() {
        val disc = Soundtrack.group(
            listOf(
                raw("", "001 ANOTHER HIM.flac", size = 4_130_000L),
                raw("", "002 Beginning.flac", size = 4_870_000L),
                raw("1", "001 ANOTHER HIM.mp3", size = 1_940_000L),
                raw("1", "002 Beginning.mp3", size = 2_320_000L),
                raw("2", "041 Faint Glow.mp3"),
            ),
        )
        assertEquals(listOf(1, 2), disc.keys.toList())
        assertEquals(listOf("flac", "flac"), disc.getValue(1).map { it.file.extension })
        assertEquals(listOf("ANOTHER HIM", "Beginning"), disc.getValue(1).map { it.title })
        assertEquals(listOf("Faint Glow"), disc.getValue(2).map { it.title })
    }

    @Test fun tagsDecideDiscsAndFileNamesDecideOrder() {
        val disc = Soundtrack.group(
            listOf(
                raw("3+4", "088 Late.mp3", disc = 4, track = 1, title = "Old wooden rafters"),
                raw("3+4", "001 Early.mp3", disc = 3, track = 2, title = "Second"),
                raw("3+4", "002 Early.mp3", disc = 3, track = 1, title = "First"),
            ),
        )
        assertEquals(listOf(3, 4), disc.keys.toList())
        assertEquals(listOf("Second", "First"), disc.getValue(3).map { it.title })
        assertEquals("Old wooden rafters", disc.getValue(4).single().title)
    }

    @Test fun sameTagsCollapseDuplicates() {
        val disc = Soundtrack.group(
            listOf(
                raw("", "a.flac", disc = 1, track = 1, title = "Song", size = 5000L),
                raw("1", "a.mp3", disc = 1, track = 1, title = "Song", size = 900L),
            ),
        )
        assertEquals(1, disc.getValue(1).size)
        assertEquals("flac", disc.getValue(1).single().file.extension)
    }

    @Test fun renamedPunctuationStillMatchesAcrossFormats() {
        val disc = Soundtrack.group(
            listOf(
                raw("", "094 Query?.flac", size = 5000L),
                raw("3+4", "094 Query-.mp3", size = 900L),
                raw("3+4", "095 Quiz!.mp3", size = 900L),
            ),
        )
        assertEquals(2, disc.getValue(0).size + disc.filterKeys { it != 0 }.values.sumOf { it.size })
        assertEquals(listOf("flac", "mp3"), disc.values.flatten().map { it.file.extension })
    }

    @Test fun sameTitleWithDifferentNumbersStaysSeparate() {
        val disc = Soundtrack.group(listOf(raw("", "01 Fanfare.mp3"), raw("", "07 Fanfare.mp3")))
        assertEquals(2, disc.values.flatten().size)
    }

    @Test fun folderDiscsKeepTheirFolderNameAsLabel() {
        val disc = Soundtrack.group(
            listOf(raw("3+4", "088 A.mp3", track = 1), raw("3+4", "165 B.mp3", track = 78), raw("5", "166 C.mp3", track = 1), raw("Bonus", "300 D.mp3")),
        )
        assertEquals(listOf("3+4", "5", "3"), disc.values.map { it.first().discLabel }.let { it.take(2) + disc.getValue(3).first().discLabel })
        assertEquals(listOf("A", "B"), disc.getValue(1).map { it.title })
    }

    @Test fun tagsFromTheDroppedDuplicateFillTheKeptOne() {
        val disc = Soundtrack.group(
            listOf(
                raw("", "165 Concert for you.flac", size = 90_000L),
                raw("3+4", "165 Concert for you.mp3", track = 78, title = "Concert for you", size = 98_000L),
                raw("", "088 Flashback (Excerpt).flac", size = 4_800_000L),
                raw("3+4", "088 Flashback (Excerpt).mp3", track = 1, title = "Flashback (Excerpt)", size = 1_280_000L),
            ),
        )
        val tracks = disc.values.flatten()
        assertEquals(listOf("Flashback (Excerpt)", "Concert for you"), tracks.map { it.title })
        assertEquals(listOf("flac", "flac"), tracks.map { it.file.extension })
    }

    @Test fun emptyInputGivesNothing() {
        assertTrue(Soundtrack.group(emptyList()).isEmpty())
    }

    @Test fun lengthFormatting() {
        assertEquals("0:48", Soundtrack.formatLength(48_000L))
        assertEquals("1:20", Soundtrack.formatLength(80_000L))
        assertEquals("4:15:37", Soundtrack.formatLength((4 * 3600 + 15 * 60 + 37) * 1000L))
    }

    @Test fun shortcutLinksRoundTrip() {
        assertEquals(391540, SoundtrackShortcuts.parse(SoundtrackShortcuts.uri(391540)))
        assertEquals(-5, SoundtrackShortcuts.parse(SoundtrackShortcuts.uri(-5)))
        assertNull(SoundtrackShortcuts.parse("droiddeck://game/12"))
        assertNull(SoundtrackShortcuts.parse(null))
    }

    @Test fun addedSoundtracksNeverCollideWithSteamIds() {
        assertTrue(AddedSoundtracks.idOf("/storage/emulated/0/Music/Album") < 0)
    }
}
