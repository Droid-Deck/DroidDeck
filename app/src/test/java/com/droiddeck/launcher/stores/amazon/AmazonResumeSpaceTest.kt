package com.droiddeck.launcher.stores.amazon

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import com.droiddeck.launcher.stores.StoreInstallRoot

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AmazonResumeSpaceTest {
    @Test fun remainingWorkRejectsMeasuredZeroButNotUnknownSpace() {
        val root = File(RuntimeEnvironment.getApplication().filesDir, "amazon-zero").apply { mkdirs() }
        ShadowStatFs.registerStats(root.path, 1024, 0, 0)
        val files = listOf(AmazonManifest.ManifestFile("missing", 4, 0, null))
        val needed = AmazonDownloadManager.remainingInstallBytes(files, root)
        assertEquals(4L, needed)
        assertTrue(StoreInstallRoot.insufficientSpace(needed, StoreInstallRoot.availableBytes(root)))
        assertFalse(StoreInstallRoot.insufficientSpace(needed, StoreInstallRoot.SPACE_UNKNOWN))
    }

    @Test fun remainingSpaceExcludesVerifiedFilesButIncludesCorruptAndPartialFiles() {
        val root = File(RuntimeEnvironment.getApplication().filesDir, "amazon-resume").apply { mkdirs() }
        val data = byteArrayOf(1, 2, 3, 4)
        val hash = MessageDigest.getInstance("SHA-256").digest(data)
        val files = listOf("good", "corrupt", "partial", "missing").map {
            AmazonManifest.ManifestFile(it, 4, 0, hash)
        }
        File(root, "good").writeBytes(data)
        File(root, "corrupt").writeBytes(ByteArray(4))
        File(root, "partial").writeBytes(byteArrayOf(1))
        assertEquals(12L, AmazonDownloadManager.remainingInstallBytes(files, root))
        for (file in files) File(root, file.unixPath()).writeBytes(data)
        assertEquals(0L, AmazonDownloadManager.remainingInstallBytes(files, root))
    }
}
