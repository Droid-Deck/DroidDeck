package com.droiddeck.launcher.stores.ea

import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object EaCrypto {
    val LSX_DEFAULT_KEY = ByteArray(16) { it.toByte() }

    private val OOA_KEY = intArrayOf(65, 50, 114, 45, 208, 130, 239, 176, 220, 100, 87, 197, 118, 104, 202, 9)
        .map { it.toByte() }.toByteArray()

    private const val PRIME_10K = 104729L
    private const val PRIME_20K = 224737L
    private const val PRIME_30K = 350377L

    fun ecbEncrypt(data: ByteArray, key: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/PKCS5Padding").run { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")); doFinal(data) }

    fun ecbDecrypt(data: ByteArray, key: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/PKCS5Padding").run { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES")); doFinal(data) }

    fun licenseEncrypt(data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run { init(Cipher.ENCRYPT_MODE, SecretKeySpec(OOA_KEY, "AES"), IvParameterSpec(ByteArray(16))); doFinal(data) }

    fun licenseDecrypt(data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run { init(Cipher.DECRYPT_MODE, SecretKeySpec(OOA_KEY, "AES"), IvParameterSpec(ByteArray(16))); doFinal(data) }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun unhex(text: String): ByteArray {
        val digits = text.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        if (digits.length % 2 != 0) return ByteArray(0)
        return ByteArray(digits.length / 2) { i -> digits.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** The session key both sides derive after the LSX challenge, from a seed the game picks. */
    fun lsxKey(seed: Int): ByteArray {
        if (seed == 0) return LSX_DEFAULT_KEY.copyOf()
        var state = 7L
        fun next(): Int {
            state = (state * 214013L + 2531011L) and 0xFFFFFFFFL
            return ((state ushr 16) and 0xFFFF).toInt()
        }
        val start = (next().toLong() + seed) and 0xFFFFFFFFL
        state = start
        return ByteArray(16) { next().toByte() }
    }

    fun challengeAnswer(key: String): String = hex(ecbEncrypt(key.toByteArray(Charsets.US_ASCII), LSX_DEFAULT_KEY))

    fun challengeSeed(version: String, answer: String): Int? = when (version) {
        "2" -> 0
        "3" -> (answer[0].code shl 8) or answer[1].code
        else -> null
    }

    /** EA's launch handshake code, which changes every UTC day. */
    fun rtpCode(day: LocalDate = LocalDate.now(ZoneOffset.UTC)): Long {
        val time = ((PRIME_10K * day.year) xor (day.monthValue * PRIME_20K) xor (day.dayOfMonth * PRIME_30K)) and 0xFFFFFFFFL
        return (time xor ((time shl 16) and 0xFFFFFFFFL) xor (time ushr 16)) and 0xFFFFFFFFL
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    fun fnv1a(data: ByteArray): ULong {
        var hash = 0xcbf29ce484222325UL
        for (b in data) hash = (hash xor (b.toUByte().toULong())) * 0x100000001b3UL
        return hash
    }

    fun base64Url(data: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data)
}
