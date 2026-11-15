package com.droiddeck.launcher.stores.gog

import com.droiddeck.launcher.stores.StoreGameSidecar
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

internal enum class GogOperation { UPDATE, REPAIR, PATCH, REFRESH }

internal data class GogInstallRecord(
    val id: String,
    val source: String,
    val generation: Int = 0,
    val build: String = "",
    val version: String = "",
    val branch: String = "",
    val language: String = "en-US",
    val products: List<String> = listOf(id),
    val manifest: JSONObject = JSONObject(),
    val files: JSONArray = JSONArray(),
    val verified: Boolean = true,
) {
    fun json() = JSONObject().put("schema", 1).put("id", id).put("source", source)
        .put("generation", generation).put("build", build).put("version", version).put("branch", branch)
        .put("language", language).put("products", JSONArray(products)).put("manifest", manifest)
        .put("files", files).put("verified", verified)

    fun write(folder: File) = GogFiles.write(File(GogFiles.dir(folder), "installed.json"), json())

    companion object {
        fun parse(o: JSONObject): GogInstallRecord {
            require(o.optInt("schema") == 1) { "Unsupported GOG installation record" }
            return GogInstallRecord(o.getString("id"), o.getString("source"), o.optInt("generation"),
                o.optString("build"), o.optString("version"), o.optString("branch"), o.optString("language", "en-US"),
                o.optJSONArray("products")?.strings().orEmpty().ifEmpty { listOf(o.getString("id")) },
                o.optJSONObject("manifest") ?: JSONObject(), o.optJSONArray("files") ?: JSONArray(), o.optBoolean("verified", true))
        }

        fun read(folder: File): GogInstallRecord? = GogFiles.read(File(GogFiles.dir(folder), "installed.json"))?.let(::parse)

        fun authenticBuild(folder: File, id: String): String = GogFiles.read(File(folder, "goggame-$id.id"))?.optString("buildId").orEmpty()
            .ifBlank { GogFiles.read(File(folder, "goggame-$id.info"))?.optString("buildId").orEmpty() }

        fun migrate(folder: File, sidecar: StoreGameSidecar): GogInstallRecord {
            read(folder)?.let { require(it.id == sidecar.id); return it }
            val legacy = GogFiles.read(File(folder, "_gog_manifest.json"))
            val info = GogFiles.read(File(folder, "goggame-${sidecar.id}.info"))
            val build = authenticBuild(folder, sidecar.id).ifBlank { legacy?.optString("buildId").orEmpty().ifBlank { sidecar.installVersion } }
            val ids = folder.listFiles().orEmpty().mapNotNull { Regex("goggame-([0-9]+)\\.info").matchEntire(it.name)?.groupValues?.get(1) }
            return GogInstallRecord(sidecar.id, if (legacy != null || sidecar.installVersion.isNotBlank()) "depot" else "offline",
                legacy?.optInt("generation", if (build.isNotBlank()) 2 else 0) ?: if (build.isNotBlank()) 2 else 0,
                build, info?.optString("versionName").orEmpty(), language = sidecar.extra[com.droiddeck.launcher.stores.StoreLanguage.EXTRA] ?: "en-US",
                products = (listOf(sidecar.id) + ids).distinct()).also { it.write(folder) }
        }
    }
}

internal object GogFiles {
    fun dir(folder: File): File = inside(folder, ".droiddeck-gog").apply { mkdirs() }
    fun journal(folder: File): File = File(dir(folder), "operation.json")

    fun inside(root: File, raw: String): File {
        val path = raw.replace('\\', '/')
        require(path.isNotBlank() && !path.startsWith('/') && ':' !in path && '\u0000' !in path &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Invalid GOG path: $raw" }
        val base = root.canonicalFile
        var out = base
        for (part in path.split('/')) {
            out = File(out, part)
            require(!Files.isSymbolicLink(out.toPath())) { "GOG path is a symbolic link: $raw" }
        }
        require(out.canonicalFile.toPath().startsWith(base.toPath())) { "GOG path leaves installation" }
        return out
    }

    fun read(file: File): JSONObject? {
        require(!Files.isSymbolicLink(file.toPath())) { "GOG metadata is a symbolic link" }
        return if (!file.exists()) null else JSONObject(file.readText())
    }

    fun write(file: File, value: JSONObject) {
        require(!Files.isSymbolicLink(file.toPath()))
        file.parentFile!!.mkdirs()
        val tmp = File.createTempFile(".gog-", ".stage", file.parentFile)
        try {
            FileOutputStream(tmp).use { it.write(value.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
            replace(tmp, file)
        } finally { tmp.delete() }
    }

    fun replace(from: File, to: File) {
        require(!Files.isSymbolicLink(to.toPath()))
        try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    fun hash(file: File, algorithm: String = "MD5"): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input -> val buffer = ByteArray(128 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun matches(folder: File, file: JSONObject): Boolean {
        val f = inside(folder, file.getString("path"))
        if (!Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS) || f.length() != file.getLong("size")) return false
        val md5 = file.optString("md5")
        if (md5.matches(Regex("[a-fA-F0-9]{32}"))) return hash(f).equals(md5, true)
        val sha = file.optString("sha256")
        if (sha.isNotBlank()) return hash(f, "SHA-256").equals(sha, true)
        val chunks = file.optJSONArray("chunks")?.objects().orEmpty()
        var offset = 0L
        return chunks.isNotEmpty() && chunks.all { chunk ->
            val result = GogEstimate.range(f, offset, chunk.getLong("size"), chunk.getString("md5"))
            offset += chunk.getLong("size"); result
        } || f.length() == 0L
    }

    fun validate(files: JSONArray, folder: File) {
        val paths = HashSet<String>()
        val components = HashMap<String, String>()
        for (file in files.objects()) {
            val path = file.getString("path").replace('\\', '/')
            inside(folder, path)
            require(!path.startsWith(".droiddeck-", true) && path != StoreGameSidecar.FILE_NAME) { "GOG content overlaps launcher metadata" }
            require(paths.add(path.lowercase(Locale.ROOT))) { "GOG paths differ only by case: $path" }
            var parent = folder
            var relative = ""
            for (part in path.split('/')) {
                relative = if (relative.isEmpty()) part else "$relative/$part"
                val previous = components.putIfAbsent(relative.lowercase(Locale.ROOT), relative)
                require(previous == null || previous == relative) { "GOG directory paths differ only by case: $path" }
                require(parent.list()?.none { it != part && it.equals(part, true) } != false) { "An existing path differs only by case: $path" }
                parent = File(parent, part)
            }
        }
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
internal fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }

internal data class GogBuild(val id: String, val version: String, val generation: Int, val branch: String, val link: String) {
    companion object {
        fun select(builds: JSONObject, pinned: String = "", branch: String = ""): GogBuild? {
            val items = builds.optJSONArray("items")?.objects().orEmpty().filter { it.optString("os", "windows") == "windows" }
            val item = if (pinned.isNotBlank()) items.firstOrNull { it.optString("build_id") == pinned || it.optString("legacy_build_id") == pinned }
                else items.firstOrNull { it.optString("branch").let { b -> (if (b == "null") "" else b) == branch } }
            return item?.let { GogBuild(it.optString("build_id"), it.optString("version_name"), it.optInt("generation", 2),
                it.optString("branch").let { b -> if (b == "null") "" else b },
                it.optString("link").ifBlank { it.optString("meta_url").ifBlank { it.optJSONArray("urls")?.optJSONObject(0)?.optString("url").orEmpty() } }) }
        }
    }
}

internal data class GogOfflinePart(val id: String, val name: String, val url: String, val size: Long, val md5: String)
internal data class GogOfflineDownload(val id: String, val name: String, val version: String, val language: String, val parts: List<GogOfflinePart>) {
    val size: Long get() = parts.sumOf { it.size }
    companion object {
        fun parse(downloads: JSONObject, kind: String): List<GogOfflineDownload> = downloads.optJSONArray(kind)?.objects().orEmpty()
            .filter { it.optString("os") == "windows" }.mapNotNull { entry ->
                val files = entry.optJSONArray("files")?.objects().orEmpty()
                val parts = files.mapNotNull { f ->
                    val url = f.optString("downlink").ifBlank { f.optString("manualUrl") }
                    if (url.isBlank()) null else GogOfflinePart(f.optString("id"), f.optString("filename"), url, f.optLong("size"), f.optString("md5"))
                }
                if (parts.isEmpty()) null else GogOfflineDownload(entry.optString("id").ifBlank { parts.first().id },
                    entry.optString("name", "GOG download"), entry.optString("version"), entry.optString("language"), parts)
            }
    }
}
