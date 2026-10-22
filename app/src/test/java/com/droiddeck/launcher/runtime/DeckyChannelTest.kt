package com.droiddeck.launcher.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeckyChannelTest {
    private fun release(tag: String, prerelease: Boolean) =
        DeckyManager.Release(tag, prerelease, "PluginLoader-arm64", "https://example.invalid/$tag", null, null, 1, 183)

    @Test fun stableSkipsPrereleasesAndPrereleaseTakesTheNewestTag() {
        val pre = release("v3.4.0-pre1", true)
        val stable = release("v3.3.0", false)
        val channels = DeckyManager.ReleaseChannels(listOf(stable), listOf(pre), listOf(pre, stable))

        assertEquals("v3.3.0", DeckyManager.latest(channels, DeckyManager.BRANCH_STABLE)?.tag)
        assertEquals("v3.4.0-pre1", DeckyManager.latest(channels, DeckyManager.BRANCH_PRERELEASE)?.tag)
    }

    @Test fun prereleaseFollowsAStableThatShippedAfterTheLastPrerelease() {
        val stable = release("v3.3.0", false)
        val pre = release("v3.3.0-pre1", true)
        val channels = DeckyManager.ReleaseChannels(listOf(stable), listOf(pre), listOf(stable, pre))

        assertEquals("v3.3.0", DeckyManager.latest(channels, DeckyManager.BRANCH_PRERELEASE)?.tag)
    }

    @Test fun stableHasNothingWhenOnlyPrereleasesExist() {
        val pre = release("v3.4.0-pre1", true)
        assertNull(DeckyManager.latest(DeckyManager.ReleaseChannels(emptyList(), listOf(pre), listOf(pre)), DeckyManager.BRANCH_STABLE))
    }
}
