package com.droiddeck.launcher.ui

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BottomGuidesPageTest {
    @Test
    fun theReaderStaysOnTheSiteUnlessTheUserFollowsALink() {
        val start = "https://eldenring.fandom.com/"
        assertTrue(readerAllows(start, Uri.parse("https://eldenring.fandom.com/wiki/Margit"), false))
        assertTrue(readerAllows(start, Uri.parse("https://community.fandom.com/"), false))
        assertFalse(readerAllows(start, Uri.parse("https://ads.example.com/landing"), false))
        assertTrue(readerAllows(start, Uri.parse("https://mapgenie.io/elden-ring"), true))
        assertTrue(readerAllows(start, Uri.parse("https://steamcommunity.com/app/1"), false))
        assertFalse(readerAllows(start, Uri.parse("intent://scan/#Intent;end"), true))
        assertFalse(readerAllows(start, Uri.parse("javascript:alert(1)"), true))
    }

    @Test
    fun adHostsAreBlockedBySuffix() {
        assertTrue(ReaderAds.blocks("securepubads.g.doubleclick.net"))
        assertTrue(ReaderAds.blocks("c.amazon-adsystem.com"))
        assertFalse(ReaderAds.blocks("strategywiki.org"))
        assertFalse(ReaderAds.blocks("static.wikia.nocookie.net"))
        assertFalse(ReaderAds.blocks("net"))
        assertFalse(ReaderAds.blocks(null))
    }

    @Test
    fun bigMapsAreDecodedAtAManageableSize() {
        assertEquals(1, MapMarkers.sampleSize(4000, 3000, 4096))
        assertEquals(2, MapMarkers.sampleSize(9000, 6000, 4096))
        assertEquals(4, MapMarkers.sampleSize(20000, 12000, 4096))
    }

    @Test
    fun mirrorHideCyclesAndDefaultsToShown() {
        assertEquals(MirrorHide.OFF, MirrorHide.from(null))
        assertEquals(MirrorHide.DIM, MirrorHide.from("dim"))
        assertEquals(MirrorHide.BLACK, MirrorHide.OFF.next())
        assertEquals(MirrorHide.OFF, MirrorHide.DIM.next())
    }
}
