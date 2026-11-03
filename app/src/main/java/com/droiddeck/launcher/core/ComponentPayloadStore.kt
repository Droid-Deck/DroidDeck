package com.droiddeck.launcher.core

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.function.BooleanSupplier
import java.util.function.Consumer

internal class ComponentPayloadStore(private val assets: (String) -> InputStream) {
    private val entries by lazy {
        val index = readObject("play-components/index.json", 2 * 1024 * 1024)
        if (index.getInt("schema") != 1) throw IOException("Unsupported component index")
        index.getJSONObject("entries")
    }

    private fun readObject(path: String, limit: Int = INDEX_LIMIT): JSONObject {
        val bytes = assets(path).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > limit) throw IOException("Component metadata exceeds its size limit")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    fun copy(url: String, output: OutputStream, limit: Long, expectedSha256: String? = null,
             cancelled: BooleanSupplier? = null, progress: Consumer<Float>? = null): Long {
        checkCancelled(cancelled)
        val entry = entries.optJSONObject(url) ?: throw IOException("Component is absent from this build: $url")
        val size = entry.getLong("size")
        val digest = entry.getString("sha256")
        if (size <= 0 || size > limit || !SHA256.matches(digest)) throw IOException("Invalid component metadata")
        if (expectedSha256 != null && !digest.equals(expectedSha256, ignoreCase = true)) {
            throw IOException("Component version does not match this build")
        }
        val recipe = "play-components/recipes/$digest.json"
        if (entry.getString("recipe") != recipe) throw IOException("Invalid component recipe path")
        val chunks = readObject(recipe).getJSONArray("chunks")
        if (chunks.length() == 0) throw IOException("Component contains no payload")
        val fullDigest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        var total = 0L
        var lastPercent = -1
        for (index in 0 until chunks.length()) {
            checkCancelled(cancelled)
            val chunk = chunks.getJSONObject(index)
            val path = chunk.getString("asset")
            val chunkSize = chunk.getLong("size")
            val chunkSha256 = chunk.getString("sha256")
            if (!ASSET.matches(path) || chunkSize <= 0 || chunkSize > size - total || !SHA256.matches(chunkSha256)) {
                throw IOException("Invalid component chunk metadata")
            }
            val chunkDigest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            assets(path).use { input ->
                while (true) {
                    checkCancelled(cancelled)
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count.toLong() > chunkSize - copied) throw IOException("Component chunk exceeds pinned size")
                    chunkDigest.update(buffer, 0, count)
                    fullDigest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    copied += count
                    total += count
                    val percent = (total * 100 / size).toInt().coerceAtMost(99)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        progress?.accept(percent / 100f)
                    }
                }
            }
            if (copied != chunkSize || hex(chunkDigest.digest()) != chunkSha256) {
                throw IOException("Component chunk failed verification")
            }
        }
        checkCancelled(cancelled)
        if (total != size || hex(fullDigest.digest()) != digest) throw IOException("Component failed verification")
        progress?.accept(1f)
        return total
    }

    fun read(url: String, cancelled: BooleanSupplier? = null): String {
        val output = ByteArrayOutputStream()
        copy(url, output, INDEX_LIMIT.toLong(), cancelled = cancelled)
        return output.toString(Charsets.UTF_8.name())
    }

    fun install(url: String, destination: File, expectedSha256: String? = null,
                cancelled: BooleanSupplier? = null, progress: Consumer<Float>? = null) {
        val parent = destination.absoluteFile.parentFile ?: throw IOException("Component destination has no parent")
        Files.createDirectories(parent.toPath())
        val temporary = Files.createTempFile(parent.toPath(), destination.name + ".", ".component-part")
        try {
            Files.newOutputStream(temporary).use { output ->
                copy(url, output, Long.MAX_VALUE, expectedSha256, cancelled, progress)
            }
            checkCancelled(cancelled)
            Files.move(temporary, destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun checkCancelled(cancelled: BooleanSupplier?) {
        if (Thread.currentThread().isInterrupted || cancelled?.asBoolean == true) throw IOException("Component copy cancelled")
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        private const val INDEX_LIMIT = 16 * 1024 * 1024
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val ASSET = Regex("play-components/[0-9a-f]{64}/[0-9]{4,}")
    }
}
