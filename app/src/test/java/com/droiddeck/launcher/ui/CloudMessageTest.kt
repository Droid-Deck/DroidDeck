package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.CloudSaves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        for (reason in listOf("off", "no-baseline", "not-installed", "nothing-local", "store-unreachable", "unresolved")) {
            assertEquals(reason, ctx.getString(R.string.cloud_not_synced), msg("skipped", 0, 0, reason))
        }
        assertEquals(ctx.getString(R.string.cloud_not_synced), msg("failed", 0, 0, "SocketTimeoutException"))
    }

    @Test fun aBusySyncExplainsItself() {
        assertEquals(ctx.getString(R.string.cloud_busy), msg("skipped", 0, 0, "busy"))
    }

    @Test fun anUnuploadableManifestExplainsItself() {
        assertEquals(ctx.getString(R.string.cloud_upload_unsupported), msg("skipped", 0, 0, "upload-unsupported"))
    }

    @Test fun aPartialSyncNamesTheSlotsItLeft() {
        fun partial(conflicts: Int, detail: String) = cloudMessage(ctx, CloudSaves.Result("partial", 0, 0L, "blocked-path", conflicts, detail))
        assertEquals(ctx.getString(R.string.cloud_partial, "outside the save folder: ../x.save"), partial(0, "outside the save folder: ../x.save"))
        assertEquals(ctx.getString(R.string.cloud_conflict_files_detail, 2, "changed locally: 1.save, 2.save"), partial(2, "changed locally: 1.save, 2.save"))
        assertTrue(partial(1, "changed locally: 1.save").contains("1.save"))
        assertEquals(ctx.getString(R.string.cloud_conflict_files, 1), partial(1, ""))
        assertEquals(ctx.getString(R.string.cloud_not_synced), partial(0, ""))
    }

    @Test fun okResultsSayWhatHappened() {
        assertEquals(ctx.getString(R.string.cloud_up_to_date), msg("ok", 0, 0, "up-to-date"))
        assertEquals(ctx.getString(R.string.cloud_done, 3), msg("ok", 3, 0, "uploaded"))
        assertEquals(ctx.getString(R.string.cloud_conflict_files, 2), msg("ok", 1, 2, "conflict"))
    }
}
