package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.CloudSaves
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CloudMessageTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun msg(result: String, files: Int, conflicts: Int, reason: String) =
        cloudMessage(ctx, CloudSaves.Result(result, files, 0L, reason, conflicts))

    @Test fun aSkippedSyncNeverReadsAsZeroFilesDone() {
        assertEquals(ctx.getString(R.string.cloud_signed_out), msg("skipped", 0, 0, "not-signed-in"))
        assertEquals(ctx.getString(R.string.cloud_none), msg("skipped", 0, 0, "no-cloud-saves"))
        for (reason in listOf("off", "no-baseline", "nothing-local", "store-unreachable", "unresolved")) {
            assertEquals(reason, ctx.getString(R.string.cloud_not_synced), msg("skipped", 0, 0, reason))
        }
        assertEquals(ctx.getString(R.string.cloud_not_synced), msg("failed", 0, 0, "SocketTimeoutException"))
    }

    @Test fun okResultsSayWhatHappened() {
        assertEquals(ctx.getString(R.string.cloud_up_to_date), msg("ok", 0, 0, "up-to-date"))
        assertEquals(ctx.getString(R.string.cloud_done, 3), msg("ok", 3, 0, "uploaded"))
        assertEquals(ctx.getString(R.string.cloud_conflict_files, 2), msg("ok", 1, 2, "conflict"))
    }
}
