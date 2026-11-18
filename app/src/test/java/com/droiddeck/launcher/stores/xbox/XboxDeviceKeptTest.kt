package com.droiddeck.launcher.stores.xbox

import com.droiddeck.launcher.stores.CredentialCipher
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import org.json.JSONArray
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
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class XboxDeviceKeptTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")

    @Before fun setUp() { StoreAccounts.keys = CredentialCipher.KeyProvider { key }; StoreAccounts.keystoreFailed = false }
    @After fun tearDown() {
        StoreAccounts.clear(app, Store.XBOX)
        StoreAccounts.keys = CredentialCipher.Keystore; StoreAccounts.keystoreFailed = false
    }

    @Test fun signingOutKeepsOnlyTheRegisteredDevice() {
        val device = JSONArray(listOf(1, 2, 3))
        StoreAccounts.write(app, Store.XBOX, JSONObject().put("username", "player")
            .put("slots", JSONObject().put(XboxCredentials.DEVICE_SLOT, device).put("user-tokens", JSONArray(listOf(9)))))
        XboxCredentials.invalidate(app)
        val record = StoreAccounts.read(app, Store.XBOX)!!
        assertNull(StoreAccounts.signedInAs(app, Store.XBOX))
        val slots = record.getJSONObject("slots")
        assertEquals(listOf(XboxCredentials.DEVICE_SLOT), slots.keys().asSequence().toList())
        assertEquals(device.toString(), slots.getJSONArray(XboxCredentials.DEVICE_SLOT).toString())
    }

    @Test fun aFailedDeviceRewriteStillSignsOutAndRevokes() {
        var sealing = true
        StoreAccounts.keys = object : CredentialCipher.KeyProvider {
            override fun key() = if (sealing) key else throw java.security.KeyStoreException("sealing refused")
            override fun existing() = key
        }
        StoreAccounts.write(app, Store.XBOX, JSONObject().put("username", "player")
            .put("slots", JSONObject().put(XboxCredentials.DEVICE_SLOT, JSONArray(listOf(1)))))
        sealing = false
        var revoked = false
        XboxCredentials.invalidate(app) { revoked = true }
        assertTrue(revoked)
        assertNull(StoreAccounts.signedInAs(app, Store.XBOX))
    }

    @Test fun signingOutWithoutADeviceLeavesNoRecord() {
        StoreAccounts.write(app, Store.XBOX, JSONObject().put("username", "player").put("slots", JSONObject()))
        XboxCredentials.invalidate(app)
        assertNull(StoreAccounts.read(app, Store.XBOX))
    }
}
