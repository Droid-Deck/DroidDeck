package com.droiddeck.launcher.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HashesTest {
    private fun file(text: String): File =
        Files.createTempFile("hashes", ".bin").toFile().apply { writeText(text); deleteOnExit() }

    @Test fun sha256OfKnownInput() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hashes.sha256(file("abc")))
    }

    @Test fun sha512OfKnownInput() {
        assertEquals(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            Hashes.sha512(file("abc")),
        )
    }

    @Test fun githubDigestIsRecognised() {
        val hex = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertTrue(Hashes.isGithubSha256("sha256:$hex"))
        assertEquals(hex, Hashes.githubSha256("sha256:$hex"))
        assertEquals(hex.uppercase(), Hashes.githubSha256("SHA256:${hex.uppercase()}"))
    }

    @Test fun anythingElseIsNotADigest() {
        assertFalse(Hashes.isGithubSha256(null))
        assertFalse(Hashes.isGithubSha256(""))
        assertFalse(Hashes.isGithubSha256("sha256:abc"))
        assertFalse(Hashes.isGithubSha256("sha512:" + "0".repeat(64)))
        assertNull(Hashes.githubSha256("md5:0123"))
    }
}
