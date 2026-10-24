package com.droiddeck.launcher.stores.amazon

import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AmazonEngineTest {
    @get:Rule val temp = TemporaryFolder()

    private fun prepared(): File = temp.newFolder().apply {
        val library = File(this, "libdroiddeckamazon.so").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val hash = MessageDigest.getInstance("SHA-256").digest(library.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        File(this, "engine.json").writeText(JSONObject().put("schema", 1).put("abi", 1)
            .put("engine", "amazon").put("target", "aarch64-linux-android")
            .put("library_sha256", hash).toString())
    }

    @Test fun installedEngineWorksRepeatedlyWithoutNetwork() {
        val directory = prepared()
        assertTrue(AmazonEngine.verify(directory))
        assertTrue(AmazonEngine.verify(directory))
    }

    @Test fun changedLibraryIsRejected() {
        val directory = prepared()
        File(directory, "libdroiddeckamazon.so").appendText("tampered")
        assertFalse(AmazonEngine.verify(directory))
    }

    @Test fun incompatibleInterfaceIsRejected() {
        val directory = prepared()
        val manifest = File(directory, "engine.json")
        manifest.writeText(JSONObject(manifest.readText()).put("abi", 2).toString())
        assertFalse(AmazonEngine.verify(directory))
    }

    @Test fun incompleteAndMalformedPacksAreRejected() {
        val directory = prepared()
        File(directory, "libdroiddeckamazon.so").delete()
        assertFalse(AmazonEngine.verify(directory))
        val malformed = prepared()
        File(malformed, "engine.json").writeText("not json")
        assertFalse(AmazonEngine.verify(malformed))
    }

    @Test fun parentStorageAliasIsAllowedButLibrarySymlinkIsRejected() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val directory = prepared()
        val alias = File(temp.root, "alias")
        Files.createSymbolicLink(alias.toPath(), directory.toPath())
        assertTrue(AmazonEngine.verify(alias))
        val library = File(directory, "libdroiddeckamazon.so")
        val outside = temp.newFile("outside.so").apply { writeBytes(library.readBytes()) }
        library.delete()
        Files.createSymbolicLink(library.toPath(), outside.toPath())
        assertFalse(AmazonEngine.verify(directory))
    }
}
