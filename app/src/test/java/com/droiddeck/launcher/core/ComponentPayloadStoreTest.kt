package com.droiddeck.launcher.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.function.BooleanSupplier
import java.util.function.Consumer

@RunWith(DroidDeckTestRunner::class)
class ComponentPayloadStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val url = "https://example.test/component"
    private val content = ByteArray(180000) { (it % 251).toByte() }
    private val digest get() = hash(content)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun fixture(transform: (JSONObject, MutableMap<String, ByteArray>) -> Unit = { _, _ -> }): ComponentPayloadStore {
        val assets = mutableMapOf<String, ByteArray>()
        val chunks = JSONArray()
        content.toList().chunked(60000).forEachIndexed { number, bytes ->
            val path = "play-components/$digest/${number.toString().padStart(4, '0')}"
            val data = bytes.toByteArray()
            assets[path] = data
            chunks.put(JSONObject().put("asset", path).put("size", data.size).put("sha256", hash(data)))
        }
        val entry = JSONObject().put("size", content.size).put("sha256", digest).put("chunks", chunks)
        transform(entry, assets)
        val recipe = "play-components/recipes/${entry.getString("sha256")}.json"
        assets[recipe] = JSONObject().put("chunks", entry.getJSONArray("chunks")).toString().toByteArray()
        entry.remove("chunks")
        entry.put("recipe", recipe)
        assets["play-components/index.json"] = JSONObject().put("schema", 1)
            .put("entries", JSONObject().put(url, entry)).toString().toByteArray()
        return ComponentPayloadStore { path ->
            ByteArrayInputStream(assets[path] ?: throw IOException("Missing asset: $path"))
        }
    }

    @Test fun reassemblesChunksAndVerifiesFullPayload() {
        val output = ByteArrayOutputStream()
        assertEquals(content.size.toLong(), fixture().copy(url, output, content.size.toLong(), digest))
        assertArrayEquals(content, output.toByteArray())
    }

    @Test fun missingComponentCannotUseNetwork() {
        assertThrows(IOException::class.java) {
            fixture().copy("https://other.test/component", ByteArrayOutputStream(), Long.MAX_VALUE)
        }
    }

    @Test fun corruptChunkPreservesPreviouslyInstalledDestination() {
        val store = fixture { entry, assets ->
            val path = entry.getJSONArray("chunks").getJSONObject(1).getString("asset")
            assets[path] = ByteArray(60000)
        }
        val destination = temporary.newFile("installed")
        destination.writeText("existing")
        assertThrows(IOException::class.java) { store.install(url, destination) }
        assertEquals("existing", destination.readText())
        assertEquals(listOf("installed"), temporary.root.listFiles()!!.map { it.name })
    }

    @Test fun cancellationDuringCopyPreservesDestinationAndStopsProgress() {
        var cancelled = false
        var progressCount = 0
        val destination = temporary.newFile("installed")
        destination.writeText("existing")
        assertThrows(IOException::class.java) {
            fixture().install(url, destination, cancelled = BooleanSupplier { cancelled }, progress = Consumer {
                progressCount++
                cancelled = true
            })
        }
        assertEquals(1, progressCount)
        assertEquals("existing", destination.readText())
    }

    @Test fun successfulInstallAtomicallyReplacesDestination() {
        val destination = temporary.newFile("installed")
        destination.writeText("existing")
        fixture().install(url, destination, digest)
        assertArrayEquals(content, destination.readBytes())
        assertEquals(listOf("installed"), temporary.root.listFiles()!!.map { it.name })
    }

    @Test fun wrongVersionAndOversizedMetadataAreRejectedBeforeWritingBytes() {
        val output = ByteArrayOutputStream()
        assertThrows(IOException::class.java) { fixture().copy(url, output, Long.MAX_VALUE, "0".repeat(64)) }
        assertThrows(IOException::class.java) { fixture().copy(url, output, content.size - 1L) }
        assertEquals(0, output.size())
    }

    @Test fun truncatedAndOverlongChunksFailVerification() {
        for (size in listOf(59999, 60001)) {
            val store = fixture { entry, assets ->
                assets[entry.getJSONArray("chunks").getJSONObject(0).getString("asset")] = ByteArray(size)
            }
            assertThrows(IOException::class.java) { store.copy(url, ByteArrayOutputStream(), Long.MAX_VALUE) }
        }
    }

    @Test fun traversalPathIsRejectedWithoutOpeningIt() {
        val store = fixture { entry, _ -> entry.getJSONArray("chunks").getJSONObject(0).put("asset", "../secret") }
        assertThrows(IOException::class.java) { store.copy(url, ByteArrayOutputStream(), Long.MAX_VALUE) }
    }

    @Test fun fullDigestIsCheckedEvenWhenEachChunkIsValid() {
        val store = fixture { entry, _ -> entry.put("sha256", "0".repeat(64)) }
        assertThrows(IOException::class.java) { store.copy(url, ByteArrayOutputStream(), Long.MAX_VALUE) }
    }

    @Test fun cancelledBeforeStartCreatesNoDestination() {
        val destination = temporary.root.resolve("installed")
        assertThrows(IOException::class.java) {
            fixture().install(url, destination, cancelled = BooleanSupplier { true })
        }
        assertFalse(Files.exists(destination.toPath()))
        assertEquals(0, temporary.root.listFiles()!!.size)
    }
}
