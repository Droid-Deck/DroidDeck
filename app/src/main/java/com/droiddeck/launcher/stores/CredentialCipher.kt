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

object CredentialCipher {
    const val VERSION = 1
    const val ALG = "AES/GCM"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun interface KeyProvider {
        fun key(): SecretKey

        fun existing(): SecretKey = key()
    }

    class KeyMissing : java.security.GeneralSecurityException("no key")

    fun isPermanent(e: Throwable): Boolean = e is javax.crypto.AEADBadTagException ||
        e is android.security.keystore.KeyPermanentlyInvalidatedException ||
        e is KeyMissing || e is org.json.JSONException || e is IllegalArgumentException

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
                try { return generate(strongBox = true) } catch (e: Exception) {   }
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

    fun seal(plain: String, keys: KeyProvider): JSONObject {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keys.key()) }
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return JSONObject().put("v", VERSION).put("alg", ALG)
            .put("iv", b64(cipher.iv)).put("ct", b64(ct))
    }

    fun open(envelope: JSONObject, keys: KeyProvider): String {
        require(envelope.optInt("v", -1) == VERSION && envelope.optString("alg") == ALG) { "unknown envelope" }
        val iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)
        val ct = Base64.decode(envelope.getString("ct"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, keys.existing(), GCMParameterSpec(TAG_BITS, iv)) }
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    fun isEnvelope(json: JSONObject): Boolean = json.has("v") && json.has("ct") && json.has("iv")

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
