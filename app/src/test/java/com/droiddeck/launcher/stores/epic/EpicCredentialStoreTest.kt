package com.droiddeck.launcher.stores.epic

import com.droiddeck.launcher.stores.CredentialCipher
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EpicCredentialStoreTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")

    @Before fun setUp() { StoreAccounts.keys = CredentialCipher.KeyProvider { key }; StoreAccounts.keystoreFailed = false }
    @After fun tearDown() {
        StoreAccounts.clear(app, Store.EPIC)
        StoreAccounts.keys = CredentialCipher.Keystore; StoreAccounts.keystoreFailed = false
    }

    private val existing = JSONObject().put("access_token", "AT").put("refresh_token", "RT").put("account_id", "acc")
        .put("display_name", "Someone").put("expires_at", System.currentTimeMillis() + 3_600_000L)

    @Test fun anExistingSignInIsRead() {
        StoreAccounts.write(app, Store.EPIC, existing)
        val c = EpicCredentialStore.load(app)!!
        assertEquals("AT", c.accessToken)
        assertEquals("RT", c.refreshToken)
        assertEquals("acc", c.accountId)
        assertEquals("Someone", c.displayName)
        assertTrue(EpicCredentialStore.isLoggedIn(app))
        assertFalse(EpicCredentialStore.expired(app))
        assertEquals("AT", EpicCredentialStore.getValidAccessToken(app))
        assertEquals("Someone", StoreAccounts.signedInAs(app, Store.EPIC))
    }

    @Test fun aRefreshAnswerKeepsWhatItOmits() {
        StoreAccounts.write(app, Store.EPIC, existing)
        EpicCredentialStore.save(app, EpicCredentialStore.TokenResult("AT2", null, null, null, 5L))
        val c = EpicCredentialStore.load(app)!!
        assertEquals("AT2", c.accessToken)
        assertEquals("RT", c.refreshToken)
        assertEquals("acc", c.accountId)
        assertEquals("Someone", c.displayName)
        assertTrue(EpicCredentialStore.expired(app))
        val stored = StoreAccounts.read(app, Store.EPIC)!!
        for (k in listOf("access_token", "refresh_token", "account_id", "display_name", "expires_at")) assertTrue(k, stored.has(k))
    }

    @Test fun aNewSignInReplacesTheOldAccount() {
        StoreAccounts.write(app, Store.EPIC, existing)
        EpicCredentialStore.save(app, EpicCredentialStore.TokenResult("NEW", null, "acc2", "Other", 9L), merge = false)
        val c = EpicCredentialStore.load(app)!!
        assertNull(c.refreshToken)
        assertEquals("acc2", c.accountId)
        assertFalse(StoreAccounts.read(app, Store.EPIC)!!.has("refresh_token"))
    }

    @Test fun signedOutAndUnusableAnswers() {
        assertNull(EpicCredentialStore.load(app))
        assertNull(EpicCredentialStore.getValidAccessToken(app))
        assertFalse(EpicCredentialStore.expired(app))
        assertNull(EpicCredentialStore.TokenResult.from(JSONObject().put("error", "x")))
        assertNull(EpicCredentialStore.TokenResult.from(JSONObject().put("refresh_token", "x")))
        StoreAccounts.write(app, Store.EPIC, JSONObject().put("access_token", "").put("account_id", "a"))
        assertNull(EpicCredentialStore.load(app))
    }

    @Test fun anExpiringTokenWithoutRefreshTokenIsReturnedAsIs() {
        StoreAccounts.write(app, Store.EPIC, JSONObject().put("access_token", "OLD").put("expires_at", 1L))
        assertEquals("OLD", EpicCredentialStore.getValidAccessToken(app))
        assertTrue(EpicCredentialStore.expired(app))
    }
}
