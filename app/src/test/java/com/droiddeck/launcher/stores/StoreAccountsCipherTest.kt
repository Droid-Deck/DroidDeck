package com.droiddeck.launcher.stores

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
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** The stores' sign-in files sealed at rest; a plain key stands in for the AndroidKeyStore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StoreAccountsCipherTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun newKey(): SecretKey = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
    private val key = newKey()
    private val fake = CredentialCipher.KeyProvider { key }
    private val broken = CredentialCipher.KeyProvider { throw java.security.KeyStoreException("no keystore") }

    private val creds = JSONObject().put("access_token", "AT-secret-123456").put("refresh_token", "RT-secret-654321")
        .put("account_id", "acct-1").put("display_name", "Someone").put("expires_at", 1_900_000_000_000L)

    private fun file(store: Store) = StoreAccounts.credentialsFile(app, store)

    @Before fun setUp() { StoreAccounts.keys = fake; StoreAccounts.keystoreFailed = false }
    @After fun tearDown() { StoreAccounts.keys = CredentialCipher.Keystore; StoreAccounts.keystoreFailed = false }

    @Test fun envelopeRoundTrips() {
        val envelope = CredentialCipher.seal(creds.toString(), fake)
        assertEquals(1, envelope.getInt("v"))
        assertEquals("AES/GCM", envelope.getString("alg"))
        assertFalse(envelope.toString().contains("secret"))
        assertEquals(creds.toString(), CredentialCipher.open(envelope, fake))
        // A fresh IV every time.
        assertFalse(envelope.getString("iv") == CredentialCipher.seal(creds.toString(), fake).getString("iv"))
    }

    @Test fun writtenCredentialsAreSealedOnDisk() {
        StoreAccounts.write(app, Store.EPIC, creds)
        val text = file(Store.EPIC).readText()
        assertFalse(text.contains("AT-secret") || text.contains("RT-secret") || text.contains("Someone"))
        assertTrue(CredentialCipher.isEnvelope(JSONObject(text)))
        assertEquals(creds.toString(), StoreAccounts.read(app, Store.EPIC).toString())
        assertEquals("Someone", StoreAccounts.signedInAs(app, Store.EPIC))
        assertFalse(File(file(Store.EPIC).path + ".tmp").exists())
    }

    @Test fun aPlainFileFromAnEarlierBuildIsSealedAndStaysSignedIn() {
        for (store in Store.entries) file(store).writeText(creds.toString())
        File(file(Store.GOG).path + ".tmp").writeText(creds.toString())

        StoreAccounts.encryptAll(app)

        for (store in Store.entries) {
            val text = file(store).readText()
            assertTrue(store.id, CredentialCipher.isEnvelope(JSONObject(text)))
            assertFalse(store.id, text.contains("secret"))
            assertEquals(store.id, creds.toString(), StoreAccounts.read(app, store).toString())
        }
        assertFalse(File(file(Store.GOG).path + ".tmp").exists())
    }

    @Test fun aTamperedFileReadsAsSignedOutAndIsDeleted() {
        StoreAccounts.write(app, Store.GOG, creds)
        val envelope = JSONObject(file(Store.GOG).readText())
        val ct = android.util.Base64.decode(envelope.getString("ct"), android.util.Base64.NO_WRAP)
        ct[0] = (ct[0].toInt() xor 1).toByte()
        file(Store.GOG).writeText(envelope.put("ct", android.util.Base64.encodeToString(ct, android.util.Base64.NO_WRAP)).toString())

        assertNull(StoreAccounts.read(app, Store.GOG))
        assertNull(StoreAccounts.signedInAs(app, Store.GOG))
        assertFalse(file(Store.GOG).exists())
    }

    @Test fun aFileSealedUnderALostKeyReadsAsSignedOutAndIsDeleted() {
        StoreAccounts.write(app, Store.AMAZON, creds)
        // Data cleared, or a backup restored on another device: the Keystore key is a new one.
        val other = newKey()
        StoreAccounts.keys = CredentialCipher.KeyProvider { other }
        assertNull(StoreAccounts.read(app, Store.AMAZON))
        assertFalse(file(Store.AMAZON).exists())
    }

    @Test fun garbageReadsAsSignedOut() {
        file(Store.EPIC).writeText("{\"v\":1,\"alg\":\"AES/GCM\",\"iv\":\"!!\",\"ct\":\"??\"}")
        assertNull(StoreAccounts.read(app, Store.EPIC))
        file(Store.EPIC).writeText("not json")
        assertNull(StoreAccounts.read(app, Store.EPIC))
    }

    @Test fun withoutAKeystoreCredentialsStayPlainAndTheNextStartSealsThem() {
        StoreAccounts.keys = broken
        StoreAccounts.write(app, Store.EPIC, creds)
        assertEquals(creds.toString(), file(Store.EPIC).readText())
        assertEquals(creds.toString(), StoreAccounts.read(app, Store.EPIC).toString())
        assertTrue(StoreAccounts.keystoreFailed)

        // The next start, on a Keystore that works.
        StoreAccounts.keys = fake
        StoreAccounts.keystoreFailed = false
        StoreAccounts.encryptAll(app)
        assertTrue(CredentialCipher.isEnvelope(JSONObject(file(Store.EPIC).readText())))
        assertEquals(creds.toString(), StoreAccounts.read(app, Store.EPIC).toString())
    }

    @Test fun signOutRemovesTheFile() {
        StoreAccounts.write(app, Store.GOG, creds)
        StoreAccounts.clear(app, Store.GOG)
        assertFalse(file(Store.GOG).exists())
        assertNull(StoreAccounts.read(app, Store.GOG))
    }
}
