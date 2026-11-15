package com.droiddeck.launcher.stores.gog

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

internal object GogEstimate {
    data class Size(val download: Long, val required: Long)
    private data class Chunk(val file: File, val offset: Long)

    fun range(file: File, offset: Long, size: Long, md5: String): Boolean {
        if (!file.isFile || offset < 0 || size < 0 || file.length() < offset + size || !md5.matches(Regex("[a-fA-F0-9]{32}"))) return false
        return runCatching {
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                val digest = MessageDigest.getInstance("MD5"); val buffer = ByteArray(64 * 1024); var left = size
                while (left > 0) { val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt()); check(n > 0); digest.update(buffer, 0, n); left -= n }
                digest.digest().joinToString("") { "%02x".format(it) }.equals(md5, true)
            }
        }.getOrDefault(false)
    }

    fun calculate(folder: File, old: GogInstallRecord, target: GogInstallRecord, patches: JSONArray, reuseChunks: Boolean = true): Size {
        val reusable = HashMap<Pair<String, Long>, MutableList<Chunk>>()
        for (file in old.files.objects()) {
            val source = GogFiles.inside(folder, file.getString("path")); var offset = 0L
            for (chunk in file.optJSONArray("chunks")?.objects().orEmpty()) {
                reusable.getOrPut(chunk.optString("md5").lowercase() to chunk.optLong("size")) { ArrayList() }.add(Chunk(source, offset))
                offset += chunk.optLong("size")
            }
        }
        var download = 0L; var required = 0L
        for (file in target.files.objects()) {
            if (file.optBoolean("directory") || GogFiles.matches(folder, file)) continue
            val chunks = file.optJSONArray("chunks")?.objects().orEmpty()
            val stamp = MessageDigest.getInstance("MD5").digest(file.getString("path").toByteArray()).joinToString("") { "%02x".format(it) }
            val stage = File(GogFiles.dir(folder), "stage/$stamp")
            var offset = 0L
            var cost = if (chunks.isEmpty()) file.getLong("size") else chunks.sumOf { chunk ->
                val size = chunk.getLong("size"); val hash = chunk.getString("md5")
                val exists = reuseChunks && (range(stage, offset, size, hash) || reusable[hash.lowercase() to size].orEmpty().any { range(it.file, it.offset, size, hash) })
                offset += size
                if (exists) 0L else chunk.optLong("compressedSize")
            }
            val patch = patches.objects().firstOrNull { it.optString("path_target").replace('\\', '/') == file.getString("path") }
            if (reuseChunks && patch != null) {
                val source = GogFiles.inside(folder, patch.getString("path_source").replace('\\', '/'))
                val delta = patch.getJSONArray("chunks").objects().sumOf { it.optLong("compressedSize") }
                if (delta < cost && source.isFile && GogFiles.hash(source).equals(patch.getString("md5_source"), true)) cost = delta
            }
            download = Math.addExact(download, cost)
            required = Math.addExact(required, maxOf(0, file.getLong("size") - stage.length()) + cost)
        }
        return Size(download, required)
    }
}
