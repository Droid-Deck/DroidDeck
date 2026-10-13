package com.droiddeck.launcher.stores.gog

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
class GogResumeSpaceTest {
    @Test fun remainingWorkRejectsMeasuredZeroButNotUnknownSpace() {
        val root = File(RuntimeEnvironment.getApplication().filesDir, "gog-zero").apply { mkdirs() }
        ShadowStatFs.registerStats(root.path, 1024, 0, 0)
        val files = listOf(GogDownloadManager.DepotFile("missing").apply { totalSize = 4 })
        val needed = GogDownloadManager.remainingInstallBytes(files, root)
        assertEquals(4L, needed)
        assertTrue(StoreInstallRoot.insufficientSpace(needed, StoreInstallRoot.availableBytes(root)))
        assertFalse(StoreInstallRoot.insufficientSpace(needed, StoreInstallRoot.SPACE_UNKNOWN))
    }

    @Test fun remainingSpaceExcludesVerifiedFilesButIncludesCorruptAndPartialFiles() {
        val root = File(RuntimeEnvironment.getApplication().filesDir, "gog-resume").apply { mkdirs() }
        val data = byteArrayOf(1, 2, 3, 4)
        val hash = MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }
        val files = listOf("good", "corrupt", "partial", "missing").map {
            GogDownloadManager.DepotFile(it).apply { totalSize = 4; md5 = hash }
        }
        File(root, "good").writeBytes(data)
        File(root, "corrupt").writeBytes(ByteArray(4))
        File(root, "partial").writeBytes(byteArrayOf(1))
        assertEquals(12L, GogDownloadManager.remainingInstallBytes(files, root))
        for (file in files) File(root, file.relativePath).writeBytes(data)
        assertEquals(0L, GogDownloadManager.remainingInstallBytes(files, root))
    }
}
