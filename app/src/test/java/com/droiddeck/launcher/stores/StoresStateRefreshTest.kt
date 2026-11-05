package com.droiddeck.launcher.stores

import android.app.Application
import com.droiddeck.launcher.stores.gog.GogMaintenance
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class StoresStateRefreshTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val secret: SecretKey = SecretKeySpec(ByteArray(32), "AES")
    private val plain = CredentialCipher.KeyProvider { secret }

    private fun opening(onOpen: () -> Unit) = object : CredentialCipher.KeyProvider {
        override fun key() = secret
        override fun existing(): SecretKey { onOpen(); return secret }
    }

    private fun refreshRunning() = Thread.getAllStackTraces().keys.any { it.name == "stores-refresh" }

    private fun awaitRefreshDone() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (refreshRunning() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("refresh did not finish", !refreshRunning())
        ShadowLooper.idleMainLooper()
    }

    @Before fun setUp() {
        StoreAccounts.keys = plain
        StoreAccounts.keystoreFailed = false
        StoreAccounts.write(app, Store.EPIC, JSONObject().put("access_token", "token").put("display_name", "Someone"))
        StoresState.accounts.clear()
    }

    @After fun tearDown() {
        StoreAccounts.keys = plain
        for (store in Store.entries) StoreAccounts.clear(app, store)
        StoreAccounts.keys = CredentialCipher.Keystore
    }

    @Test fun gogCheckDoesNotWaitForTheCredentialLock() {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread { synchronized(StoreAccounts) { held.countDown(); release.await() } }
        held.await()
        val done = CountDownLatch(1)
        thread { GogMaintenance.checkInstalled(app, emptyList()); done.countDown() }
        try {
            assertTrue("checkInstalled waited for the credential lock", done.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            holder.join()
        }
    }

    @Test fun overlappingRefreshesShareOneMorePass() {
        val opens = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        StoreAccounts.keys = opening {
            opens.incrementAndGet()
            entered.countDown()
            release.await()
        }
        StoresState.refresh(app)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(4) { StoresState.refresh(app) }
        } finally {
            release.countDown()
        }
        awaitRefreshDone()
        assertEquals(2, opens.get())
        assertEquals("Someone", StoresState.accounts[Store.EPIC])
    }

    @Test fun aRequestMadeDuringAFailedPassStillRuns() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        StoreAccounts.keys = opening {
            entered.countDown()
            release.await()
            throw Error("keystore died")
        }
        StoresState.refresh(app)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            StoresState.refresh(app)
            StoreAccounts.keys = plain
        } finally {
            release.countDown()
        }
        awaitRefreshDone()
        awaitRefreshDone()
        assertEquals("Someone", StoresState.accounts[Store.EPIC])
    }

    @Test fun aFailedRefreshDoesNotBlockTheNextOne() {
        val failed = CountDownLatch(1)
        StoreAccounts.keys = opening { failed.countDown(); throw Error("keystore died") }
        StoresState.refresh(app)
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        awaitRefreshDone()
        assertNull(StoresState.accounts[Store.EPIC])
        StoreAccounts.keys = plain
        StoresState.refresh(app)
        awaitRefreshDone()
        assertEquals("Someone", StoresState.accounts[Store.EPIC])
    }
}
