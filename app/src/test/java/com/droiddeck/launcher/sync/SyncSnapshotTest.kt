package com.droiddeck.launcher.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncSnapshotTest {
    private fun folder(state: String = "idle", global: Long = 100, need: Long = 0, blocked: Boolean = false, paused: Boolean = false, errors: Int = 0) =
        SyncFolder("id", "Saves", "/p", paused, blocked, state, global, need, errors, emptyList())

    private fun snapshot(vararg folders: SyncFolder) = SyncSnapshot("me", folders.toList(), emptyList(), emptyList(), emptyList(), 0, 0)

    @Test
    fun syncingReportsPercentOfActiveFolders() {
        val s = snapshot(folder("syncing", global = 1000, need = 360), folder())
        assertTrue(s.syncing)
        assertEquals(64, s.percent)
        assertEquals(360, s.needBytes)
    }

    @Test
    fun blockedOrFailingFoldersNeedAttention() {
        assertTrue(snapshot(folder(blocked = true, paused = true)).needsAttention)
        assertTrue(snapshot(folder(errors = 2)).needsAttention)
        assertTrue(snapshot(folder("idle", need = 5)).needsAttention)
        assertFalse(snapshot(folder(paused = true, errors = 2)).needsAttention)
    }

    @Test
    fun unreadableFolderStatusShowsAsError() {
        val config = JSONObject("""{"id":"saves","label":"Saves","path":"/p","paused":false,"devices":[{"deviceID":"DEV1"}]}""")
        val f = SyncthingParse.folder(config, SyncthingParse.unreadableStatus(), blocked = false, names = mapOf("DEV1" to "Desktop PC"))
        assertEquals("error", f.state)
        assertTrue(f.errors > 0)
        assertTrue(f.outOfSync)
        assertFalse(f.syncing)
        assertEquals(listOf("Desktop PC"), f.sharedWith)
        assertTrue(snapshot(f).needsAttention)
    }

    @Test
    fun parsesPendingFoldersWithDeviceNames() {
        val json = JSONObject("""{"emu":{"offeredBy":{"DEV1":{"label":"Emulation saves","time":"2026-01-01T00:00:00Z"}}}}""")
        val pending = SyncthingParse.pendingFolders(json, mapOf("DEV1" to "Desktop PC"))
        assertEquals(listOf(SyncPendingFolder("emu", "Emulation saves", "Desktop PC", "DEV1")), pending)
    }
}
