package com.droiddeck.launcher.update

import android.content.Context
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.core.Hashes
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * DroidDeck's own builds, on three channels: Stable (the org repo's latest release), Nightly (the
 * newest `main-<sha>` release on DroidDeck-CI, one per merge to main) and Test (a `pr-<n>` release
 * on DroidDeck-CI, a signed build of an open PR). All three are signed with the release key, so any
 * of them installs over any other and keeps the app's data.
 *
 * Which build is newer is decided without a diff of the repo (GitHub's compare answers in
 * megabytes): main is linear, so the newest main build is the newest; Stable is ordered by its
 * version; a test build is newer when its PR's head moved. The last check is remembered, so the
 * page shows it without going online.
 */
object AppUpdates {
    private const val REPO = "Droid-Deck/DroidDeck"
    private const val CI_REPO = "Droid-Deck/DroidDeck-CI"
    private const val PREFS = "app_updates"
    private const val KEY_CATALOG = "catalog"
    private const val KEY_CHANNEL = "channel"

    enum class Channel { STABLE, NIGHTLY, TEST }

    /** The channel the user follows; [pr] is the followed test build's PR, for [Channel.TEST]. */
    data class Follow(val channel: Channel, val pr: Int = 0)

    class Apk(val name: String, val url: String, val size: Long, val sha256: String)

    /**
     * One published build. [commit] is a full or short SHA of the source it was built from; [pr] is
     * 0 for a main or stable build. [apk] is the file for the package this app runs as, or null when
     * the build has none (a variant a release predates).
     */
    class Release(
        val tag: String, val title: String, val summary: String, val commit: String, val pr: Int,
        val version: String?, val publishedAt: Long, val url: String, val apk: Apk?,
    )

    class Catalog(val stable: Release?, val nightly: Release?, val tests: List<Release>, val checkedAt: Long)

    /** What is running: the commit it was built from, its PR (0 for none), whether CI made it, and when it was committed. */
    class Installed(val commit: String, val pr: Int, val version: String, val ci: Boolean, val committedAt: Long = 0L)

    enum class Offer {
        /** This is the build the channel has. */
        CURRENT,
        /** The channel has a newer build of what is running. */
        UPDATE,
        /** The channel's build is a different line (a test build from Nightly, or back); installing moves over. */
        SWITCH,
        /** What is running is newer than the channel's build (a Nightly past the last Stable). */
        AHEAD,
        /** The followed test build is gone: its PR was merged or closed. */
        GONE,
    }

    fun installed(): Installed = Installed(BuildConfig.SOURCE_COMMIT, BuildConfig.PR_NUMBER, BuildConfig.VERSION_NAME, BuildConfig.CI_BUILD, BuildConfig.COMMIT_TIME)

    fun release(catalog: Catalog, follow: Follow): Release? = when (follow.channel) {
        Channel.STABLE -> catalog.stable
        Channel.NIGHTLY -> catalog.nightly
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

    /** The dot on the rail: the followed channel has a newer build of what is running. */
    fun hasUpdate(catalog: Catalog?, follow: Follow): Boolean =
        catalog != null && installed().ci && offer(catalog, follow) == Offer.UPDATE

    fun isRunning(r: Release, me: Installed = installed()): Boolean {
        if (me.commit.length < 7 || r.commit.length < 7 || r.pr != me.pr) return false
        return me.commit.startsWith(r.commit, ignoreCase = true) || r.commit.startsWith(me.commit, ignoreCase = true)
    }

    /**
     * The channel followed. Until the user picks one it is worked out once from what is running and
     * kept: a test build follows its PR, the Stable release follows Stable, and a main build Nightly.
     */
    fun follow(context: Context, catalog: Catalog?): Follow {
        stored(context)?.let { return it }
        val me = installed()
        val inferred = when {
            me.pr != 0 -> Follow(Channel.TEST, me.pr)
            catalog == null -> return Follow(Channel.STABLE)
            catalog.stable != null && isRunning(catalog.stable, me) -> Follow(Channel.STABLE)
            me.ci -> Follow(Channel.NIGHTLY)
            else -> Follow(Channel.STABLE)
        }
        setFollow(context, inferred)
        return inferred
    }

    fun setFollow(context: Context, follow: Follow) {
        val value = if (follow.channel == Channel.TEST) "test:${follow.pr}" else follow.channel.name.lowercase()
        prefs(context).edit().putString(KEY_CHANNEL, value).apply()
    }

    private fun stored(context: Context): Follow? {
        val v = prefs(context).getString(KEY_CHANNEL, null) ?: return null
        return when {
            v == "stable" -> Follow(Channel.STABLE)
            v == "nightly" -> Follow(Channel.NIGHTLY)
            v.startsWith("test:") -> v.removePrefix("test:").toIntOrNull()?.let { Follow(Channel.TEST, it) }
            else -> null
        }
    }

    /** The last check, or null before the first. */
    fun cached(context: Context): Catalog? {
        val raw = prefs(context).getString(KEY_CATALOG, null) ?: return null
        return runCatching { readCatalog(JSONObject(raw)) }.getOrNull()
    }

    /** Read every channel now and remember the result: three small requests, four for an annotated tag. */
    fun refresh(context: Context): Catalog {
        val suffix = variantSuffix(context.packageName)
        val stable = runCatching { stable(suffix) }.getOrElse { if (it is NoRelease) null else throw it }
        val ci = JSONArray(get("https://api.github.com/repos/$CI_REPO/releases?per_page=50"))
        var nightly: Release? = null
        val tests = ArrayList<Release>()
        for (i in 0 until ci.length()) {
            val r = ci.getJSONObject(i)
            if (r.optBoolean("draft")) continue
            val tag = r.getString("tag_name")
            when {
                tag.startsWith("main-") -> {
                    val candidate = ciRelease(r, suffix, tag.removePrefix("main-"), 0)
                    if (nightly == null || candidate.publishedAt > nightly.publishedAt) nightly = candidate
                }
                tag.startsWith("pr-") -> {
                    val pr = tag.removePrefix("pr-").toIntOrNull() ?: continue
                    val body = r.optString("body")
                    // Only builds of this repo's PRs: a test build from before the org move names another.
                    if (!body.contains("github.com/$REPO/pull/$pr")) continue
                    val commit = Regex("""Commit: `?([0-9a-f]{7,40})""").find(body)?.groupValues?.get(1) ?: continue
                    tests.add(ciRelease(r, suffix, commit, pr))
                }
            }
        }
        tests.sortByDescending { it.publishedAt }
        val catalog = Catalog(stable, nightly, tests, System.currentTimeMillis())
        prefs(context).edit().putString(KEY_CATALOG, writeCatalog(catalog).toString()).apply()
        return catalog
    }

    private class NoRelease : IOException("no stable release")

    private fun stable(suffix: String): Release {
        val r = try {
            JSONObject(get("https://api.github.com/repos/$REPO/releases/latest"))
        } catch (e: IOException) {
            if (e.message?.contains("HTTP 404") == true) throw NoRelease() else throw e
        }
        val tag = r.getString("tag_name")
        // The tag's commit; an annotated tag points at a tag object first.
        var obj = JSONObject(get("https://api.github.com/repos/$REPO/git/ref/tags/$tag")).getJSONObject("object")
        if (obj.getString("type") == "tag") obj = JSONObject(get(obj.getString("url"))).getJSONObject("object")
        val (title, summary) = describeStable(r.optString("name").ifBlank { tag }, r.optString("body"))
        return Release(tag, title, summary, obj.getString("sha"), 0, tag.removePrefix("v"), published(r),
            r.optString("html_url"), apk(r, suffix))
    }

    private fun ciRelease(r: JSONObject, suffix: String, commit: String, pr: Int): Release {
        val (title, summary) = describeCi(r.optString("name"), r.optString("body"))
        return Release(r.getString("tag_name"), title, summary, commit, pr, null, published(r), r.optString("html_url"), apk(r, suffix))
    }

    private fun published(r: JSONObject): Long =
        runCatching { Instant.parse(r.optString("published_at")).toEpochMilli() }.getOrDefault(0L)

    /** The apk for this package: `<name><suffix>.apk`, and for the standard app the one with no other variant's suffix. */
    private fun apk(r: JSONObject, suffix: String): Apk? {
        val others = variantSuffixes().values.filter { it.isNotEmpty() && it != suffix }
        val assets = r.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.getString("name")
            if (!name.endsWith("$suffix.apk") || others.any { name.endsWith("$it.apk") }) continue
            // Only a file GitHub has a sha256 for: the download is checked against it.
            val sha = Hashes.githubSha256(a.optString("digest")) ?: continue
            return Apk(name, a.getString("browser_download_url"), a.optLong("size"), sha)
        }
        return null
    }

    private fun variantSuffixes(): Map<String, String> = BuildConfig.VARIANT_SUFFIXES.split(';')
        .mapNotNull { e -> e.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()

    private fun variantSuffix(packageName: String): String = variantSuffixes()[packageName] ?: ""

    /**
     * A CI release's notes open with what it is: "**Merged to main** - [PR #n](…): title (merged …)"
     * or "**Not merged** - a test build of open [PR #n](…): title", then a paragraph from the PR.
     */
    internal fun describeCi(name: String, body: String): Pair<String, String> {
        val paragraphs = paragraphs(body)
        val head = paragraphs.firstOrNull().orEmpty()
        val title = head.substringAfter("): ", "").substringBeforeLast(" (merged").ifBlank { plain(head) }.ifBlank { name }
        val summary = paragraphs.getOrNull(1)?.takeUnless { it.startsWith("Signed build") || it.startsWith("Source:") }.orEmpty()
        return plain(title) to lines(summary)
    }

    /** A stable release: its name, and the first paragraph of its notes that is prose rather than a heading or picture. */
    private fun describeStable(name: String, body: String): Pair<String, String> =
        name to lines(paragraphs(body).firstOrNull { !it.startsWith("#") && !it.startsWith("![") && !it.startsWith("<") }.orEmpty())

    private fun paragraphs(body: String): List<String> =
        body.replace("\r", "").split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }

    /** A paragraph's lines as text, a list item's marker as a bullet: the page shows them collapsed or in full. */
    private fun lines(md: String): String = md.lines().map { line ->
        val item = Regex("""^\s*[-*]\s+""").find(line)
        if (item != null) "• " + plain(line.substring(item.range.last + 1)) else plain(line)
    }.filter { it.isNotEmpty() }.joinToString("\n")

    /** Markdown down to text: links to their words, emphasis and code marks gone, on one line. */
    private fun plain(md: String): String = md
        .replace(Regex("""!\[[^]]*]\([^)]*\)"""), "")
        .replace(Regex("""\[([^]]*)]\([^)]*\)"""), "$1")
        .replace(Regex("""[*_`]{1,3}"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()

    /** Dotted versions, number by number: "0.10.0" is past "0.9.2"; a missing part counts as 0. */
    fun compareVersions(a: String, b: String): Int {
        val x = a.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "DroidDeck-app")
        try {
            val code = c.responseCode
            if (code == 403 || code == 429) throw IOException("GitHub's rate limit was hit - try again later")
            if (code != 200) throw IOException("GitHub answered HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun writeRelease(r: Release): JSONObject = JSONObject()
        .put("tag", r.tag).put("title", r.title).put("summary", r.summary).put("commit", r.commit).put("pr", r.pr)
        .put("version", r.version).put("publishedAt", r.publishedAt).put("url", r.url)
        .put("apk", r.apk?.let { JSONObject().put("name", it.name).put("url", it.url).put("size", it.size).put("sha256", it.sha256) })

    private fun readRelease(o: JSONObject): Release = Release(
        o.getString("tag"), o.optString("title"), o.optString("summary"), o.getString("commit"), o.optInt("pr"),
        o.optString("version").ifEmpty { null }, o.optLong("publishedAt"), o.optString("url"),
        o.optJSONObject("apk")?.let { Apk(it.getString("name"), it.getString("url"), it.optLong("size"), it.getString("sha256")) },
    )

    private fun writeCatalog(c: Catalog): JSONObject = JSONObject()
        .put("stable", c.stable?.let(::writeRelease)).put("nightly", c.nightly?.let(::writeRelease))
        .put("tests", JSONArray().apply { c.tests.forEach { put(writeRelease(it)) } }).put("checkedAt", c.checkedAt)

    private fun readCatalog(o: JSONObject): Catalog {
        val tests = o.optJSONArray("tests") ?: JSONArray()
        return Catalog(
            o.optJSONObject("stable")?.let(::readRelease), o.optJSONObject("nightly")?.let(::readRelease),
            (0 until tests.length()).map { readRelease(tests.getJSONObject(it)) }, o.optLong("checkedAt"),
        )
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
