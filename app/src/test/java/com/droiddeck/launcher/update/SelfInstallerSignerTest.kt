package com.droiddeck.launcher.update

import android.content.pm.PackageInfo
import android.content.pm.Signature
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class SelfInstallerSignerTest {
    private val cert = byteArrayOf(1, 2, 3, 4, 5)
    private val expected = MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it) }

    @Suppress("DEPRECATION")
    private fun archive() = PackageInfo().apply { signatures = arrayOf(Signature(cert)) }

    @Config(sdk = [28, 29])
    @Test fun androidNineAndTenUseTheSignaturesWhenSigningInfoIsMissing() {
        assertEquals(listOf(expected), SelfInstaller.archiveSignerDigests(archive()))
    }

    @Config(sdk = [26])
    @Test fun androidEightIsCheckedToo() {
        assertEquals(listOf(expected), SelfInstaller.archiveSignerDigests(archive()))
    }

    @Config(sdk = [33])
    @Test fun anArchiveWithNoCertificatesHasNoDigests() {
        assertTrue(SelfInstaller.archiveSignerDigests(PackageInfo()).isEmpty())
    }
}
