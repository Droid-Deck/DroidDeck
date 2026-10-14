package com.droiddeck.launcher.core

import java.io.File
import java.security.MessageDigest

object Hashes {
    private val GITHUB_SHA256 = Regex("(?i)sha256:[0-9a-f]{64}")

    @JvmStatic
    fun sha256(file: File): String = hex(file, "SHA-256")

    @JvmStatic
    fun sha512(file: File): String = hex(file, "SHA-512")

    @JvmStatic
    fun isGithubSha256(digest: String?): Boolean = digest != null && digest.matches(GITHUB_SHA256)

    @JvmStatic
    fun githubSha256(digest: String?): String? = digest?.takeIf { isGithubSha256(it) }?.substringAfter(':')

    private fun hex(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().buffered(1 shl 16).use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
