package com.droiddeck.launcher.stores.gog

import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadStage
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.InflaterInputStream

internal object GogFallback {
    fun transfer(folder: File, plan: JSONObject, handle: DownloadQueue.JobHandle?, stop: AtomicBoolean,
        callback: GogDownloadManager.Callback? = null) {
        val stageRoot = File(GogFiles.dir(folder), "stage").apply { mkdirs() }
        val files = plan.getJSONArray("files")
        GogFiles.validate(files, folder)
        var wire = 0L
        fun progress(n: Int) { wire += n; handle?.progress(wire, plan.optLong("downloadBytes").coerceAtLeast(wire)); callback?.onBytes(wire, plan.optLong("downloadBytes").coerceAtLeast(wire), 0) }
        for ((index, item) in files.objects().withIndex()) {
            if (stop.get()) return
            val destination = GogFiles.inside(folder, item.getString("path"))
            if (item.optBoolean("directory")) { java.nio.file.Files.createDirectories(destination.toPath()); continue }
            if (GogFiles.matches(folder, item)) continue
            val stage = File(stageRoot, java.security.MessageDigest.getInstance("MD5").digest(item.getString("path").toByteArray()).joinToString("") { "%02x".format(it) })
            val chunks = item.optJSONArray("chunks")?.objects().orEmpty()
            FileOutputStream(stage).use { output ->
                if (chunks.isNotEmpty()) {
                    for (chunk in chunks) {
                        if (stop.get()) return
                        val packed = File(stageRoot, "${stage.name}.packed")
                        val urls = endpoints(plan.getJSONObject("links"), item.getString("product"), GogContent.cdnPath(chunk.getString("compressedMd5")))
                        download(urls, packed, chunk.getLong("compressedSize"), stop, ::progress)
                        check(GogFiles.hash(packed).equals(chunk.getString("compressedMd5"), true)) { "GOG compressed chunk checksum mismatch" }
                        val digest = java.security.MessageDigest.getInstance("MD5")
                        var count = 0L
                        packed.inputStream().buffered().use { raw ->
                            raw.mark(2); val first = raw.read(); raw.reset()
                            val stored = chunk.getLong("size") == chunk.getLong("compressedSize") && chunk.getString("md5").equals(chunk.getString("compressedMd5"), true)
                            val input = if (!stored && first == 0x78) InflaterInputStream(raw) else raw
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (stop.get()) return
                                val n = input.read(buffer); if (n < 0) break
                                count += n; check(count <= chunk.getLong("size")) { "GOG chunk exceeds expected size" }
                                digest.update(buffer, 0, n); output.write(buffer, 0, n)
                            }
                        }
                        check(count == chunk.getLong("size") && digest.digest().joinToString("") { "%02x".format(it) }.equals(chunk.getString("md5"), true)) { "GOG chunk checksum mismatch" }
                        packed.delete()
                    }
                } else if (item.getLong("size") > 0) {
                    val remote = item.getString("remotePath")
                    val urls = if (remote.startsWith("https://")) listOf(remote) else endpoints(plan.getJSONObject("links"), item.getString("product"), remote.trimStart('/'))
                    val range = item.getLong("offset")
                    val raw = File(stageRoot, "${stage.name}.range")
                    download(urls, raw, item.getLong("size"), stop, ::progress, range)
                    raw.inputStream().use { it.copyTo(output) }; raw.delete()
                }
                output.fd.sync()
            }
            if (stop.get()) return
            check(stage.length() == item.getLong("size")) { "GOG file size mismatch" }
            if (item.optString("md5").isNotBlank()) check(GogFiles.hash(stage).equals(item.getString("md5"), true)) { "GOG file checksum mismatch" }
            if (item.optString("sha256").isNotBlank()) check(GogFiles.hash(stage, "SHA-256").equals(item.getString("sha256"), true)) { "GOG file checksum mismatch" }
            destination.parentFile!!.mkdirs()
            GogFiles.inside(folder, item.getString("path"))
            GogFiles.replace(stage, destination)
            handle?.stage(DownloadStage.INSTALL); handle?.stageProgress((index + 1).toLong(), files.length().toLong())
            callback?.onProgress("Downloading: ${destination.name}", ((index + 1) * 100 / files.length()))
        }
        val targetPaths = files.objects().mapTo(HashSet()) { it.getString("path").lowercase(java.util.Locale.ROOT) }
        for (old in plan.optJSONArray("oldFiles")?.objects().orEmpty()) {
            if (stop.get()) return
            if (old.getString("path").lowercase(java.util.Locale.ROOT) !in targetPaths && GogFiles.matches(folder, old))
                check(GogFiles.inside(folder, old.getString("path")).delete()) { "Could not remove obsolete GOG file" }
        }
    }

    fun endpoints(links: JSONObject, product: String, path: String): List<String> = links.getJSONArray(product).strings().map { base ->
        if (base.contains("{GALAXY_PATH}")) base.replace("{GALAXY_PATH}", path)
        else base.substringBefore('?').trimEnd('/') + "/" + path + if ('?' in base) "?" + base.substringAfter('?') else ""
    }

    fun download(urls: List<String>, destination: File, size: Long, stop: AtomicBoolean, progress: (Int) -> Unit, range: Long? = null) {
        var failure: Throwable? = null
        for (attempt in 0..2) for (url in urls) {
            if (stop.get()) return
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                check(URL(url).protocol == "https" || URL(url).host in setOf("127.0.0.1", "localhost"))
                connection.connectTimeout = 20_000; connection.readTimeout = 30_000
                if (range != null) connection.setRequestProperty("Range", "bytes=$range-${range + size - 1}")
                check(connection.responseCode == if (range == null) 200 else 206) { "GOG download failed (HTTP ${connection.responseCode})" }
                FileOutputStream(destination).use { output ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024); var count = 0L
                        while (true) {
                            if (stop.get()) return
                            val n = input.read(buffer); if (n < 0) break
                            count += n; check(size <= 0 || count <= size) { "GOG response exceeds expected size" }
                            output.write(buffer, 0, n); progress(n)
                        }
                        check(size <= 0 || count == size) { "Incomplete GOG download" }
                    }
                    output.fd.sync()
                }
                return
            } catch (e: Exception) { failure = e }
            finally { connection.disconnect() }
        }
        throw failure ?: IllegalStateException("No GOG download URL")
    }
}
