package com.droiddeck.launcher.update

import com.droiddeck.launcher.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SelfInstallerTest {
    @Test
    fun updatePathUsesDigestInsteadOfRemoteFilename() {
        val context = RuntimeEnvironment.getApplication()
        val digest = "a".repeat(64)
        val apk = AppUpdates.Apk(
            "../../outside.apk", "https://github.com/o/r/releases/download/t/outside.apk", 1,
            digest, context.packageName, 1, BuildConfig.RELEASE_SIGNER,
        )
        val target = SelfInstaller.updateFile(context, apk)
        assertEquals("$digest.apk", target.name)
        assertEquals("updates", target.parentFile!!.name)
        assertEquals(context.cacheDir.canonicalFile, target.parentFile!!.parentFile!!.canonicalFile)
    }

    @Test
    fun signerRotationMustBeSameSignerOrForwardLineage() {
        val old = SelfInstaller.VerifiedIdentity(setOf("old"), emptySet())
        val rotated = SelfInstaller.VerifiedIdentity(setOf("new"), setOf("old", "new"))
        val attackerWithCommonAncestor = SelfInstaller.VerifiedIdentity(setOf("attacker"), setOf("ancestor", "attacker"))
        val installedWithAncestor = SelfInstaller.VerifiedIdentity(setOf("old"), setOf("ancestor", "old"))
        assertTrue(SelfInstaller.compatibleUpdate(old, old))
        assertTrue(SelfInstaller.compatibleUpdate(old, rotated))
        assertFalse(SelfInstaller.compatibleUpdate(rotated, old))
        assertFalse(SelfInstaller.compatibleUpdate(installedWithAncestor, attackerWithCommonAncestor))
    }
}
