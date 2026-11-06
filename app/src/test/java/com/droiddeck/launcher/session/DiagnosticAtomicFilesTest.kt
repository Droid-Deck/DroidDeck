package com.droiddeck.launcher.session

import android.system.ErrnoException
import android.system.OsConstants
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DiagnosticAtomicFilesTest {
    @Test fun successfulRenameNeverUsesTheFallback() {
        val events = ArrayList<String>()

        val method = publish(0, events)

        assertEquals(DiagnosticAtomicFiles.PublicationMethod.RENAME, method)
        assertEquals(listOf("rename", "published:RENAME"), events)
    }

    @Test fun unsupportedRenameErrorsUseTheCompleteLinkFallbackSequence() {
        for (error in listOf(OsConstants.ENOSYS, OsConstants.EOPNOTSUPP, OsConstants.EINVAL)) {
            val events = ArrayList<String>()

            val method = publish(error, events)

            assertEquals(DiagnosticAtomicFiles.PublicationMethod.LINK, method)
            assertEquals(
                listOf("rename", "link", "published:LINK", "verify-linked", "unlink-stage", "verify-final"),
                events,
            )
        }
    }

    @Test fun collisionPermissionPathAndFilesystemErrorsNeverUseTheFallback() {
        for (error in listOf(
            OsConstants.EEXIST,
            OsConstants.EPERM,
            OsConstants.EACCES,
            OsConstants.EXDEV,
            OsConstants.ENOENT,
        )) {
            val events = ArrayList<String>()

            val thrown = assertThrows(ErrnoException::class.java) { publish(error, events) }

            assertEquals(error, thrown.errno)
            assertEquals(listOf("rename"), events)
        }
    }

    @Test fun linkCollisionIsNotMarkedAsPublished() {
        var published = false
        var verified = false

        val thrown = assertThrows(ErrnoException::class.java) {
            DiagnosticAtomicFiles.publishNoReplace(
                rename = { OsConstants.ENOSYS },
                link = { throw ErrnoException("link", OsConstants.EEXIST) },
                onPublished = { published = true },
                verifyLinked = { verified = true },
                unlinkStage = { error("must not unlink") },
                verifyFinal = { error("must not verify final") },
            )
        }

        assertEquals(OsConstants.EEXIST, thrown.errno)
        assertFalse(published)
        assertFalse(verified)
    }

    @Test fun unlinkFailureHappensAfterPublicationIsRecorded() {
        val events = ArrayList<String>()
        var published = false

        assertThrows(IOException::class.java) {
            DiagnosticAtomicFiles.publishNoReplace(
                rename = { events += "rename"; OsConstants.ENOSYS },
                link = { events += "link" },
                onPublished = { published = true; events += "published" },
                verifyLinked = { events += "verify-linked" },
                unlinkStage = { events += "unlink-stage"; throw IOException("injected") },
                verifyFinal = { events += "verify-final" },
            )
        }

        assertTrue(published)
        assertEquals(listOf("rename", "link", "published", "verify-linked", "unlink-stage"), events)
    }

    private fun publish(
        renameError: Int,
        events: MutableList<String>,
    ): DiagnosticAtomicFiles.PublicationMethod = DiagnosticAtomicFiles.publishNoReplace(
        rename = { events += "rename"; renameError },
        link = { events += "link" },
        onPublished = { events += "published:$it" },
        verifyLinked = { events += "verify-linked" },
        unlinkStage = { events += "unlink-stage" },
        verifyFinal = { events += "verify-final" },
    )
}
