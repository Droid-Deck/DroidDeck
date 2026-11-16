package com.droiddeck.launcher.store

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StoreStateTest {
    private fun snapshot(vararg data: String) = StoreState.Snapshot(true, emptyList(), data.toSet(), true)

    @Test fun anOlderSnapshotNeverOverwritesANewerOne() {
        val older = StoreState.nextSnapshot()
        val newer = StoreState.nextSnapshot()
        StoreState.publish(newer, snapshot("org.example.App"))
        StoreState.publish(older, snapshot())
        assertEquals(setOf("org.example.App"), StoreState.cardData)
    }

    @Test fun aSnapshotSupersededBeforeItLandsIsDropped() {
        val first = StoreState.nextSnapshot()
        StoreState.nextSnapshot()
        StoreState.publish(first, snapshot("org.example.Late"))
        assertFalse("org.example.Late" in StoreState.cardData)
    }
}
