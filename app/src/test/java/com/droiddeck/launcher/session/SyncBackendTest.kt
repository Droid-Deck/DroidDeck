package com.droiddeck.launcher.session

import org.robolectric.RuntimeEnvironment
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncBackendTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun aFreshInstallStartsOnFsync() {
        assertEquals(SessionPrefs.SYNC_FSYNC, SessionPrefs.syncBackend(context))
    }

    @Test
    fun eachChoiceIsKeptAndSetsTheSwitchesTogether() {
        SessionPrefs.setSyncBackend(context, SessionPrefs.SYNC_NTSYNC)
        assertEquals(SessionPrefs.SYNC_NTSYNC, SessionPrefs.syncBackend(context))
        assertTrue(SessionPrefs.fastSync(context))
        SessionPrefs.setSyncBackend(context, SessionPrefs.SYNC_FSYNC)
        assertEquals(SessionPrefs.SYNC_FSYNC, SessionPrefs.syncBackend(context))
        assertEquals(false, SessionPrefs.fastSync(context))
        assertTrue(SessionPrefs.fsyncFirst(context))
        SessionPrefs.setSyncBackend(context, SessionPrefs.SYNC_ESYNC)
        assertEquals(SessionPrefs.SYNC_ESYNC, SessionPrefs.syncBackend(context))
        assertEquals(false, SessionPrefs.fsyncFirst(context))
    }

    @Test
    fun pickingATabTurnsSyncBackOn() {
        SessionPrefs.setSyncFallback(context, false)
        SessionPrefs.setFsyncFirst(context, false)
        assertEquals(SessionPrefs.SYNC_WINESERVER, SessionPrefs.syncBackend(context))
        SessionPrefs.setSyncBackend(context, SessionPrefs.SYNC_FSYNC)
        assertTrue(SessionPrefs.syncFallback(context))
        assertEquals(SessionPrefs.SYNC_FSYNC, SessionPrefs.syncBackend(context))
    }

    @Test
    fun wineserverTurnsEverySyncOffAndBackOn() {
        SessionPrefs.setSyncBackend(context, SessionPrefs.SYNC_WINESERVER)
        assertEquals(SessionPrefs.SYNC_WINESERVER, SessionPrefs.syncBackend(context))
        assertEquals(false, SessionPrefs.fastSync(context))
        assertEquals(false, SessionPrefs.fsyncFirst(context))
        assertEquals(false, SessionPrefs.syncFallback(context))
        SessionPrefs.setSyncBackend(context, SessionPrefs.SYNC_ESYNC)
        assertTrue(SessionPrefs.syncFallback(context))
        assertEquals(SessionPrefs.SYNC_ESYNC, SessionPrefs.syncBackend(context))
    }

    @Test
    fun ntsyncWinsOverFsyncFirstAsTheLauncherDoes() {
        assertEquals(SessionPrefs.SYNC_NTSYNC, SessionPrefs.syncBackendOf(fastSync = true, fsyncFirst = true, syncFallback = true))
        assertEquals(SessionPrefs.SYNC_FSYNC, SessionPrefs.syncBackendOf(fastSync = false, fsyncFirst = true, syncFallback = false))
        assertEquals(SessionPrefs.SYNC_WINESERVER, SessionPrefs.syncBackendOf(fastSync = false, fsyncFirst = false, syncFallback = false))
    }
}
