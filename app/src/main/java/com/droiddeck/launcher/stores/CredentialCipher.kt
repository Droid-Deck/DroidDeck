package com.droiddeck.launcher.stores

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The stores' sign-in files at rest: AES-256-GCM under a key that lives in the AndroidKeyStore and
 * never leaves it, as a small versioned envelope `{"v":1,"alg":"AES/GCM","iv":...,"ct":...}`
 * (base64). The session's Linux side shares the app's files directory but not its Keystore, so
 * what it can see of a sign-in is the envelope only.
 */
object CredentialCipher {
    const val VERSION = 1
    const val ALG = "AES/GCM"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    /** Where the key comes from: the AndroidKeyStore on a device, a plain key in tests. */
    fun interface KeyProvider {
        /** The key, created on first use. Throws when no key can be had (no Keystore on this ROM). */
        fun key(): SecretKey

        /** The key that sealed earlier files: never a new one. Throws [KeyMissing] when there is none. */
        fun existing(): SecretKey = key()
    }

    /** The Keystore answered and holds no key: nothing sealed before can open again. */
    class KeyMissing : java.security.GeneralSecurityException("no key")

    /**
     * Whether [e], from [open], means the file can never open: it does not authenticate (tampered,
     * or sealed under another key), the key is permanently invalidated or gone, or the file is not
     * an envelope. Anything else - the Keystore busy or erroring, an I/O error - may pass.
     */
    fun isPermanent(e: Throwable): Boolean = e is javax.crypto.AEADBadTagException ||
        e is android.security.keystore.KeyPermanentlyInvalidatedException ||
        e is KeyMissing || e is org.json.JSONException || e is IllegalArgumentException

    /**
     * An AES-256 key in the AndroidKeyStore: not exportable, encrypt/decrypt with GCM only, no user
     * authentication (background downloads and launch-time code fetches use it with the screen
     * off). StrongBox where the device has it, the TEE otherwise.
     */
    object Keystore : KeyProvider {
        private const val ALIAS = "droiddeck-store-credentials"
        private const val PROVIDER = "AndroidKeyStore"

        @Synchronized
        override fun existing(): SecretKey {
            val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
            return ks.getKey(ALIAS, null) as? SecretKey ?: throw KeyMissing()
        }

        @Synchronized
        override fun key(): SecretKey {
            val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            if (Build.VERSION.SDK_INT >= 28) {
                try { return generate(strongBox = true) } catch (e: Exception) { /* no StrongBox: the TEE */ }
            }
            return generate(strongBox = false)
        }

        private fun generate(strongBox: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
                .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run { init(spec); generateKey() }
        }
    }

    /** [plain] sealed into the envelope. The cipher picks the IV (the Keystore requires it). */
    fun seal(plain: String, keys: KeyProvider): JSONObject {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keys.key()) }
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return JSONObject().put("v", VERSION).put("alg", ALG)
            .put("iv", b64(cipher.iv)).put("ct", b64(ct))
    }

    /** The text inside [envelope]. Throws when it is not ours, of another version, or does not authenticate. */
    fun open(envelope: JSONObject, keys: KeyProvider): String {
        require(envelope.optInt("v", -1) == VERSION && envelope.optString("alg") == ALG) { "unknown envelope" }
        val iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)
        val ct = Base64.decode(envelope.getString("ct"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, keys.existing(), GCMParameterSpec(TAG_BITS, iv)) }
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    /** Whether [json] is an envelope rather than plain credentials. */
    fun isEnvelope(json: JSONObject): Boolean = json.has("v") && json.has("ct") && json.has("iv")

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
