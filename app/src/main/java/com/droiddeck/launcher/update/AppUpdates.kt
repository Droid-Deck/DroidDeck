package com.droiddeck.launcher.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * DroidDeck's published builds. CI turns GitHub Releases into one static catalog in DroidDeck-CI;
 * the app consumes that contract instead of inferring channels from tags and release-note prose.
 *
 * [Channel.NIGHTLY] is intentionally kept as the internal enum/pref value for upgrades from older
 * DroidDeck versions. The channel is presented to users as Preview.
 */
object AppUpdates {
    private const val REPO = "Droid-Deck/DroidDeck"
    private const val CI_REPO = "Droid-Deck/DroidDeck-CI"
    private const val CATALOG_URL = "https://raw.githubusercontent.com/$CI_REPO/main/catalog.json"
    private const val PREFS = "app_updates"
    private const val KEY_CATALOG = "catalog"
    private const val KEY_CHANNEL = "channel"
    private const val CATALOG_SCHEMA = 1

    enum class Channel { STABLE, NIGHTLY, TEST }

    data class Follow(val channel: Channel, val pr: Int = 0)

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
    )

    class Installed(
        val commit: String,
        val pr: Int,
        val version: String,
        val versionCode: Int,
        val updatable: Boolean,
        val committedAt: Long = 0L,
        val ciBuild: Boolean = BuildConfig.CI_BUILD,
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

    @Volatile private var releaseSigned = BuildConfig.CI_BUILD

    fun init(context: Context) {
        releaseSigned = runCatching {
            signers(context).any { it.equals(BuildConfig.RELEASE_SIGNER, ignoreCase = true) }
        }.getOrDefault(BuildConfig.CI_BUILD)
    }

    private fun signers(context: Context): List<String> {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            ).signingInfo ?: return emptyList()
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        } ?: return emptyList()
        return certs.map { cert ->
            MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    fun installed(): Installed = Installed(
        BuildConfig.SOURCE_COMMIT,
        BuildConfig.PR_NUMBER,
        BuildConfig.VERSION_NAME,
        BuildConfig.VERSION_CODE,
        releaseSigned,
        BuildConfig.COMMIT_TIME,
    )

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

    /** Recent Preview changes relative to a CI-installed commit, when its position is known. */
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

    /** Why Android cannot install this published build over [me], before downloading anything. */
    fun installBlock(r: Release, me: Installed = installed()): InstallBlock? = when {
        r.apk == null -> InstallBlock(R.string.upd_block_no_apk)
        r.apk.versionCode < me.versionCode -> InstallBlock(R.string.upd_block_version_code, r.apk.versionCode, me.versionCode)
        else -> null
    }

    /** Why Android would refuse a build, as a line for the user. */
    class InstallBlock(@androidx.annotation.StringRes val text: Int, private vararg val args: Any) {
        fun message(context: Context): String = context.getString(text, *args)
    }

    fun follow(context: Context, catalog: Catalog?): Follow {
        stored(context)?.let { return it }
        val me = installed()
        val inferred = when {
            me.pr != 0 -> Follow(Channel.TEST, me.pr)
            catalog == null -> return Follow(Channel.STABLE)
            catalog.stable != null && isRunning(catalog.stable, me) -> Follow(Channel.STABLE)
            me.updatable -> Follow(Channel.NIGHTLY)
            else -> Follow(Channel.STABLE)
        }
        setFollow(context, inferred)
        return inferred
    }

    fun setFollow(context: Context, follow: Follow) {
        // Keep "nightly" on disk so an older DroidDeck still understands the preference after rollback.
        val value = if (follow.channel == Channel.TEST) "test:${follow.pr}"
        else follow.channel.name.lowercase()
        prefs(context).edit().putString(KEY_CHANNEL, value).apply()
    }

    private fun stored(context: Context): Follow? {
        val v = prefs(context).getString(KEY_CHANNEL, null) ?: return null
        return when {
            v == "stable" -> Follow(Channel.STABLE)
            v == "nightly" || v == "preview" -> Follow(Channel.NIGHTLY)
            v.startsWith("test:") -> v.removePrefix("test:").toIntOrNull()?.let {
                Follow(Channel.TEST, it)
            }
            else -> null
        }
    }

    fun cached(context: Context): Catalog? {
        val raw = prefs(context).getString(KEY_CATALOG, null) ?: return null
        return runCatching { readCachedCatalog(JSONObject(raw)) }.getOrNull()
    }

    /** One static request, rather than several unauthenticated GitHub API requests. */
    fun refresh(context: Context): Catalog {
        val checkedAt = System.currentTimeMillis()
        val published = JSONObject(get(context, "$CATALOG_URL?checked=$checkedAt"))
        // Its checks say why in English; the user gets that inside a sentence in the app's language.
        val catalog = try {
            readPublishedCatalog(published, context.packageName, checkedAt)
        } catch (e: IOException) {
            throw IOException(context.getString(R.string.appupd_catalog_rejected, e.message), e)
        }
        prefs(context).edit().putString(KEY_CATALOG, writeCatalog(catalog).toString()).apply()
        return catalog
    }

    internal fun readPublishedCatalog(
        root: JSONObject,
        packageName: String,
        checkedAt: Long,
    ): Catalog {
        val schema = root.optInt("schema")
        val sourceRepo = root.optString("sourceRepo")
        val ciRepo = root.optString("ciRepo")
        if (schema != CATALOG_SCHEMA || sourceRepo != REPO || ciRepo != CI_REPO) {
            throw IOException(
                "The update catalog has an unsupported format (schema $schema, source $sourceRepo, CI $ciRepo)",
            )
        }
        val tests = root.optJSONArray("tests") ?: JSONArray()
        val recentPreviews = root.optJSONArray("recentPreviews") ?: JSONArray()
        return Catalog(
            root.optJSONObject("stable")?.let { readPublishedRelease(it, packageName) },
            root.optJSONObject("preview")?.let { readPublishedRelease(it, packageName) },
            (0 until tests.length())
                .map { readPublishedRelease(tests.getJSONObject(it), packageName) }
                .filter { it.pr > 0 }
                .sortedByDescending { it.publishedAt },
            checkedAt,
            (0 until recentPreviews.length())
                .mapNotNull { recentPreviews.optJSONObject(it)?.let(::readPublishedPreviewChange) }
                .sortedByDescending { it.publishedAt }
                .distinctBy { it.commit.lowercase() }
                .take(10),
        )
    }

    private fun readPublishedPreviewChange(o: JSONObject): PreviewChange? {
        val commit = o.optString("commit")
        if (!Regex("^[0-9a-fA-F]{40}$").matches(commit)) return null
        return PreviewChange(commit.lowercase(), o.optString("title"), o.optString("summary"), o.optLong("publishedAt"))
    }

    private fun readPublishedRelease(o: JSONObject, packageName: String): Release {
        val commit = o.getString("commit")
        if (!Regex("^[0-9a-fA-F]{7,40}$").matches(commit)) {
            throw IOException("The update catalog contains an invalid commit")
        }
        val versionCode = o.getInt("versionCode")
        if (versionCode <= 0) throw IOException("The update catalog contains an invalid versionCode")
        val apk = o.optJSONObject("apks")?.optJSONObject(packageName)?.let {
            readPublishedApk(it, packageName)
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

    private fun readPublishedApk(o: JSONObject, packageName: String): Apk {
        val actualPackage = o.getString("packageName")
        val versionCode = o.getInt("versionCode")
        val signer = o.getString("signerSha256").lowercase()
        val sha = o.getString("sha256").lowercase()
        val url = o.getString("url")
        if (actualPackage != packageName) throw IOException("The update catalog names the wrong Android package")
        if (versionCode <= 0) throw IOException("The update catalog has an invalid APK versionCode")
        if (!Regex("^[0-9a-f]{64}$").matches(sha)) {
            throw IOException("The update catalog has an invalid checksum")
        }
        if (!signer.equals(BuildConfig.RELEASE_SIGNER, ignoreCase = true)) {
            throw IOException("The update catalog is not signed for DroidDeck's release key")
        }
        val allowed = listOf(
            "https://github.com/$REPO/releases/download/",
            "https://github.com/$CI_REPO/releases/download/",
        )
        if (allowed.none { url.startsWith(it) }) {
            throw IOException("The update catalog has an unexpected download URL")
        }
        return Apk(
            o.getString("name"),
            url,
            o.optLong("size"),
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

    private fun get(context: Context, url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "DroidDeck-app")
        c.setRequestProperty("Cache-Control", "no-cache")
        try {
            val code = c.responseCode
            if (code != 200) throw IOException(context.getString(R.string.appupd_http, code))
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
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

    internal fun readCachedCatalog(o: JSONObject): Catalog {
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
