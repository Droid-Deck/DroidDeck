package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.artifact.RepositorySources
import org.json.JSONObject
import java.net.URI
import java.util.Locale

internal object SystemVulkanManifest {
    const val COMPONENT = "system-vulkan"
    const val API = 1
    const val MAX_MANIFEST_BYTES = 1024 * 1024
    const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 768L * 1024 * 1024
    const val MAX_FILES = 4096
    const val MAX_FILE_BYTES = 256L * 1024 * 1024

    data class Artifact(
        val name: String,
        val size: Long,
        val sha256: String,
        val treeSha256: String,
    )

    data class Provenance(
        val repository: String,
        val commit: String,
        val buildUrl: String,
    )

    data class Sidecar(
        val variant: String,
        val version: String,
        val minSdk: Int,
        val artifact: Artifact,
        val provenance: Provenance,
    )

    data class Entry(
        val path: String,
        val size: Long,
        val sha256: String,
        val mode: Int,
    )

    data class Bundle(
        val variant: String,
        val version: String,
        val minSdk: Int,
        val treeSha256: String,
        val provenance: Provenance,
        val files: List<Entry>,
        val licenses: List<String>,
    )

    fun sidecar(bytes: ByteArray, expectedRepository: String): Sidecar {
        require(bytes.size <= MAX_MANIFEST_BYTES) { "System Vulkan manifest exceeds size limit" }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        identity(json)
        val variant = variant(json.getString("variant"))
        val version = version(json.getString("version"))
        val requires = json.getJSONObject("requires")
        require(requires.getInt("componentApi") == API && requires.getString("abi") == "arm64-v8a") {
            "System Vulkan component is not compatible with this app"
        }
        val minSdk = requires.getInt("minSdk")
        require(minSdk in 26..100) { "Invalid System Vulkan minimum SDK" }
        val artifactJson = json.getJSONObject("artifact")
        val artifact = Artifact(
            assetName(artifactJson.getString("name")),
            artifactJson.getLong("size").also { require(it in 1..MAX_ARCHIVE_BYTES) },
            digest(artifactJson.getString("sha256")),
            digest(artifactJson.getString("treeSha256")),
        )
        require(artifact.name == "system-vulkan-$variant.tar.zst") { "System Vulkan asset name does not match variant" }
        return Sidecar(variant, version, minSdk, artifact, provenance(json.getJSONObject("provenance"), expectedRepository))
    }

    fun bundle(bytes: ByteArray, expected: Sidecar): Bundle {
        require(bytes.size <= MAX_MANIFEST_BYTES) { "System Vulkan component manifest exceeds size limit" }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        identity(json)
        val variant = variant(json.getString("variant"))
        val version = version(json.getString("version"))
        require(variant == expected.variant && version == expected.version) { "System Vulkan component identity does not match sidecar" }
        val requires = json.getJSONObject("requires")
        require(requires.getInt("componentApi") == API && requires.getString("abi") == "arm64-v8a")
        val minSdk = requires.getInt("minSdk")
        require(minSdk == expected.minSdk) { "System Vulkan component requirements do not match sidecar" }
        val tree = digest(json.getString("treeSha256"))
        require(tree == expected.artifact.treeSha256) { "System Vulkan component tree does not match sidecar" }
        val source = provenance(json.getJSONObject("provenance"), expected.provenance.repository)
        require(source == expected.provenance) { "System Vulkan component provenance does not match sidecar" }

        val rows = json.getJSONArray("files")
        require(rows.length() in 1..MAX_FILES) { "Invalid System Vulkan file inventory" }
        val seen = HashSet<String>()
        var total = 0L
        val files = (0 until rows.length()).map { index ->
            val item = rows.getJSONObject(index)
            require(item.getString("type") == "file") { "System Vulkan bundles may contain regular files only" }
            val path = path(item.getString("path"))
            require(seen.add(path)) { "Duplicate System Vulkan inventory path: $path" }
            val size = item.getLong("size")
            require(size in 1..MAX_FILE_BYTES) { "Invalid System Vulkan file size: $path" }
            total = Math.addExact(total, size)
            require(total <= MAX_EXPANDED_BYTES) { "System Vulkan component expands beyond size limit" }
            val mode = item.getInt("mode")
            require(mode == 420 || mode == 493) { "Invalid System Vulkan file mode: $path" }
            Entry(path, size, digest(item.getString("sha256")), mode)
        }
        require(treeDigest(files) == tree) { "System Vulkan inventory digest does not match" }

        val licensesJson = json.getJSONArray("licenses")
        require(licensesJson.length() in 1..128) { "System Vulkan component has no bounded license inventory" }
        val licenses = (0 until licensesJson.length()).map { path(licensesJson.getString(it)) }
        require(licenses.distinct().size == licenses.size && licenses.all { it.startsWith("licenses/") && it in seen }) {
            "Invalid System Vulkan license inventory"
        }
        val parents = files.flatMap { entry ->
            val segments = entry.path.split('/')
            (1 until segments.size).map { segments.take(it).joinToString("/") }
        }.toSet()
        require(files.none { it.path in parents }) { "System Vulkan inventory places a file above another file" }
        required(variant).forEach { require(it in seen) { "Missing System Vulkan component file: $it" } }
        return Bundle(variant, version, minSdk, tree, source, files, licenses)
    }

    fun treeDigest(files: List<Entry>): String {
        val normalized = files.sortedBy { it.path }.joinToString(separator = "") {
            "${it.path}\u0000file\u0000${it.size}\u0000${it.sha256}\u0000${it.mode}\n"
        }
        return RepositorySources.sha256(normalized.toByteArray(Charsets.UTF_8))
    }

    fun required(variant: String): Set<String> = buildSet {
        addAll(COMMON_REQUIRED)
        when (variant(variant)) {
            "powervr" -> addDxvk("dxvk", listOf("x32", "x64"))
            "xclipse" -> addDxvk("dxvk-2.6.2", listOf("x32", "x64"))
            "mali" -> addDxvk("dxvk-mali", listOf("i386-windows", "x86_64-windows", "aarch64-windows"))
        }
    }

    private fun MutableSet<String>.addDxvk(root: String, architectures: List<String>) {
        architectures.forEach { architecture -> DXVK_DLLS.forEach { add("$root/$architecture/$it") } }
    }

    private fun identity(json: JSONObject) {
        require(json.getInt("schema") == 1 && json.getString("component") == COMPONENT) {
            "Unsupported System Vulkan manifest"
        }
    }

    private fun provenance(json: JSONObject, expectedRepository: String): Provenance {
        val repository = RepositorySources.canonicalRepository(json.getString("repository"))
        require(repository == RepositorySources.canonicalRepository(expectedRepository)) { "System Vulkan producer does not match source" }
        val commit = json.getString("commit").lowercase(Locale.ROOT)
        require(Regex("(?:[0-9a-f]{40}|[0-9a-f]{64})").matches(commit)) { "Invalid System Vulkan producer commit" }
        val buildUrl = json.getString("buildUrl")
        val uri = URI(buildUrl)
        val expectedPrefix = "/$repository/actions/runs/"
        require(uri.scheme.equals("https", true) && uri.host.equals("github.com", true) && uri.rawUserInfo == null &&
            uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null && !buildUrl.contains('\\') &&
            uri.rawPath.lowercase(Locale.ROOT).startsWith(expectedPrefix) &&
            uri.rawPath.substring(expectedPrefix.length).matches(Regex("[1-9][0-9]*"))) {
            "Invalid System Vulkan build URL"
        }
        return Provenance(repository, commit, buildUrl)
    }

    private fun variant(value: String): String {
        require(value in setOf("powervr", "xclipse", "mali")) { "Unsupported System Vulkan variant" }
        return value
    }

    private fun version(value: String): String {
        require(value.isNotBlank() && value.length <= 128 && value.none { it.isISOControl() }) { "Invalid System Vulkan version" }
        return value
    }

    private fun digest(value: String): String = value.lowercase(Locale.ROOT).also {
        require(Regex("[0-9a-f]{64}").matches(it)) { "Invalid SHA-256 digest" }
    }

    private fun assetName(value: String): String = value.also {
        require(it.isNotEmpty() && it.length <= 240 && it.none { char -> char == '/' || char == '\\' || char.isISOControl() }) {
            "Invalid System Vulkan asset name"
        }
    }

    fun path(value: String): String = value.also {
        require(it.isNotEmpty() && it.length <= 512 && !it.startsWith('/') && it.none { char -> char == '\\' || char == '\u0000' || char.isISOControl() } &&
            it.split('/').all { segment -> segment.isNotEmpty() && segment != "." && segment != ".." }) {
            "Unsafe System Vulkan archive path"
        }
    }

    fun archivePath(value: String, isDirectory: Boolean): String = path(
        if (isDirectory) value.removeSuffix("/") else value,
    )

    private val COMMON_REQUIRED = setOf(
        "lib/libhybris-vulkan-icd.so.0",
        "lib/libhybris-common.so.1",
        "lib/libhybris/linker/q.so",
        "lib/libVkLayer_hybris_compat.so",
        "lib/libVkLayer_DROIDDECK_placed_memory.so",
        "lib/VkLayer_DROIDDECK_placed_memory.json",
        "run-proton.py",
        "wsi/libVkLayer_window_system_integration.so",
        "wsi/VkLayer_window_system_integration.json",
    )
    private val DXVK_DLLS = listOf("d3d8.dll", "d3d9.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll")
}
