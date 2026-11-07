package com.droiddeck.launcher.session

import android.system.ErrnoException
import android.system.OsConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DiagnosticAtomicFilesTest {
    @Test fun successfulRenameResultReturnsNormally() {
        checkAtomicRenameResult(0)
    }

    @Test fun unsupportedRenameResultsUseTheTypedFailure() {
        for (error in listOf(OsConstants.ENOSYS, OsConstants.EOPNOTSUPP, OsConstants.EINVAL)) {
            val thrown = assertThrows(AtomicPublicationUnsupportedException::class.java) {
                checkAtomicRenameResult(error)
            }

            assertEquals(error, thrown.errno)
        }
    }

    @Test fun collisionPermissionPathAndFilesystemErrorsRemainOrdinaryFailures() {
        for (error in listOf(
            OsConstants.EEXIST,
            OsConstants.EPERM,
            OsConstants.EACCES,
            OsConstants.EXDEV,
            OsConstants.ENOENT,
        )) {
            val thrown = assertThrows(ErrnoException::class.java) { checkAtomicRenameResult(error) }

            assertEquals(error, thrown.errno)
        }
    }
}
