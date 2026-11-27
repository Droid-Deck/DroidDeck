package com.droiddeck.launcher.stores

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SteamLiveShortcutsSyncTest {
    @Test fun everyGameKnownOrAddedIsDelivered() {
        assertTrue(SteamLiveShortcuts.synced("""{"added":2,"missed":0}"""))
        assertTrue(SteamLiveShortcuts.synced("""{"added":0,"missed":0}"""))
    }

    @Test fun aGameTheClientRefusedIsNotDelivered() {
        assertFalse(SteamLiveShortcuts.synced("""{"added":1,"missed":1}"""))
        assertFalse(SteamLiveShortcuts.synced("""{"added":0,"missed":3}"""))
    }

    @Test fun noAnswerIsNotDelivered() {
        assertFalse(SteamLiveShortcuts.synced(null))
        assertFalse(SteamLiveShortcuts.synced("1"))
    }
}
