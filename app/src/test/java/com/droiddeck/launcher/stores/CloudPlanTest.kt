package com.droiddeck.launcher.stores

import com.droiddeck.launcher.stores.CloudPlan.Entry
import com.droiddeck.launcher.stores.CloudPlan.Local
import com.droiddeck.launcher.stores.CloudPlan.Remote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The rules that keep a cloud sync from replacing real saves. (Robolectric for org.json.) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CloudPlanTest {
    @Test fun noUploadBeforeADownloadHasSetTheBaseline() {
        val plan = CloudPlan.up(mapOf("slot0.sav" to Local("new")), mapOf("slot0.sav" to Remote("real", 1000)), null)
        assertEquals("no-baseline", plan.refused)
        assertTrue(plan.transfer.isEmpty())
    }

    @Test fun theFirstDownloadLetsTheCloudWin() {
        val plan = CloudPlan.down(
            mapOf("slot0.sav" to Local("fresh-profile"), "only-local.cfg" to Local("x")),
            mapOf("slot0.sav" to Remote("real", 1000), "slot1.sav" to Remote("other", 1000)),
            null,
        )
        assertEquals(setOf("slot0.sav", "slot1.sav"), plan.transfer.toSet())
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test fun aFileChangedOnBothSidesIsAConflictEitherWay() {
        val base = mapOf("slot0.sav" to Entry(localMd5 = "a", cloudMd5 = "a", cloudModified = 1000))
        val local = mapOf("slot0.sav" to Local("b"))
        val remote = mapOf("slot0.sav" to Remote("c", 5000))
        assertEquals(listOf("slot0.sav"), CloudPlan.down(local, remote, base).conflicts)
        assertTrue(CloudPlan.down(local, remote, base).transfer.isEmpty())
        assertEquals(listOf("slot0.sav"), CloudPlan.up(local, remote, base).conflicts)
        assertTrue(CloudPlan.up(local, remote, base).transfer.isEmpty())
    }

    @Test fun theSideThatChangedWins() {
        val base = mapOf("s" to Entry("a", "a", 1000))
        // Only local changed: up sends it, down leaves it.
        assertEquals(listOf("s"), CloudPlan.up(mapOf("s" to Local("b")), mapOf("s" to Remote("a", 1000)), base).transfer)
        assertTrue(CloudPlan.down(mapOf("s" to Local("b")), mapOf("s" to Remote("a", 1000)), base).transfer.isEmpty())
        // Only the cloud changed: down takes it, up leaves it.
        assertEquals(listOf("s"), CloudPlan.down(mapOf("s" to Local("a")), mapOf("s" to Remote("c", 9000)), base).transfer)
        assertTrue(CloudPlan.up(mapOf("s" to Local("a")), mapOf("s" to Remote("c", 9000)), base).transfer.isEmpty())
        // The same bytes on both sides: nothing moves.
        assertEquals(listOf("s"), CloudPlan.up(mapOf("s" to Local("c")), mapOf("s" to Remote("c", 9000)), base).same)
    }

    @Test fun timesAloneDecideWhenTheServiceGivesNoHash() {
        val base = mapOf("s" to Entry("a", null, 1000))
        assertEquals(listOf("s"), CloudPlan.down(mapOf("s" to Local("a")), mapOf("s" to Remote(null, 60_000)), base).transfer)
        // Within two seconds of the baseline: the same cloud copy.
        assertTrue(CloudPlan.down(mapOf("s" to Local("a")), mapOf("s" to Remote(null, 2500)), base).transfer.isEmpty())
    }

    @Test fun theBaselineSurvivesItsJson() {
        val b = mapOf("a/b.sav" to Entry("l", "c", 5), "x" to Entry("l2", null, -1))
        assertEquals(b, CloudPlan.fromJson(CloudPlan.toJson(b)))
    }
}
