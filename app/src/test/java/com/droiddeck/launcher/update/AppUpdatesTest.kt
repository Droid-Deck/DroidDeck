package com.droiddeck.launcher.update

import com.droiddeck.launcher.update.AppUpdates.Channel
import com.droiddeck.launcher.update.AppUpdates.Follow
import com.droiddeck.launcher.update.AppUpdates.Installed
import com.droiddeck.launcher.update.AppUpdates.Offer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatesTest {
    private fun release(commit: String, pr: Int = 0, version: String? = null) =
        AppUpdates.Release("t", "title", "", commit, pr, version, 0L, "", null)

    private val stable = release("aaaaaaa1111111111111111111111111111111", version = "0.2.0")
    private val catalog = AppUpdates.Catalog(
        stable = stable,
        nightly = release("bbbbbbb"),
        tests = listOf(release("ccccccc2222222222222222222222222222222", pr = 93)),
        checkedAt = 0L,
    )

    private fun me(commit: String, pr: Int = 0, version: String = "0.2.0") = Installed(commit, pr, version, ci = true)

    @Test
    fun theStableBuildIsCurrentOnStable() {
        assertEquals(Offer.CURRENT, AppUpdates.offer(catalog, Follow(Channel.STABLE), me(stable.commit)))
    }

    @Test
    fun aNightlyPastStableIsAheadNotBehind() {
        assertEquals(Offer.AHEAD, AppUpdates.offer(catalog, Follow(Channel.STABLE), me("ddddddd999")))
    }

    @Test
    fun aNewerStableVersionIsAnUpdate() {
        assertEquals(Offer.UPDATE, AppUpdates.offer(catalog, Follow(Channel.STABLE), me("eeeeeee999", version = "0.1.9")))
    }

    @Test
    fun nightlyMatchesItsShortShaAndOffersTheNewest() {
        assertEquals(Offer.CURRENT, AppUpdates.offer(catalog, Follow(Channel.NIGHTLY), me("bbbbbbb0123456789")))
        assertEquals(Offer.UPDATE, AppUpdates.offer(catalog, Follow(Channel.NIGHTLY), me(stable.commit)))
    }

    @Test
    fun testBuildsUpdateWithinTheirPrAndSwitchOtherwise() {
        assertEquals(Offer.UPDATE, AppUpdates.offer(catalog, Follow(Channel.TEST, 93), me("fffffff000", pr = 93)))
        assertEquals(Offer.SWITCH, AppUpdates.offer(catalog, Follow(Channel.TEST, 93), me(stable.commit)))
        assertEquals(Offer.SWITCH, AppUpdates.offer(catalog, Follow(Channel.NIGHTLY), me("ccccccc2", pr = 93)))
        assertEquals(Offer.GONE, AppUpdates.offer(catalog, Follow(Channel.TEST, 12), me("ccccccc2", pr = 12)))
    }

    @Test
    fun aTestBuildIsNeverTheMainBuildOfTheSameCommit() {
        assertEquals(Offer.SWITCH, AppUpdates.offer(catalog, Follow(Channel.NIGHTLY), me("bbbbbbb", pr = 5)))
    }

    @Test
    fun versionsCompareByNumberNotText() {
        assertTrue(AppUpdates.compareVersions("0.10.0", "0.9.2") > 0)
        assertTrue(AppUpdates.compareVersions("v0.2.1", "0.2") > 0)
        assertEquals(0, AppUpdates.compareVersions("0.2", "0.2.0"))
    }

    @Test
    fun ciNotesGiveTheChangeAndItsSummary() {
        val (title, summary) = AppUpdates.describeCi(
            "main 8a61a18",
            "**Merged to main** - [PR #95](https://github.com/Droid-Deck/DroidDeck/pull/95): fix: keep `Steam` alive (merged 2026-09-30)\n\n" +
                "Steam no longer **exits** on resume.\n\nSigned build of https://github.com/x - in main.",
        )
        assertEquals("fix: keep Steam alive", title)
        assertEquals("Steam no longer exits on resume.", summary)
        val (prTitle, prSummary) = AppUpdates.describeCi(
            "PR #93",
            "**Not merged** - a test build of open [PR #93](https://github.com/Droid-Deck/DroidDeck/pull/93): fix: report Adreno 840v2\n\n" +
                "Source: https://github.com/Droid-Deck/DroidDeck/pull/93\nCommit: 93382ff",
        )
        assertEquals("fix: report Adreno 840v2", prTitle)
        assertEquals("", prSummary)
    }
}
