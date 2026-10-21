package com.droiddeck.launcher.stores.xbox

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class XboxAccountLifecycleTest {
    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun staleSignInCommitsAndBackendStartsCannotAcquireANewerAccount() {
        val original = XboxCredentials.lifecycle(app) { it }
        XboxCredentials.invalidate(app)
        var committed = false
        assertThrows(IllegalStateException::class.java) {
            XboxCredentials.lifecycle(app, original) { committed = true }
        }
        assertFalse(committed)
        assertThrows(IllegalStateException::class.java) { XboxCredentials(app, original) }
        XboxCredentials.lifecycle(app) { current -> assertNotEquals(original, current) }
    }

    @Test fun accountCommitAndLaunchAuthorizationWaitUntilRevocationFinishes() {
        val revoked = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val attempted = CountDownLatch(1)
        val committed = AtomicBoolean(false)
        val authorized = CountDownLatch(1)
        val signOut = Thread {
            XboxCredentials.invalidate(app) {
                revoked.countDown()
                check(releaseStop.await(5, TimeUnit.SECONDS))
            }
        }.apply { start() }
        assertTrue(revoked.await(5, TimeUnit.SECONDS))
        val signIn = Thread {
            attempted.countDown()
            XboxCredentials.lifecycle(app) {
                committed.set(true)
                authorized.countDown()
            }
        }.apply { start() }
        try {
            assertTrue(attempted.await(5, TimeUnit.SECONDS))
            assertFalse(authorized.await(100, TimeUnit.MILLISECONDS))
            assertFalse(committed.get())
        } finally {
            releaseStop.countDown()
            signOut.join(5000)
            signIn.join(5000)
        }
        assertTrue(committed.get())
        assertFalse(signOut.isAlive)
        assertFalse(signIn.isAlive)
    }
}
