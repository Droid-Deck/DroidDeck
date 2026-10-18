package com.droiddeck.launcher.gpu

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.artifact.GitHubArtifacts
import com.droiddeck.launcher.artifact.RepositorySources
import com.droiddeck.launcher.core.FileUtils
import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object SystemVulkanComponentStore {
    private const val CANDIDATE_CACHE = "system-vulkan-candidates.json"
    private const val RECEIPT = ".droiddeck-receipt.json"
    private const val READY = "READY"
    private const val STATE_SCHEMA = 1
    private const val ROW_PREFIX = "system-vulkan:"
    private val HASH = Regex("[0-9a-f]{64}")
    private val installLock = Any()
    private val stateLock = Any()
    private val launches = ConcurrentHashMap<Int, Snapshot>()
    @Volatile private var readyContext: Context? = null

    data class Candidate(
        val key: String,
        val sourceId: String,
        val repository: String,
        val repositoryId: Long,
        val releaseId: Long,
        val tag: String,
        val publishedAt: String,
        val version: String,
        val variant: String,
        val minSdk: Int,
        val manifestSha256: String,
        val assetId: Long,
        val assetName: String,
        val assetUrl: String,
        val size: Long,
        val sha256: String,
        val treeSha256: String,
        val producerCommit: String,
        val buildUrl: String,
    )

    data class Catalog(
        val candidates: List<Candidate>,
        val failed: List<String>,
        val checkedAt: Long,
        val rateLimited: Boolean = false,
    )

    data class Installed(
        val rowId: String,
        val sha256: String,
        val variant: String,
        val version: String,
        val repository: String?,
        val tag: String?,
        val directory: File,
        val legacy: Boolean,
        val active: Boolean,
        val previous: Boolean,
        val pending: Boolean,
    )

    data class Snapshot(
        val sha256: String,
        val variant: String,
        val version: String,
        val directory: File,
        val legacy: Boolean,
    )

    private data class Activation(
        val active: String? = null,
        val previous: String? = null,
        val pending: String? = null,
        val unreadyStarts: Int = 0,
        val trialDigest: String? = null,
        val trialStarts: Int = 0,
        val damaged: Boolean = false,
    )

    @JvmStatic fun scope(profile: SystemVulkanDriver.Profile): String = "gpu/system-vulkan/${profile.id}"

    @JvmStatic fun sourceBuiltIns(): List<String> = listOf("Droid-Deck/DroidDeck")

    @JvmStatic fun initializeSources(context: Context, profile: SystemVulkanDriver.Profile): List<RepositorySources.Source> {
        val defaults = sourceBuiltIns()
        val rows = defaults.map { RepositorySources.source(it).copy(builtIn = true) }
        return RepositorySources.initialize(context, scope(profile), defaults, rows)
    }

    @JvmStatic fun bootstrap(context: Context, profile: SystemVulkanDriver.Profile): Candidate {
        initializeSources(context, profile)
        val (tag, digest) = when (profile) {
            SystemVulkanDriver.Profile.POWERVR -> BuildConfig.SYSTEM_VULKAN_POWERVR_BOOTSTRAP_TAG to BuildConfig.SYSTEM_VULKAN_POWERVR_BOOTSTRAP_MANIFEST_SHA256
            SystemVulkanDriver.Profile.XCLIPSE -> BuildConfig.SYSTEM_VULKAN_XCLIPSE_BOOTSTRAP_TAG to BuildConfig.SYSTEM_VULKAN_XCLIPSE_BOOTSTRAP_MANIFEST_SHA256
            SystemVulkanDriver.Profile.MALI -> BuildConfig.SYSTEM_VULKAN_MALI_BOOTSTRAP_TAG to BuildConfig.SYSTEM_VULKAN_MALI_BOOTSTRAP_MANIFEST_SHA256
        }
        require(tag.isNotBlank() && HASH.matches(digest) && BuildConfig.COMPONENT_REPOSITORY_ID > 0) {
            "No pinned ${profile.gpuName} System Vulkan bootstrap component is configured"
        }
        val repository = RepositorySources.canonicalRepository(BuildConfig.COMPONENT_REPOSITORY)
        val scope = scope(profile)
        val defaults = sourceBuiltIns()
        val configured = RepositorySources.visible(context, scope, defaults).singleOrNull {
            it.repository == repository
        } ?: throw IOException("Add a source with a compatible ${profile.gpuName} bundle in Components > Sources")
        val release = GitHubArtifacts.release(context, scope, defaults, configured, tag)
        val source = RepositorySources.visible(context, scope, defaults).single { it.id == configured.id }
        require(source.repositoryId == BuildConfig.COMPONENT_REPOSITORY_ID) { "System Vulkan producer repository identity changed" }
        val sidecarName = "droiddeck-component-system-vulkan-${profile.id}.json"
        val sidecarAsset = release.assets.singleOrNull { it.name == sidecarName && it.sha256 == digest }
            ?: throw IOException("Pinned System Vulkan sidecar is absent or changed")
        return candidate(context, scope, defaults, source, release, profile, sidecarAsset)
    }

    @JvmStatic fun rowId(sha256: String): String {
        require(HASH.matches(sha256))
        return ROW_PREFIX + sha256
    }

    @JvmStatic fun rowDigest(rowId: String): String? = rowId.removePrefix(ROW_PREFIX)
        .takeIf { rowId.startsWith(ROW_PREFIX) && HASH.matches(it) }

    @JvmStatic fun refresh(context: Context, profile: SystemVulkanDriver.Profile): Catalog {
        initializeSources(context, profile)
        val scope = scope(profile)
        val defaults = sourceBuiltIns()
        val generation = RepositorySources.generation(context, scope, defaults)
        val candidates = ArrayList<Candidate>()
        val failed = ArrayList<String>()
        var rateLimited = false
        for (initial in RepositorySources.visible(context, scope, defaults)) {
            try {
                val releases = GitHubArtifacts.refreshReleases(context, scope, defaults, initial)
                val source = RepositorySources.list(context, scope, defaults).first {
                    it.id == initial.id && !it.hidden
                }
                val rows = releases.flatMap { release -> candidates(context, scope, defaults, source, release, profile) }
                if (!RepositorySources.isCurrent(context, scope, defaults, source.id, generation)) {
                    throw IOException("System Vulkan source changed while checking")
                }
                writeCandidates(context, scope, source, rows)
                candidates.addAll(rows)
            } catch (error: Exception) {
                if (RepositorySources.generation(context, scope, defaults) == generation &&
                    RepositorySources.isCurrent(context, scope, defaults, initial.id, generation)) {
                    failed.add(initial.repository)
                    rateLimited = rateLimited || GitHubArtifacts.isRateLimited(error)
                    candidates.addAll(readCandidates(context, scope, initial, profile))
                }
            }
        }
        if (RepositorySources.generation(context, scope, defaults) != generation) return cached(context, profile)
        val visible = RepositorySources.visible(context, scope, defaults).mapTo(HashSet()) { it.id }
        return Catalog(deduplicate(candidates.filter { it.sourceId in visible }), failed, cached(context, profile).checkedAt, rateLimited)
    }

    @JvmStatic fun cached(context: Context, profile: SystemVulkanDriver.Profile): Catalog {
        initializeSources(context, profile)
        val scope = scope(profile)
        val rows = RepositorySources.visible(context, scope, sourceBuiltIns())
            .flatMap { readCandidates(context, scope, it, profile) }
        val checkedAt = RepositorySources.visible(context, scope, sourceBuiltIns())
            .maxOfOrNull { candidateCache(context, scope, it).lastModified() } ?: 0L
        return Catalog(deduplicate(rows), emptyList(), checkedAt)
    }

    @JvmStatic fun install(
        context: Context,
        profile: SystemVulkanDriver.Profile,
        candidate: Candidate,
        activate: Boolean,
        guestsStopped: Boolean,
    ): Installed {
        initializeSources(context, profile)
        require(candidate.variant == profile.id) { "System Vulkan component targets another GPU" }
        require(Build.VERSION.SDK_INT >= 26) { "System Vulkan components require Android 8 or newer" }
        require(Build.VERSION.SDK_INT >= candidate.minSdk) { "System Vulkan component requires a newer Android version" }
        val source = RepositorySources.visible(context, scope(profile), sourceBuiltIns()).firstOrNull {
            it.id == candidate.sourceId && it.repository == candidate.repository && it.repositoryId == candidate.repositoryId
        } ?: throw IOException("System Vulkan source is hidden, removed, or changed")
        val archiveAsset = GitHubArtifacts.Asset(candidate.assetId, candidate.assetName, candidate.assetUrl, candidate.size, candidate.sha256)
        val cache = File(context.cacheDir, "components/system-vulkan/${candidate.sha256}.tar.zst")
        GitHubArtifacts.downloadReleaseAsset(
            context, scope(profile), sourceBuiltIns(), source, candidate.tag, archiveAsset,
            cache, SystemVulkanManifest.MAX_ARCHIVE_BYTES,
        )
        synchronized(installLock) {
            val root = root(context)
            if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create System Vulkan component directory")
            val target = File(root, candidate.sha256)
            if (!complete(target, profile)) {
                if (target.exists()) {
                    if (!guestsStopped) throw IOException("Incomplete System Vulkan component may still be in use")
                    quarantine(root, target)
                }
                extract(cache, target, candidate, source)
            }
            recordOrigin(context, candidate)
        }
        if (activate) activate(context, profile, candidate.sha256, guestsStopped)
        return installed(context, profile).first { it.sha256 == candidate.sha256 }
    }

    @JvmStatic fun activate(
        context: Context,
        profile: SystemVulkanDriver.Profile,
        sha256: String,
        guestsStopped: Boolean,
    ) {
        require(HASH.matches(sha256)) { "Invalid System Vulkan component identity" }
        installed(context, profile).firstOrNull { it.sha256 == sha256 }?.directory
            ?: throw IllegalArgumentException("System Vulkan component is incomplete")
        synchronized(stateLock) {
            val current = readState(context, profile)
            if (current.damaged) throw IOException("System Vulkan activation state is damaged")
            val next = if (guestsStopped) {
                if (current.active == sha256) current.copy(pending = null, unreadyStarts = 0)
                else Activation(
                    active = sha256,
                    previous = current.active?.takeIf { it != sha256 },
                    trialDigest = sha256,
                )
            } else if (current.active == sha256) current.copy(pending = null)
            else current.copy(pending = sha256)
            writeState(context, profile, next)
        }
    }

    @JvmStatic fun capture(
        context: Context,
        profile: SystemVulkanDriver.Profile,
        guestsStopped: Boolean,
    ): Snapshot = synchronized(stateLock) {
            var state = readState(context, profile)
            if (state.damaged) throw IOException("System Vulkan activation state is damaged")
            if (state.active == null) {
                val initial = findInitial(context, profile)
                    ?: throw IOException("Download a ${profile.gpuName} System Vulkan component before starting a session")
                state = state.copy(active = initial.sha256)
                writeState(context, profile, state)
            }
            if (guestsStopped && state.pending != null) {
                val promoted = promotePending(context, profile, state)
                if (promoted != state) {
                    state = promoted
                    writeState(context, profile, state)
                }
            }
            var active = state.active?.let { installedByDigest(context, profile, it) }
            val trialFailed = state.trialDigest == state.active && state.trialStarts >= 2
            if (trialFailed) {
                val previous = state.previous?.let { installedByDigest(context, profile, it) }
                if (previous != null) {
                    state = Activation(active = previous.sha256, pending = state.pending)
                    writeState(context, profile, state)
                    active = previous
                }
            }
            if (active == null) {
                val failures = state.unreadyStarts + 1
                val previous = state.previous?.let { installedByDigest(context, profile, it) }
                if (failures >= 2 && previous != null) {
                    state = Activation(active = previous.sha256, pending = state.pending)
                    writeState(context, profile, state)
                    active = previous
                } else {
                    writeState(context, profile, state.copy(unreadyStarts = failures))
                    throw IOException("Active System Vulkan component is incomplete")
                }
            } else if (state.unreadyStarts != 0) {
                writeState(context, profile, state.copy(unreadyStarts = 0))
            }
            Snapshot(active.sha256, profile.id, active.version, active.directory, active.legacy)
        }

    @JvmStatic fun installed(context: Context, profile: SystemVulkanDriver.Profile): List<Installed> {
        initializeSources(context, profile)
        return synchronized(stateLock) { installedUnlocked(context, profile, readState(context, profile)) }
    }

    @JvmStatic fun isReady(context: Context, profile: SystemVulkanDriver.Profile): Boolean {
        initializeSources(context, profile)
        return synchronized(stateLock) {
            val state = readState(context, profile)
            !state.damaged && (state.active?.let { installedByDigest(context, profile, it) } != null ||
                (state.active == null && findInitial(context, profile) != null))
        }
    }

    @JvmStatic fun launchStarting(context: Context, snapshot: Snapshot, generation: Int) {
        synchronized(stateLock) {
            if (launches.containsKey(generation)) return
            val profile = SystemVulkanDriver.Profile.values().single { it.id == snapshot.variant }
            val state = readState(context, profile)
            if (state.active == snapshot.sha256 && state.trialDigest == snapshot.sha256) {
                writeState(context, profile, state.copy(trialStarts = (state.trialStarts + 1).coerceAtMost(2)))
                readyContext = context.applicationContext
                launches[generation] = snapshot
            }
        }
    }

    @JvmStatic fun sessionReady(generation: Int) {
        val snapshot = launches.remove(generation) ?: return
        val context = readyContext ?: return
        synchronized(stateLock) {
            val profile = SystemVulkanDriver.Profile.values().single { it.id == snapshot.variant }
            val state = readState(context, profile)
            if (state.active == snapshot.sha256 && state.trialDigest == snapshot.sha256) {
                writeState(context, profile, state.copy(previous = null, trialDigest = null, trialStarts = 0))
            }
        }
    }

    @JvmStatic fun sessionStopped(generation: Int) {
        launches.remove(generation)
    }

    @JvmStatic fun prune(context: Context, profile: SystemVulkanDriver.Profile, guestsStopped: Boolean) {
        synchronized(installLock) {
            val roots = listOf(root(context), legacyRoot(context))
            roots.forEach { base ->
                if (!safeRoot(base)) return@forEach
                base.listFiles()?.filter { it.name.matches(Regex("\\.stage-[0-9a-f-]{36}")) }?.forEach {
                    if (!it.toPath().let(java.nio.file.Files::isSymbolicLink)) FileUtils.delete(it)
                }
            }
        }
        if (guestsStopped) {
            synchronized(stateLock) {
                val state = readState(context, profile)
                val promoted = promotePending(context, profile, state)
                if (promoted != state) writeState(context, profile, promoted)
            }
        }
    }

    private fun promotePending(
        context: Context,
        profile: SystemVulkanDriver.Profile,
        state: Activation,
    ): Activation {
        val digest = state.pending ?: return state
        if (digest == state.active) return state.copy(pending = null)
        val pending = installedByDigest(context, profile, digest) ?: return state
        return Activation(
            active = pending.sha256,
            previous = state.active,
            trialDigest = pending.sha256,
        )
    }

    private fun candidates(
        context: Context,
        scope: String,
        defaults: List<String>,
        source: RepositorySources.Source,
        release: GitHubArtifacts.Release,
        profile: SystemVulkanDriver.Profile,
    ): List<Candidate> {
        val sidecarName = "droiddeck-component-system-vulkan-${profile.id}.json"
        return release.assets.filter { it.name == sidecarName }.map { sidecarAsset ->
            candidate(context, scope, defaults, source, release, profile, sidecarAsset)
        }
    }

    private fun candidate(
        context: Context,
        scope: String,
        defaults: List<String>,
        source: RepositorySources.Source,
        release: GitHubArtifacts.Release,
        profile: SystemVulkanDriver.Profile,
        sidecarAsset: GitHubArtifacts.Asset,
    ): Candidate {
        val sidecar = SystemVulkanManifest.sidecar(
                GitHubArtifacts.readReleaseAsset(
                    context, scope, defaults, source, release.tag, sidecarAsset,
                    SystemVulkanManifest.MAX_MANIFEST_BYTES,
                ),
                source.repository,
            )
        require(sidecar.variant == profile.id)
        val archive = release.assets.singleOrNull { it.name == sidecar.artifact.name }
            ?: throw IOException("System Vulkan release is missing ${sidecar.artifact.name}")
        require(archive.size == sidecar.artifact.size && archive.sha256 == sidecar.artifact.sha256) {
            "System Vulkan archive metadata does not match GitHub"
        }
        return Candidate(
            "${source.id}:${archive.sha256}", source.id, source.repository,
            source.repositoryId ?: throw IOException("System Vulkan source identity is not bound"),
            release.id, release.tag, release.publishedAt, sidecar.version, sidecar.variant, sidecar.minSdk,
            sidecarAsset.sha256,
            archive.id, archive.name, archive.url, archive.size, archive.sha256,
            sidecar.artifact.treeSha256, sidecar.provenance.commit, sidecar.provenance.buildUrl,
        )
    }

    private fun deduplicate(rows: List<Candidate>): List<Candidate> = rows
        .sortedWith(compareByDescending<Candidate> { it.publishedAt }.thenBy { it.repository })
        .distinctBy { it.key }

    private fun extract(
        archive: File,
        target: File,
        candidate: Candidate,
        source: RepositorySources.Source,
    ) {
        val base = target.parentFile ?: throw IOException("Invalid System Vulkan store")
        require(target.name == candidate.sha256 && safeRoot(base))
        val stage = File(base, ".stage-${UUID.randomUUID()}")
        if (!stage.mkdir()) throw IOException("Cannot stage System Vulkan component")
        try {
            TarArchiveInputStream(ZstdInputStream(BufferedInputStream(FileInputStream(archive), 1 shl 16))).use { tar ->
                val manifestEntry = tar.nextTarEntry ?: throw IOException("System Vulkan archive is empty")
                require(manifestEntry.isFile && manifestEntry.name == "component.json" &&
                    manifestEntry.size in 1..SystemVulkanManifest.MAX_MANIFEST_BYTES.toLong()) {
                    "System Vulkan archive must start with component.json"
                }
                val manifestBytes = readEntry(tar, manifestEntry.size, SystemVulkanManifest.MAX_MANIFEST_BYTES.toLong())
                val expected = SystemVulkanManifest.Sidecar(
                    candidate.variant, candidate.version, candidate.minSdk,
                    SystemVulkanManifest.Artifact(candidate.assetName, candidate.size, candidate.sha256, candidate.treeSha256),
                    SystemVulkanManifest.Provenance(candidate.repository, candidate.producerCommit, candidate.buildUrl),
                )
                val bundle = SystemVulkanManifest.bundle(manifestBytes, expected)
                require(Build.VERSION.SDK_INT >= bundle.minSdk) { "System Vulkan component requires Android ${bundle.minSdk}" }
                val inventory = bundle.files.associateBy { it.path }
                val parents = inventory.keys.flatMap { path ->
                    val segments = path.split('/')
                    (1 until segments.size).map { segments.take(it).joinToString("/") }
                }.toSet()
                val seen = HashSet<String>()
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    val path = SystemVulkanManifest.path(entry.name)
                    require(seen.add(path) && path != "component.json") { "Duplicate System Vulkan archive path: $path" }
                    if (entry.isDirectory) {
                        require(path in parents) { "Unexpected System Vulkan directory: $path" }
                        safeDestination(stage, path).let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Cannot create $path") }
                        continue
                    }
                    require(entry.isFile && !entry.isSymbolicLink && !entry.isLink) { "Unsupported System Vulkan archive entry: $path" }
                    val pin = inventory[path] ?: throw IOException("Unexpected System Vulkan archive file: $path")
                    require(entry.size == pin.size && normalizedMode(entry.mode) == pin.mode) { "System Vulkan metadata mismatch: $path" }
                    val destination = safeDestination(stage, path)
                    destination.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Cannot create parent for $path") }
                    val digest = MessageDigest.getInstance("SHA-256")
                    FileOutputStream(destination).use { output -> copyEntry(tar, output, pin.size, digest) }
                    require(hex(digest.digest()) == pin.sha256) { "System Vulkan file checksum mismatch: $path" }
                    if (pin.mode == 493 && !destination.setExecutable(true, false)) throw IOException("Cannot make $path executable")
                }
                require(seen.filterNot { it in parents }.toSet() == inventory.keys) { "System Vulkan archive does not match its inventory" }
                writeIcd(File(stage, "icd.json"), File(target, "lib/libhybris-vulkan-icd.so.0"))
                writeReceipt(stage, candidate, source, bundle)
                File(stage, READY).writeText(candidate.sha256 + "\n")
            }
            if (target.exists() || !stage.renameTo(target)) throw IOException("Cannot publish System Vulkan component")
        } finally {
            if (stage.exists()) FileUtils.delete(stage)
        }
    }

    private fun writeIcd(file: File, library: File) {
        val json = JSONObject().put("file_format_version", "1.0.0").put("ICD", JSONObject()
            .put("library_path", library.path).put("api_version", "1.3.0"))
        file.writeText(json.toString())
    }

    private fun writeReceipt(
        stage: File,
        candidate: Candidate,
        source: RepositorySources.Source,
        bundle: SystemVulkanManifest.Bundle,
    ) {
        val json = JSONObject().put("schema", 1).put("component", SystemVulkanManifest.COMPONENT)
            .put("variant", bundle.variant).put("version", bundle.version)
            .put("sha256", candidate.sha256).put("treeSha256", bundle.treeSha256)
            .put("sourceId", source.id).put("repository", source.repository)
            .put("repositoryId", source.repositoryId).put("releaseId", candidate.releaseId)
            .put("tag", candidate.tag).put("assetId", candidate.assetId)
            .put("producerCommit", candidate.producerCommit).put("buildUrl", candidate.buildUrl)
        RepositorySources.atomicWrite(File(stage, RECEIPT), json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun writeCandidates(context: Context, scope: String, source: RepositorySources.Source, rows: List<Candidate>) {
        val array = JSONArray()
        rows.forEach { row -> array.put(encodeCandidate(row)) }
        val json = JSONObject().put("schema", 1).put("sourceId", source.id)
            .put("repository", source.repository).put("repositoryId", source.repositoryId).put("candidates", array)
        RepositorySources.atomicWrite(candidateCache(context, scope, source), json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun readCandidates(
        context: Context,
        scope: String,
        source: RepositorySources.Source,
        profile: SystemVulkanDriver.Profile,
    ): List<Candidate> = runCatching {
        val json = AtomicFile(candidateCache(context, scope, source)).openRead().use {
            JSONObject(RepositorySources.readBounded(it, 2 * 1024 * 1024).toString(Charsets.UTF_8))
        }
        require(json.getInt("schema") == 1 && json.getString("sourceId") == source.id &&
            json.getString("repository") == source.repository && json.getLong("repositoryId") == source.repositoryId)
        val array = json.getJSONArray("candidates")
        require(array.length() <= 500)
        (0 until array.length()).map { decodeCandidate(array.getJSONObject(it)) }.filter { it.variant == profile.id }
    }.getOrDefault(emptyList())

    private fun encodeCandidate(row: Candidate): JSONObject = JSONObject()
        .put("key", row.key).put("sourceId", row.sourceId).put("repository", row.repository)
        .put("repositoryId", row.repositoryId).put("releaseId", row.releaseId).put("tag", row.tag)
        .put("publishedAt", row.publishedAt).put("version", row.version).put("variant", row.variant)
        .put("minSdk", row.minSdk)
        .put("manifestSha256", row.manifestSha256)
        .put("assetId", row.assetId).put("assetName", row.assetName).put("assetUrl", row.assetUrl)
        .put("size", row.size).put("sha256", row.sha256).put("treeSha256", row.treeSha256)
        .put("producerCommit", row.producerCommit).put("buildUrl", row.buildUrl)

    private fun decodeCandidate(json: JSONObject): Candidate = Candidate(
        json.getString("key"), json.getString("sourceId"), json.getString("repository"), json.getLong("repositoryId"),
        json.getLong("releaseId"), json.getString("tag"), json.getString("publishedAt"), json.getString("version"),
        json.getString("variant"), json.getInt("minSdk"), json.getString("manifestSha256"),
        json.getLong("assetId"), json.getString("assetName"), json.getString("assetUrl"),
        json.getLong("size"), json.getString("sha256"), json.getString("treeSha256"),
        json.getString("producerCommit"), json.getString("buildUrl"),
    ).also {
        require(it.key == "${it.sourceId}:${it.sha256}" && HASH.matches(it.sha256) && HASH.matches(it.treeSha256) &&
            HASH.matches(it.manifestSha256) && it.minSdk in 26..100)
    }

    private fun candidateCache(context: Context, scope: String, source: RepositorySources.Source): File {
        return File(RepositorySources.cacheDirectory(context, scope, source), CANDIDATE_CACHE)
    }

    private fun recordOrigin(context: Context, candidate: Candidate) {
        val file = File(root(context), "origins/${candidate.sha256}/${candidate.sourceId}.json")
        val json = encodeCandidate(candidate).put("schema", 1)
        RepositorySources.atomicWrite(file, json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun installedByDigest(context: Context, profile: SystemVulkanDriver.Profile, digest: String): Installed? {
        if (!HASH.matches(digest)) return null
        val state = readState(context, profile)
        val current = File(root(context), digest)
        receipt(current, profile)?.let { return installed(current, it, legacy = false, state) }
        val legacy = File(legacyRoot(context), digest)
        if (!legacyComplete(legacy, profile)) return null
        return Installed(
            rowId(digest), digest, profile.id, "Legacy local", null, null, legacy, true,
            state.active == digest, state.previous == digest, state.pending == digest,
        )
    }

    private fun findInitial(context: Context, profile: SystemVulkanDriver.Profile): Installed? =
        installedUnlocked(context, profile, readState(context, profile)).maxByOrNull { it.directory.lastModified() }

    private fun installedUnlocked(
        context: Context,
        profile: SystemVulkanDriver.Profile,
        state: Activation,
    ): List<Installed> {
        val rows = ArrayList<Installed>()
        root(context).listFiles()?.filter { it.isDirectory && HASH.matches(it.name) }?.forEach { directory ->
            receipt(directory, profile)?.let { receipt -> rows.add(installed(directory, receipt, legacy = false, state)) }
        }
        legacyRoot(context).listFiles()?.filter { it.isDirectory && HASH.matches(it.name) }?.forEach { directory ->
            if (legacyComplete(directory, profile) && rows.none { it.sha256 == directory.name }) {
                rows.add(Installed(
                    rowId(directory.name), directory.name, profile.id, "Legacy local", null, null, directory, true,
                    state.active == directory.name, state.previous == directory.name, state.pending == directory.name,
                ))
            }
        }
        return rows.sortedWith(compareByDescending<Installed> { it.active }.thenByDescending { it.directory.lastModified() })
    }

    private fun installed(directory: File, receipt: JSONObject, legacy: Boolean, state: Activation): Installed = Installed(
        rowId(directory.name), directory.name, receipt.getString("variant"), receipt.getString("version"),
        receipt.optString("repository").takeIf { it.isNotEmpty() }, receipt.optString("tag").takeIf { it.isNotEmpty() },
        directory, legacy, state.active == directory.name, state.previous == directory.name, state.pending == directory.name,
    )

    private fun receipt(directory: File, profile: SystemVulkanDriver.Profile): JSONObject? = runCatching {
        if (!complete(directory, profile)) return null
        AtomicFile(File(directory, RECEIPT)).openRead().use {
            JSONObject(RepositorySources.readBounded(it, 256 * 1024).toString(Charsets.UTF_8))
        }.also { json ->
            require(json.getInt("schema") == 1 && json.getString("component") == SystemVulkanManifest.COMPONENT &&
                json.getString("variant") == profile.id && json.getString("sha256") == directory.name)
        }
    }.getOrNull()

    private fun complete(directory: File, profile: SystemVulkanDriver.Profile): Boolean {
        if (!safeComponentPath(directory) || !File(directory, READY).isFile || !File(directory, RECEIPT).isFile ||
            !File(directory, "icd.json").isFile) return false
        return SystemVulkanManifest.required(profile.id).all { File(directory, it).let { file -> file.isFile && file.length() > 0 } } &&
            File(directory, "run-proton.py").canExecute()
    }

    private fun legacyComplete(directory: File, profile: SystemVulkanDriver.Profile): Boolean {
        if (!safeLegacyPath(directory) || !File(directory, "icd.json").isFile) return false
        return SystemVulkanManifest.required(profile.id).all { File(directory, it).let { file -> file.isFile && file.length() > 0 } } &&
            File(directory, "run-proton.py").canExecute()
    }

    private fun componentDirectory(context: Context, digest: String): File {
        val current = File(root(context), digest)
        if (current.exists()) return current
        return File(legacyRoot(context), digest)
    }

    private fun root(context: Context) = File(context.filesDir, "components/system-vulkan")
    private fun legacyRoot(context: Context) = File(context.filesDir, "system_vulkan")
    private fun stateFile(context: Context, profile: SystemVulkanDriver.Profile) = File(root(context), "state-${profile.id}.json")

    private fun readState(context: Context, profile: SystemVulkanDriver.Profile): Activation {
        val file = stateFile(context, profile)
        if (!file.exists() && !File(file.path + ".bak").exists()) return Activation()
        return runCatching {
            AtomicFile(file).openRead().use {
                val json = JSONObject(RepositorySources.readBounded(it, 64 * 1024).toString(Charsets.UTF_8))
                require(json.getInt("schema") == STATE_SCHEMA && json.getString("variant") == profile.id)
                fun digest(name: String) = json.optString(name).takeIf { value -> value.isNotEmpty() }
                    ?.also { value -> require(HASH.matches(value)) }
                Activation(
                    active = digest("active"),
                    previous = digest("previous"),
                    pending = digest("pending"),
                    unreadyStarts = json.getInt("unreadyStarts").also { count -> require(count in 0..2) },
                    trialDigest = digest("trialDigest"),
                    trialStarts = json.optInt("trialStarts", 0).also { count -> require(count in 0..2) },
                )
            }
        }.getOrElse { Activation(damaged = true) }
    }

    private fun writeState(context: Context, profile: SystemVulkanDriver.Profile, state: Activation) {
        val json = JSONObject().put("schema", STATE_SCHEMA).put("variant", profile.id)
            .put("active", state.active ?: "").put("previous", state.previous ?: "")
            .put("pending", state.pending ?: "").put("unreadyStarts", state.unreadyStarts)
            .put("trialDigest", state.trialDigest ?: "").put("trialStarts", state.trialStarts)
        RepositorySources.atomicWrite(stateFile(context, profile), json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun quarantine(base: File, target: File) {
        require(safeRoot(base) && safeComponentPath(target))
        val stage = File(base, ".stage-${UUID.randomUUID()}")
        if (!target.renameTo(stage)) throw IOException("Cannot quarantine incomplete System Vulkan component")
        FileUtils.delete(stage)
    }

    private fun safeRoot(base: File): Boolean = !java.nio.file.Files.isSymbolicLink(base.toPath()) && (
        (base.name == "system-vulkan" && base.parentFile?.name == "components") || base.name == "system_vulkan"
    )

    private fun safeComponentPath(directory: File): Boolean = directory.parentFile?.let(::safeRoot) == true &&
        directory.parentFile.name == "system-vulkan" && HASH.matches(directory.name) &&
        !java.nio.file.Files.isSymbolicLink(directory.toPath())

    private fun safeLegacyPath(directory: File): Boolean = directory.parentFile?.name == "system_vulkan" &&
        HASH.matches(directory.name) && !java.nio.file.Files.isSymbolicLink(directory.parentFile.toPath()) &&
        !java.nio.file.Files.isSymbolicLink(directory.toPath())

    private fun safeDestination(root: File, path: String): File {
        val destination = File(root, path)
        val rootPath = root.toPath().toAbsolutePath().normalize()
        val targetPath = destination.toPath().toAbsolutePath().normalize()
        require(targetPath.parent != null && targetPath.startsWith(rootPath) && targetPath != rootPath) { "Unsafe System Vulkan destination" }
        return destination
    }

    private fun normalizedMode(mode: Int) = mode and 0b111111111

    private fun readEntry(input: TarArchiveInputStream, size: Long, limit: Long): ByteArray {
        require(size in 1..limit)
        val output = java.io.ByteArrayOutputStream(size.toInt())
        val buffer = ByteArray(8192)
        var remaining = size
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw IOException("Truncated System Vulkan archive entry")
            output.write(buffer, 0, count)
            remaining -= count
        }
        return output.toByteArray()
    }

    private fun copyEntry(input: TarArchiveInputStream, output: FileOutputStream, size: Long, digest: MessageDigest) {
        val buffer = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw IOException("Truncated System Vulkan archive entry")
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            remaining -= count
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
