package com.droiddeck.launcher.artifact

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.util.Locale

object RepositorySources {
    internal val lock = Any()
    private const val MAX_POLICY_BYTES = 128 * 1024
    private const val MAX_SOURCES = 100
    private const val MAX_BINDINGS = 1000

    data class Source(
        val id: String,
        val repository: String,
        val builtIn: Boolean,
        val hidden: Boolean,
        val repositoryId: Long? = null,
        val catalogBranch: String? = null,
    )

    private data class Policy(
        val generation: Long,
        val rows: List<Source>,
        val bindings: Map<String, Long>,
        val invalidBranchSourceIds: Set<String> = emptySet(),
        val rawInvalidCatalogBranches: Map<String, Any> = emptyMap(),
        val damaged: Boolean = false,
    )

    @JvmStatic fun canonicalRepository(input: String): String {
        val value = input.trim()
        require(value.isNotEmpty() && value.length <= 300) { "Enter a GitHub owner/repository" }
        require(value.none { it == '\\' || it == '%' || it.isWhitespace() || it.isISOControl() }) {
            "Repository contains unsupported characters"
        }
        val path = if (value.contains("://")) {
            val uri = try { URI(value) } catch (error: URISyntaxException) {
                throw IllegalArgumentException("Invalid GitHub repository URL", error)
            }
            require(uri.scheme.equals("https", true) && uri.host.equals("github.com", true) &&
                uri.rawUserInfo == null && uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null) {
                "Use an HTTPS GitHub repository URL"
            }
            require(uri.rawAuthority.equals("github.com", true)) { "Invalid GitHub authority" }
            uri.rawPath.removePrefix("/")
        } else value
        val parts = path.split('/')
        require(parts.size == 2) { "Use owner/repository, without extra path segments" }
        val owner = parts[0]
        val name = parts[1].removeSuffix(".git")
        require(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})").matches(owner) && !owner.endsWith('-')) {
            "Invalid GitHub owner"
        }
        require(Regex("[A-Za-z0-9_.-]{1,100}").matches(name) && name != "." && name != "..") {
            "Invalid GitHub repository"
        }
        return "$owner/$name".lowercase(Locale.ROOT)
    }

    @JvmStatic fun normalizeCatalogBranch(input: String?): String? {
        val value = input?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        require(value.length <= 256) { "Invalid Git branch name" }
        require(value != "HEAD" && !value.startsWith('-')) { "Invalid Git branch name" }
        require(!value.startsWith('/') && !value.endsWith('/') && !value.contains("//")) {
            "Invalid Git branch name"
        }
        require(!value.contains("..") && !value.contains("@{") && !value.endsWith('.')) {
            "Invalid Git branch name"
        }
        require(value.none { character ->
            character.isWhitespace() || character.isISOControl() || character in "~^:?*[\\"
        }) { "Invalid Git branch name" }
        require(value.split('/').none { it.startsWith('.') || it.endsWith(".lock") }) {
            "Invalid Git branch name"
        }
        return value
    }

    @JvmStatic @JvmOverloads fun source(
        repository: String, hidden: Boolean = false, catalogBranch: String? = null,
    ): Source {
        val canonical = canonicalRepository(repository)
        return Source(digest(canonical), canonical, false, hidden, catalogBranch = normalizeCatalogBranch(catalogBranch))
    }

    @JvmStatic fun list(context: Context, key: String, builtIns: List<String>): List<Source> =
        synchronized(lock) { read(context, key, builtIns).rows.toList() }

    @JvmStatic fun initialize(
        context: Context, key: String, builtIns: List<String>, initialRows: List<Source>,
    ): List<Source> = synchronized(lock) {
        val file = policyFile(context, key)
        if (file.exists() || File(file.path + ".bak").exists()) list(context, key, builtIns)
        else save(context, key, builtIns, initialRows)
    }

    @JvmStatic fun visible(context: Context, key: String, builtIns: List<String>): List<Source> =
        synchronized(lock) {
            val policy = read(context, key, builtIns)
            policy.rows.filterNot { it.hidden || it.id in policy.invalidBranchSourceIds }
        }

    @JvmStatic fun generation(context: Context, key: String, builtIns: List<String>): Long =
        synchronized(lock) { read(context, key, builtIns).generation }

    @JvmStatic fun save(
        context: Context, key: String, builtIns: List<String>, rows: List<Source>,
    ): List<Source> = synchronized(lock) {
        require(rows.size <= MAX_SOURCES) { "Too many repository sources" }
        val defaults = defaults(builtIns)
        val previous = read(context, key, builtIns)
        val seen = HashSet<String>()
        val normalized = rows.map { row ->
            val canonical = canonicalRepository(row.repository)
            require(seen.add(canonical)) { "Repository is already listed" }
            require(row.id == digest(canonical)) { "Repository source identity changed" }
            val bound = previous.bindings[canonical]
            require(row.repositoryId == null || row.repositoryId == bound) { "Repository identity cannot be changed" }
            Source(
                row.id, canonical, defaults.any { it.repository == canonical }, row.hidden, bound,
                normalizeCatalogBranch(row.catalogBranch),
            )
        }
        require(defaults.all { original -> normalized.any { it.id == original.id } }) {
            "Built-in repositories can be hidden but cannot be removed"
        }
        val nextGeneration = maxOf(System.currentTimeMillis(), previous.generation.coerceAtLeast(0) + 1)
        write(context, key, Policy(nextGeneration, normalized, previous.bindings))
        normalized
    }

    @JvmStatic fun isCurrent(
        context: Context, key: String, builtIns: List<String>, sourceId: String, generation: Long,
    ): Boolean = synchronized(lock) {
        val policy = read(context, key, builtIns)
        !policy.damaged && policy.generation == generation &&
            sourceId !in policy.invalidBranchSourceIds && policy.rows.any { it.id == sourceId && !it.hidden }
    }

    @JvmStatic fun isCurrent(
        context: Context, key: String, builtIns: List<String>, source: Source, generation: Long,
    ): Boolean = synchronized(lock) {
        val policy = read(context, key, builtIns)
        !policy.damaged && policy.generation == generation && source.id !in policy.invalidBranchSourceIds &&
            policy.rows.any {
                it.id == source.id && it.repository == source.repository &&
                    it.catalogBranch == source.catalogBranch && !it.hidden
            }
    }

    @JvmStatic fun bind(
        context: Context, key: String, builtIns: List<String>, source: Source,
        repositoryId: Long, expectedGeneration: Long,
    ): Source = synchronized(lock) {
        require(repositoryId > 0) { "Invalid GitHub repository identity" }
        val policy = read(context, key, builtIns)
        val row = policy.rows.firstOrNull {
            it.id == source.id && it.repository == source.repository && it.catalogBranch == source.catalogBranch &&
                !it.hidden && it.id !in policy.invalidBranchSourceIds
        }
        if (policy.damaged || policy.generation != expectedGeneration || row == null) {
            throw IOException("Repository source changed while checking")
        }
        val bound = policy.bindings[row.repository]
        if (bound != null && bound != repositoryId) throw IOException("GitHub repository identity changed")
        if (source.repositoryId != null && source.repositoryId != repositoryId) {
            throw IOException("GitHub repository identity changed")
        }
        val updated = row.copy(repositoryId = repositoryId)
        if (bound == null) {
            require(policy.bindings.size < MAX_BINDINGS) { "Too many remembered repository identities" }
            write(context, key, policy.copy(
                rows = policy.rows.map { if (it.id == row.id) updated else it },
                bindings = policy.bindings + (row.repository to repositoryId),
            ))
        }
        updated
    }

    @JvmStatic fun cacheDirectory(context: Context, key: String, source: Source): File {
        validateKey(key)
        require(source.id == digest(canonicalRepository(source.repository))) { "Invalid source identity" }
        return File(context.cacheDir, "repository-metadata/${digest(key)}/${source.id}")
    }

    internal fun policyFile(context: Context, key: String): File {
        validateKey(key)
        return File(context.filesDir, "repository-sources/${digest(key)}.json")
    }

    private fun validateKey(key: String) {
        require(Regex("[a-z0-9-]+(?:/[a-z0-9-]+){0,3}").matches(key) && key.length <= 100) {
            "Invalid component source scope"
        }
    }

    internal fun <T> whileCurrent(
        context: Context, key: String, builtIns: List<String>, sourceId: String,
        generation: Long, action: () -> T,
    ): T = synchronized(lock) {
        if (!isCurrent(context, key, builtIns, sourceId, generation)) {
            throw IOException("Repository source changed while checking")
        }
        action()
    }

    internal fun <T> whileCurrent(
        context: Context, key: String, builtIns: List<String>, source: Source,
        generation: Long, action: () -> T,
    ): T = synchronized(lock) {
        if (!isCurrent(context, key, builtIns, source, generation)) {
            throw IOException("Repository source changed while checking")
        }
        action()
    }

    private fun defaults(builtIns: List<String>): List<Source> {
        require(builtIns.size <= MAX_SOURCES) { "Too many built-in sources" }
        val rows = builtIns.map { source(it).copy(builtIn = true) }
        require(rows.distinctBy { it.id }.size == rows.size) { "Duplicate built-in repository" }
        return rows
    }

    private fun read(context: Context, key: String, builtIns: List<String>): Policy {
        val initial = defaults(builtIns)
        val file = policyFile(context, key)
        if (!file.exists() && !File(file.path + ".bak").exists()) return Policy(0, initial, emptyMap())
        return try {
            val json = AtomicFile(file).openRead().use { JSONObject(readBounded(it, MAX_POLICY_BYTES).toString(Charsets.UTF_8)) }
            require(json.getInt("schema") == 1 && json.getString("scope") == key)
            val generation = json.getLong("generation")
            require(generation >= 0)
            val bindingsJson = json.getJSONObject("bindings")
            require(bindingsJson.length() <= MAX_BINDINGS)
            val bindings = LinkedHashMap<String, Long>()
            bindingsJson.keys().forEach { name ->
                require(canonicalRepository(name) == name)
                val id = bindingsJson.getLong(name)
                require(id > 0)
                bindings[name] = id
            }
            val saved = json.getJSONArray("sources")
            require(saved.length() <= MAX_SOURCES)
            val seen = HashSet<String>()
            val invalidBranchSourceIds = HashSet<String>()
            val rawInvalidCatalogBranches = LinkedHashMap<String, Any>()
            val rows = (0 until saved.length()).map { index ->
                val item = saved.getJSONObject(index)
                val repo = item.getString("repository")
                require(canonicalRepository(repo) == repo && seen.add(repo))
                val id = item.getString("id")
                require(id == digest(repo))
                val savedBranchValue = item.opt("catalogBranch")
                val invalidBranchType = item.has("catalogBranch") && savedBranchValue !== JSONObject.NULL &&
                    savedBranchValue !is String
                val savedBranch = savedBranchValue as? String
                val branch = if (invalidBranchType) {
                    invalidBranchSourceIds.add(id)
                    rawInvalidCatalogBranches[id] = requireNotNull(savedBranchValue)
                    null
                } else try {
                    normalizeCatalogBranch(savedBranch)
                } catch (_: IllegalArgumentException) {
                    invalidBranchSourceIds.add(id)
                    savedBranch
                }
                Source(
                    id, repo, initial.any { it.repository == repo }, item.getBoolean("hidden"), bindings[repo], branch,
                )
            }.toMutableList()
            initial.filter { original -> rows.none { it.id == original.id } }.forEach {
                rows.add(it.copy(hidden = true, repositoryId = bindings[it.repository]))
            }
            Policy(generation, rows, bindings, invalidBranchSourceIds, rawInvalidCatalogBranches)
        } catch (_: Exception) {
            Policy(Long.MIN_VALUE, initial.map { it.copy(hidden = true) }, emptyMap(), damaged = true)
        }
    }

    private fun write(context: Context, key: String, policy: Policy) {
        val rows = JSONArray()
        policy.rows.forEach { row ->
            val item = JSONObject().put("id", row.id).put("repository", row.repository).put("hidden", row.hidden)
            val rawInvalidBranch = policy.rawInvalidCatalogBranches[row.id]
            if (rawInvalidBranch != null) item.put("catalogBranch", rawInvalidBranch)
            else row.catalogBranch?.let { item.put("catalogBranch", it) }
            rows.put(item)
        }
        val bindings = JSONObject()
        policy.bindings.forEach { (repository, id) -> bindings.put(repository, id) }
        val json = JSONObject().put("schema", 1).put("scope", key).put("generation", policy.generation)
            .put("sources", rows).put("bindings", bindings)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_POLICY_BYTES) { "Repository policy is too large" }
        atomicWrite(policyFile(context, key), bytes)
    }

    internal fun atomicWrite(file: File, bytes: ByteArray) {
        file.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Cannot create metadata directory") }
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    internal fun readBounded(input: InputStream, limit: Int): ByteArray {
        require(limit in 1..16 * 1024 * 1024) { "Invalid metadata limit" }
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
            if (count == -1) break
            if (count == 0) continue
            if (output.size() + count > limit) throw IOException("Metadata exceeds size limit")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    internal fun digest(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

    internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
