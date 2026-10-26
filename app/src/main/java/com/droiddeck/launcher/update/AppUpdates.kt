package com.droiddeck.launcher.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.R
import com.droiddeck.launcher.artifact.GitHubArtifacts
import com.droiddeck.launcher.artifact.RepositorySources
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.concurrent.thread

object AppUpdates {
    private const val REPO = "Droid-Deck/DroidDeck"
    private const val CI_REPO = "Droid-Deck/DroidDeck-CI"
    const val SOURCE_SCOPE = "updates/app"
    private const val PREFS = "app_updates"
    private const val KEY_CATALOG = "catalog"
    private const val CATALOG_CACHE_VERSION = "v2"
    private const val KEY_CHANNEL = "channel"
    private const val KEY_SOURCE = "source"
    private const val KEY_SIGNER_STAMP = "signer_stamp"
    private const val KEY_SIGNER_TRUSTED = "signer_trusted"
    private const val CATALOG_SCHEMA = 1

    enum class Channel { STABLE, NIGHTLY, TEST }

    data class Follow(val channel: Channel, val pr: Int = 0, val sourceId: String = "")

    class Apk(
        val name: String,
        val url: String,
        val size: Long,
        val sha256: String,
        val packageName: String,
        val versionCode: Int,
        val signerSha256: String,
    )

    class Release(
        val tag: String,
        val title: String,
        val summary: String,
        val commit: String,
        val pr: Int,
        val version: String?,
        val versionCode: Int,
        val publishedAt: Long,
        val url: String,
        val apk: Apk?,
    )

    class PreviewChange(
        val commit: String,
        val title: String,
        val summary: String,
        val publishedAt: Long,
    )

    class Catalog(
        val stable: Release?,
        val preview: Release?,
        val tests: List<Release>,
        val checkedAt: Long,
        val recentPreviews: List<PreviewChange> = emptyList(),
        val sourceId: String = "",
    )

    @JvmStatic fun sourceBuiltIns(): List<String> = listOf(CI_REPO)

    @JvmStatic fun sources(context: Context): List<RepositorySources.Source> =
        RepositorySources.list(context, SOURCE_SCOPE, sourceBuiltIns())

    @JvmStatic fun saveSources(context: Context, rows: List<RepositorySources.Source>): List<RepositorySources.Source> =
        RepositorySources.save(context, SOURCE_SCOPE, sourceBuiltIns(), rows)

    class Installed(
        val commit: String,
        val pr: Int,
        val version: String,
        val versionCode: Int,
        val updatable: Boolean,
        val committedAt: Long = 0L,
        val ciBuild: Boolean = BuildConfig.CI_BUILD,
        val signerDigests: Set<String> = if (updatable) setOf(BuildConfig.RELEASE_SIGNER.lowercase()) else emptySet(),
        val signerLineageDigests: Set<String> = emptySet(),
        val signerRevision: Int = 0,
    )

    enum class PreviewHistoryKind { CURRENT, BEHIND, RECENT }

    class PreviewHistory(
        val kind: PreviewHistoryKind,
        val buildsBehind: Int,
        val changes: List<PreviewChange>,
    )

    enum class Offer {
        CURRENT,
        UPDATE,
        SWITCH,
        AHEAD,
        GONE,
    }

    internal enum class SignerCompatibility { MATCH, NEEDS_APK_LINEAGE, INCOMPATIBLE }

    private data class InstalledSignerState(
        val identity: SelfInstaller.VerifiedIdentity,
        val trusted: Boolean,
        val stamp: String,
    )

    @Volatile private var installedIdentity = SelfInstaller.VerifiedIdentity(emptySet(), emptySet())
    @Volatile private var installedTrusted = false
    var signerRevision by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        applyInstalledSignerIdentity(SelfInstaller.VerifiedIdentity(emptySet(), emptySet()), false)
        val state = runCatching { installedSignerState(context) }.getOrNull() ?: return
        applyInstalledSignerIdentity(state.identity, state.trusted)
        if (state.trusted || Build.VERSION.SDK_INT >= 28 ||
            !Regex("[0-9a-fA-F]{64}").matches(BuildConfig.RELEASE_SIGNER)) return
        val preferences = prefs(context)
        if (preferences.getString(KEY_SIGNER_STAMP, null) == state.stamp &&
            preferences.contains(KEY_SIGNER_TRUSTED)) {
            applyInstalledSignerIdentity(state.identity, preferences.getBoolean(KEY_SIGNER_TRUSTED, false))
            return
        }
        thread(name = "DroidDeck update signer", isDaemon = true) {
            val identity = runCatching {
                SelfInstaller.verifiedApk(File(context.applicationInfo.sourceDir)).producer
            }.getOrNull() ?: return@thread
            val trusted = trustedInstalledIdentity(identity)
            preferences.edit().putString(KEY_SIGNER_STAMP, state.stamp)
                .putBoolean(KEY_SIGNER_TRUSTED, trusted).commit()
            Handler(Looper.getMainLooper()).post { applyInstalledSignerIdentity(identity, trusted) }
        }
    }

    internal fun applyInstalledSignerIdentity(identity: SelfInstaller.VerifiedIdentity, trusted: Boolean) {
        installedIdentity = identity
        installedTrusted = trusted
        signerRevision++
    }

    @Suppress("DEPRECATION")
    private fun installedSignerState(context: Context): InstalledSignerState {
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        val current = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            info.signatures.orEmpty()
        }
        val lineage = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.signingCertificateHistory.orEmpty()
        } else {
            emptyArray()
        }
        fun digests(signatures: Array<out android.content.pm.Signature>): Set<String> =
            signatures.mapTo(LinkedHashSet()) { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            }
        val currentDigests = digests(current)
        val lineageDigests = digests(lineage)
        val identity = SelfInstaller.VerifiedIdentity(currentDigests, lineageDigests)
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val apk = File(context.applicationInfo.sourceDir)
        val stamp = listOf(
            info.lastUpdateTime,
            versionCode,
            apk.path,
            apk.length(),
            apk.lastModified(),
            BuildConfig.RELEASE_SIGNER.lowercase(),
        ).joinToString(":")
        return InstalledSignerState(identity, trustedInstalledIdentity(identity), stamp)
    }

    fun installed(): Installed {
        val identity = installedIdentity
        return Installed(
            BuildConfig.SOURCE_COMMIT,
            BuildConfig.PR_NUMBER,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
            installedTrusted,
            BuildConfig.COMMIT_TIME,
            signerDigests = identity.current,
            signerLineageDigests = identity.lineage,
            signerRevision = signerRevision,
        )
    }

    internal fun trustedInstalledIdentity(
        identity: SelfInstaller.VerifiedIdentity,
        releaseSigner: String = BuildConfig.RELEASE_SIGNER,
    ): Boolean = Regex("[0-9a-fA-F]{64}").matches(releaseSigner) &&
        (identity.current + identity.lineage).any { it.equals(releaseSigner, ignoreCase = true) }

    fun release(catalog: Catalog, follow: Follow): Release? = when (follow.channel) {
        Channel.STABLE -> catalog.stable
        Channel.NIGHTLY -> catalog.preview
        Channel.TEST -> catalog.tests.firstOrNull { it.pr == follow.pr }
    }

    fun offer(catalog: Catalog, follow: Follow, me: Installed = installed()): Offer {
        val r = release(catalog, follow) ?: return Offer.GONE
        if (isRunning(r, me)) return Offer.CURRENT
        return when (follow.channel) {
            Channel.STABLE -> when {
                me.pr != 0 -> Offer.SWITCH
                compareVersions(r.version ?: "", me.version) > 0 -> Offer.UPDATE
                else -> Offer.AHEAD
            }
            Channel.NIGHTLY -> if (me.pr != 0) Offer.SWITCH else Offer.UPDATE
            Channel.TEST -> if (me.pr == r.pr) Offer.UPDATE else Offer.SWITCH
        }
    }

    fun hasUpdate(catalog: Catalog?, follow: Follow): Boolean {
        if (catalog == null) return false
        val me = installed()
        val r = release(catalog, follow) ?: return false
        return me.updatable && offer(catalog, follow, me) == Offer.UPDATE && installBlock(r, me) == null
    }

    fun isRunning(r: Release, me: Installed = installed()): Boolean {
        if (me.commit.length < 7 || r.commit.length < 7 || r.pr != me.pr) return false
        return me.commit.startsWith(r.commit, ignoreCase = true) ||
            r.commit.startsWith(me.commit, ignoreCase = true)
    }

    fun previewHistory(catalog: Catalog, me: Installed = installed()): PreviewHistory? {
        if (!me.ciBuild || catalog.recentPreviews.isEmpty()) return null
        val stable = catalog.stable
        if (
            me.pr != 0 || stable?.let { isRunning(it, me) } == true ||
            stable?.version?.let { compareVersions(me.version, it) <= 0 } == true
        ) {
            return PreviewHistory(PreviewHistoryKind.RECENT, 0, catalog.recentPreviews)
        }

        val matching = catalog.recentPreviews.indices.filter {
            matchesInstalledCommit(catalog.recentPreviews[it].commit, me.commit)
        }
        if (matching.size != 1) {
            return PreviewHistory(PreviewHistoryKind.RECENT, 0, catalog.recentPreviews)
        }
        val index = matching.single()
        return if (index == 0) {
            PreviewHistory(PreviewHistoryKind.CURRENT, 0, emptyList())
        } else {
            PreviewHistory(PreviewHistoryKind.BEHIND, index, catalog.recentPreviews.take(index))
        }
    }

    private fun matchesInstalledCommit(published: String, installed: String): Boolean {
        if (!Regex("^[0-9a-fA-F]{7,40}$").matches(installed)) return false
        return published.equals(installed, ignoreCase = true) ||
            (installed.length < 40 && published.startsWith(installed, ignoreCase = true))
    }

    fun installBlock(r: Release, me: Installed = installed()): InstallBlock? = when {
        r.apk == null -> InstallBlock(R.string.upd_block_no_apk)
        signerCompatibility(r.apk, me) == SignerCompatibility.INCOMPATIBLE ->
            InstallBlock(R.string.selfinst_wrong_signer)
        r.apk.versionCode < me.versionCode -> InstallBlock(R.string.upd_block_version_code, r.apk.versionCode, me.versionCode)
        else -> null
    }

    internal fun signerCompatibility(apk: Apk, me: Installed): SignerCompatibility = when {
        !me.updatable -> SignerCompatibility.INCOMPATIBLE
        me.signerDigests.any { it.equals(apk.signerSha256, ignoreCase = true) } -> SignerCompatibility.MATCH
        else -> SignerCompatibility.NEEDS_APK_LINEAGE
    }

    class InstallBlock(@androidx.annotation.StringRes val text: Int, private vararg val args: Any) {
        fun message(context: Context): String = context.getString(text, *args)
    }

    fun follow(context: Context, catalog: Catalog?, me: Installed = installed()): Follow {
        stored(context)?.let { return it }
        val initialSource = RepositorySources.visible(context, SOURCE_SCOPE, sourceBuiltIns()).firstOrNull()?.id.orEmpty()
        val inferred = when {
            me.pr != 0 -> Follow(Channel.TEST, me.pr, initialSource)
            catalog == null -> return Follow(Channel.STABLE, sourceId = initialSource)
            catalog.stable != null && isRunning(catalog.stable, me) -> Follow(Channel.STABLE, sourceId = initialSource)
            me.updatable -> Follow(Channel.NIGHTLY, sourceId = initialSource)
            else -> Follow(Channel.STABLE, sourceId = initialSource)
        }
        setFollow(context, inferred)
        return inferred
    }

    fun setFollow(context: Context, follow: Follow) {
        val value = if (follow.channel == Channel.TEST) "test:${follow.pr}"
        else follow.channel.name.lowercase()
        prefs(context).edit().putString(KEY_CHANNEL, value).putString(KEY_SOURCE, follow.sourceId).apply()
    }

    private fun stored(context: Context): Follow? {
        val v = prefs(context).getString(KEY_CHANNEL, null) ?: return null
        val sourceId = if (prefs(context).contains(KEY_SOURCE)) prefs(context).getString(KEY_SOURCE, "").orEmpty()
        else RepositorySources.visible(context, SOURCE_SCOPE, sourceBuiltIns()).firstOrNull()?.id.orEmpty()
        return when {
            v == "stable" -> Follow(Channel.STABLE, sourceId = sourceId)
            v == "nightly" || v == "preview" -> Follow(Channel.NIGHTLY, sourceId = sourceId)
            v.startsWith("test:") -> v.removePrefix("test:").toIntOrNull()?.let {
                Follow(Channel.TEST, it, sourceId)
            }
            else -> null
        }
    }

    @JvmOverloads fun cached(context: Context, sourceId: String = follow(context, null).sourceId): Catalog? {
        if (sourceId.isEmpty()) return null
        val snapshot = synchronized(RepositorySources.lock) {
            val source = RepositorySources.visible(context, SOURCE_SCOPE, sourceBuiltIns())
                .singleOrNull { it.id == sourceId } ?: return@synchronized null
            val generation = RepositorySources.generation(context, SOURCE_SCOPE, sourceBuiltIns())
            val preferences = prefs(context)
            val cacheKey = catalogCacheKey(source)
            var raw = preferences.getString(cacheKey, null)
            if (raw == null) {
                val legacyScopedKey = "$KEY_CATALOG:$sourceId"
                val legacyRaw = preferences.getString(legacyScopedKey, null)
                    ?: if (sourceId == RepositorySources.source(CI_REPO).id) {
                        preferences.getString(KEY_CATALOG, null)
                    } else null
                val editor = preferences.edit().remove(legacyScopedKey)
                if (sourceId == RepositorySources.source(CI_REPO).id) editor.remove(KEY_CATALOG)
                if (source.catalogBranch == "main" && legacyRaw != null) {
                    editor.putString(cacheKey, legacyRaw)
                    raw = legacyRaw
                }
                editor.commit()
            }
            val cachedRaw = raw ?: return@synchronized null
            Triple(source, generation, cachedRaw)
        } ?: return null
        val catalog = runCatching { readCachedCatalog(JSONObject(snapshot.third), sourceId) }.getOrNull() ?: return null
        return runCatching {
            RepositorySources.whileCurrent(
                context, SOURCE_SCOPE, sourceBuiltIns(), snapshot.first, snapshot.second,
            ) { catalog }
        }.getOrNull()
    }

    @JvmOverloads fun refresh(context: Context, sourceId: String = follow(context, null).sourceId): Catalog {
        val (source, generation) = synchronized(RepositorySources.lock) {
            val selected = RepositorySources.visible(context, SOURCE_SCOPE, sourceBuiltIns())
                .singleOrNull { it.id == sourceId }
                ?: throw IOException("The selected update source is hidden or unavailable")
            selected to RepositorySources.generation(context, SOURCE_SCOPE, sourceBuiltIns())
        }
        val checkedAt = System.currentTimeMillis()
        val published = try {
            JSONObject(GitHubArtifacts.readRepositoryFile(
                context, SOURCE_SCOPE, sourceBuiltIns(), source, "catalog.json", source.catalogBranch,
                1024 * 1024, generation,
            ).toString(Charsets.UTF_8))
        } catch (error: GitHubArtifacts.HttpException) {
            if (error.status == 404) throw IOException(context.getString(R.string.sources_update_catalog_missing), error)
            throw error
        }
        val catalog = try {
            readPublishedCatalog(published, context.packageName, checkedAt, expectedSourceRepo = null,
                expectedCiRepo = source.repository, sourceId = source.id)
        } catch (e: IOException) {
            throw IOException(context.getString(R.string.appupd_catalog_rejected, e.message), e)
        }
        cacheCatalog(context, source, generation, catalog)
        return catalog
    }

    internal fun cacheCatalog(
        context: Context, source: RepositorySources.Source, generation: Long, catalog: Catalog,
    ) {
        val cached = writeCatalog(catalog).toString()
        RepositorySources.whileCurrent(context, SOURCE_SCOPE, sourceBuiltIns(), source, generation) {
            val preferences = prefs(context)
            val editor = preferences.edit().putString(catalogCacheKey(source), cached)
                .remove("$KEY_CATALOG:${source.id}")
            if (source.id == RepositorySources.source(CI_REPO).id) editor.remove(KEY_CATALOG)
            if (!editor.commit()) throw IOException("Could not cache the update catalog")
        }
    }

    internal fun catalogCacheKey(source: RepositorySources.Source): String {
        val branch = RepositorySources.normalizeCatalogBranch(source.catalogBranch)
        require(branch == source.catalogBranch) { "Repository source branch is not normalized" }
        val selector = branch?.let {
            "ref:${RepositorySources.sha256(it.toByteArray(Charsets.UTF_8))}"
        } ?: "default"
        return "$KEY_CATALOG:$CATALOG_CACHE_VERSION:${source.id}:$selector"
    }

    internal fun readPublishedCatalog(
        root: JSONObject,
        packageName: String,
        checkedAt: Long,
        expectedSourceRepo: String? = REPO,
        expectedCiRepo: String = CI_REPO,
        sourceId: String = "",
    ): Catalog {
        val schema = root.optInt("schema")
        val sourceRepo = root.optString("sourceRepo")
        val ciRepo = root.optString("ciRepo")
        val canonicalSource = runCatching { RepositorySources.canonicalRepository(sourceRepo) }.getOrNull()
        val canonicalCi = runCatching { RepositorySources.canonicalRepository(ciRepo) }.getOrNull()
        val expectedSource = expectedSourceRepo?.let { RepositorySources.canonicalRepository(it) }
        val expectedCi = RepositorySources.canonicalRepository(expectedCiRepo)
        if (schema != CATALOG_SCHEMA || canonicalSource == null || canonicalCi == null ||
            (expectedSource != null && canonicalSource != expectedSource) || canonicalCi != expectedCi) {
            throw IOException(
                "The update catalog has an unsupported format (schema $schema, source $sourceRepo, CI $ciRepo)",
            )
        }
        val tests = root.optJSONArray("tests") ?: JSONArray()
        val recentPreviews = root.optJSONArray("recentPreviews") ?: JSONArray()
        return Catalog(
            root.optJSONObject("stable")?.let { readPublishedRelease(it, packageName, sourceRepo, ciRepo) },
            root.optJSONObject("preview")?.let { readPublishedRelease(it, packageName, sourceRepo, ciRepo) },
            (0 until tests.length())
                .map { readPublishedRelease(tests.getJSONObject(it), packageName, sourceRepo, ciRepo) }
                .filter { it.pr > 0 }
                .sortedByDescending { it.publishedAt },
            checkedAt,
            (0 until recentPreviews.length())
                .mapNotNull { recentPreviews.optJSONObject(it)?.let(::readPublishedPreviewChange) }
                .sortedByDescending { it.publishedAt }
                .distinctBy { it.commit.lowercase() }
                .take(10),
            sourceId,
        )
    }

    private fun readPublishedPreviewChange(o: JSONObject): PreviewChange? {
        val commit = o.optString("commit")
        if (!Regex("^[0-9a-fA-F]{40}$").matches(commit)) return null
        return PreviewChange(commit.lowercase(), o.optString("title"), o.optString("summary"), o.optLong("publishedAt"))
    }

    private fun readPublishedRelease(o: JSONObject, packageName: String, sourceRepo: String, ciRepo: String): Release {
        val commit = o.getString("commit")
        if (!Regex("^[0-9a-fA-F]{7,40}$").matches(commit)) {
            throw IOException("The update catalog contains an invalid commit")
        }
        val versionCode = o.getInt("versionCode")
        if (versionCode <= 0) throw IOException("The update catalog contains an invalid versionCode")
        val apk = o.optJSONObject("apks")?.optJSONObject(packageName)?.let {
            readPublishedApk(it, packageName, sourceRepo, ciRepo)
        }
        if (apk != null && apk.versionCode != versionCode) {
            throw IOException("The update catalog disagrees about the APK versionCode")
        }
        return Release(
            o.getString("tag"),
            o.optString("title"),
            o.optString("summary"),
            commit,
            o.optInt("pr"),
            if (o.isNull("version")) null else o.optString("version").ifEmpty { null },
            versionCode,
            o.optLong("publishedAt"),
            o.optString("url"),
            apk,
        )
    }

    private fun readPublishedApk(o: JSONObject, packageName: String, sourceRepo: String, ciRepo: String): Apk {
        val actualPackage = o.getString("packageName")
        val versionCode = o.getInt("versionCode")
        val signer = o.getString("signerSha256").lowercase()
        val sha = o.getString("sha256").lowercase()
        val url = o.getString("url")
        val name = o.getString("name")
        val size = o.getLong("size")
        if (actualPackage != packageName) throw IOException("The update catalog names the wrong Android package")
        if (versionCode <= 0) throw IOException("The update catalog has an invalid APK versionCode")
        if (size !in 1..(2L * 1024 * 1024 * 1024) || name.isEmpty() || name.length > 240 ||
            name.any { it == '/' || it == '\\' || it.isISOControl() }) {
            throw IOException("The update catalog has invalid APK file metadata")
        }
        if (!Regex("^[0-9a-f]{64}$").matches(sha)) {
            throw IOException("The update catalog has an invalid checksum")
        }
        if (!Regex("^[0-9a-f]{64}$").matches(signer)) {
            throw IOException("The update catalog has an invalid signer identity")
        }
        val allowed = listOf(
            "https://github.com/$sourceRepo/releases/download/",
            "https://github.com/$ciRepo/releases/download/",
        )
        if (allowed.none { url.startsWith(it) }) {
            throw IOException("The update catalog has an unexpected download URL")
        }
        return Apk(
            name,
            url,
            size,
            sha,
            actualPackage,
            versionCode,
            signer,
        )
    }

    fun compareVersions(a: String, b: String): Int {
        val x = a.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private fun writeApk(a: Apk): JSONObject = JSONObject()
        .put("name", a.name)
        .put("url", a.url)
        .put("size", a.size)
        .put("sha256", a.sha256)
        .put("packageName", a.packageName)
        .put("versionCode", a.versionCode)
        .put("signerSha256", a.signerSha256)

    private fun writeRelease(r: Release): JSONObject = JSONObject()
        .put("tag", r.tag)
        .put("title", r.title)
        .put("summary", r.summary)
        .put("commit", r.commit)
        .put("pr", r.pr)
        .put("version", r.version)
        .put("versionCode", r.versionCode)
        .put("publishedAt", r.publishedAt)
        .put("url", r.url)
        .put("apk", r.apk?.let(::writeApk))

    private fun readCachedRelease(o: JSONObject): Release = Release(
        o.getString("tag"),
        o.optString("title"),
        o.optString("summary"),
        o.getString("commit"),
        o.optInt("pr"),
        if (o.isNull("version")) null else o.optString("version").ifEmpty { null },
        o.getInt("versionCode"),
        o.optLong("publishedAt"),
        o.optString("url"),
        o.optJSONObject("apk")?.let {
            Apk(
                it.getString("name"),
                it.getString("url"),
                it.optLong("size"),
                it.getString("sha256"),
                it.getString("packageName"),
                it.getInt("versionCode"),
                it.getString("signerSha256"),
            )
        },
    )

    internal fun writeCatalog(c: Catalog): JSONObject = JSONObject()
        .put("stable", c.stable?.let(::writeRelease))
        .put("preview", c.preview?.let(::writeRelease))
        .put("tests", JSONArray().apply { c.tests.forEach { put(writeRelease(it)) } })
        .put("recentPreviews", JSONArray().apply { c.recentPreviews.forEach { put(writePreviewChange(it)) } })
        .put("checkedAt", c.checkedAt)

    private fun writePreviewChange(c: PreviewChange): JSONObject = JSONObject()
        .put("commit", c.commit)
        .put("title", c.title)
        .put("summary", c.summary)
        .put("publishedAt", c.publishedAt)

    internal fun readCachedCatalog(o: JSONObject, sourceId: String = ""): Catalog {
        val tests = o.optJSONArray("tests") ?: JSONArray()
        val preview = o.optJSONObject("preview") ?: o.optJSONObject("nightly")
        val recentPreviews = o.optJSONArray("recentPreviews") ?: JSONArray()
        return Catalog(
            o.optJSONObject("stable")?.let(::readCachedRelease),
            preview?.let(::readCachedRelease),
            (0 until tests.length()).map { readCachedRelease(tests.getJSONObject(it)) },
            o.optLong("checkedAt"),
            (0 until recentPreviews.length())
                .mapNotNull { recentPreviews.optJSONObject(it)?.let(::readCachedPreviewChange) }
                .sortedByDescending { it.publishedAt }
                .distinctBy { it.commit.lowercase() }
                .take(10),
            sourceId,
        )
    }

    private fun readCachedPreviewChange(o: JSONObject): PreviewChange? {
        val commit = o.optString("commit")
        if (!Regex("^[0-9a-fA-F]{40}$").matches(commit)) return null
        return PreviewChange(commit.lowercase(), o.optString("title"), o.optString("summary"), o.optLong("publishedAt"))
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
