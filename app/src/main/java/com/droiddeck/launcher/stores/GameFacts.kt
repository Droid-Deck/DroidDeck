package com.droiddeck.launcher.stores

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.ConcurrentHashMap

/**
 * What other sites know of a store game, looked up by its title: the Metacritic score (as Steam's
 * store search carries it) and HowLongToBeat's times. Neither store publishes these, so every
 * lookup is a name match against someone else's catalog, kept on disk a fortnight (a miss for two
 * days) and in memory for the process. Blocking: call off the main thread.
 */
object GameFacts {
    private const val TAG = "GameFacts"
    private const val HIT_TTL_MS = 14L * 24 * 60 * 60 * 1000
    private const val MISS_TTL_MS = 2L * 24 * 60 * 60 * 1000
    private const val TIMEOUT_MS = 10_000

    /** Metacritic's critic score (0-100) and the game's page there. */
    data class Metacritic(val score: Int, val url: String)

    /** HowLongToBeat's community times, in seconds (0 when nobody has logged that kind of run), and the game's page there. */
    data class Hltb(val main: Int, val extras: Int, val completionist: Int, val url: String) {
        val isEmpty: Boolean get() = main <= 0 && extras <= 0 && completionist <= 0
    }

    /** A remembered answer: [value] null is a remembered miss. */
    private class Entry<T>(val value: T?, val at: Long) {
        fun fresh(now: Long) = now - at < if (value != null) HIT_TTL_MS else MISS_TTL_MS
    }

    private val memory = ConcurrentHashMap<String, Entry<*>>()

    /** The score or the times already in memory, without touching disk or network; null when not yet known. */
    fun peekMetacritic(title: String): Lookup<Metacritic>? = peek("metacritic", title)
    fun peekHltb(title: String): Lookup<Hltb>? = peek("hltb", title)

    /** The score, or null when Steam does not list the game or has no score for it. */
    fun metacritic(context: Context, title: String): Metacritic? =
        remembered(context, "metacritic", title,
            read = { o -> o.optInt("score").takeIf { it > 0 }?.let { Metacritic(it, o.optString("url").ifEmpty { metacriticSearch(title) }) } },
            write = { o, v -> o.put("score", v.score).put("url", v.url) },
        ) { searchSteamScore(title) }

    fun hltb(context: Context, title: String): Hltb? =
        remembered(context, "hltb", title,
            read = { o -> Hltb(o.optInt("main"), o.optInt("extras"), o.optInt("completionist"), o.optString("url").ifEmpty { hltbSearch(title) }).takeUnless { it.isEmpty } },
            write = { o, v -> o.put("main", v.main).put("extras", v.extras).put("completionist", v.completionist).put("url", v.url) },
        ) { searchHltb(title) }

    // Where a game goes when its own page is not known: the site's search for its name.
    private fun metacriticSearch(title: String) = "https://www.metacritic.com/search/${encodePath(TitleMatch.searchTerm(title))}/?category=13"
    private fun hltbSearch(title: String) = "$HLTB/?q=${URLEncoder.encode(TitleMatch.searchTerm(title), "UTF-8")}"
    private fun encodePath(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    @Suppress("UNCHECKED_CAST")
    private fun <T> peek(kind: String, title: String): Lookup<T>? {
        val e = memory[key(kind, title)] as Entry<T>? ?: return null
        return e.value?.let { Lookup.Found(it) } ?: Lookup.None
    }

    /**
     * The memory copy, else the disk copy while fresh, else [fetch]. [fetch] answers a [Fetched]:
     * a failed request is not a miss, so it leaves the stale copy (if any) standing.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> remembered(
        context: Context, kind: String, title: String,
        read: (JSONObject) -> T?, write: (JSONObject, T) -> Unit, fetch: () -> Fetched<T>,
    ): T? {
        val now = System.currentTimeMillis()
        val k = key(kind, title)
        (memory[k] as Entry<T>?)?.takeIf { it.fresh(now) }?.let { return it.value }
        val file = File(context.filesDir, "game-facts/$kind/${fileName(title)}.json")
        val disk = runCatching { JSONObject(file.readText()) }.getOrNull()
            ?.let { o -> Entry(if (o.optBoolean("none")) null else read(o), o.optLong("at")) }
        if (disk != null && disk.fresh(now)) { memory[k] = disk; return disk.value }
        return when (val got = fetch()) {
            is Fetched.Failed -> disk?.value
            is Fetched.Answer -> {
                val entry = Entry(got.value, now)
                memory[k] = entry
                runCatching {
                    file.parentFile?.mkdirs()
                    val o = JSONObject().put("at", now)
                    if (got.value != null) write(o, got.value) else o.put("none", true)
                    file.writeText(o.toString())
                }
                got.value
            }
        }
    }

    private sealed interface Fetched<out T> {
        object Failed : Fetched<Nothing>
        class Answer<T>(val value: T?) : Fetched<T>
    }

    private fun key(kind: String, title: String) = "$kind:${TitleMatch.normalize(title)}"

    private fun fileName(title: String) = TitleMatch.normalize(title).take(80).ifEmpty { "_" + title.hashCode().toUInt() }

    // Steam's store search lists a game's Metacritic score beside its name: one request, no key.
    // The game's own Metacritic page is in its app details, asked for only once there is a score.
    private fun searchSteamScore(title: String): Fetched<Metacritic> {
        val term = URLEncoder.encode(TitleMatch.searchTerm(title), "UTF-8")
        val body = StoreNet.get("https://store.steampowered.com/api/storesearch/?l=english&cc=US&term=$term", timeoutMs = TIMEOUT_MS)
            ?: return Fetched.Failed
        return runCatching {
            val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
            val scored = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                .filter { it.optString("type") == "app" && (it.optString("metascore").toIntOrNull() ?: 0) > 0 }
            val best = TitleMatch.pick(title, scored) { listOf(it.optString("name")) }
            val score = best?.optString("metascore")?.toIntOrNull() ?: return Fetched.Answer(null)
            Fetched.Answer(Metacritic(score, metacriticPage(best.optLong("id")) ?: metacriticSearch(title)))
        }.getOrElse { Fetched.Failed }
    }

    /** Steam's link to the game on Metacritic, without its tracking tag; null when Steam gives none. */
    private fun metacriticPage(appId: Long): String? {
        if (appId <= 0) return null
        val body = StoreNet.get("https://store.steampowered.com/api/appdetails?appids=$appId&filters=metacritic", timeoutMs = TIMEOUT_MS) ?: return null
        return runCatching {
            JSONObject(body).optJSONObject("$appId")?.optJSONObject("data")?.optJSONObject("metacritic")?.optString("url")
                ?.substringBefore('?')?.takeIf { it.startsWith("https://") }
        }.getOrNull()
    }

    // HowLongToBeat's own site search: a short-lived token from init, sent back as x-auth-token.
    // The token is bound to the User-Agent that asked for it, so both calls send the same one.
    private const val HLTB = "https://howlongtobeat.com"
    private val hltbHeaders = mapOf("Referer" to "$HLTB/", "Origin" to HLTB)
    @Volatile private var hltbToken: Pair<String, Long>? = null

    private fun hltbToken(renew: Boolean): String? {
        val now = System.currentTimeMillis()
        hltbToken?.takeIf { !renew && now - it.second < 10 * 60 * 1000 }?.let { return it.first }
        val body = StoreNet.get("$HLTB/api/search/site/init?t=$now", timeoutMs = TIMEOUT_MS, headers = hltbHeaders) ?: return null
        val token = runCatching { JSONObject(body).optString("token") }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        hltbToken = token to now
        return token
    }

    private fun searchHltb(title: String): Fetched<Hltb> {
        val terms = JSONArray().apply { TitleMatch.searchTerm(title).split(' ').filter { it.isNotBlank() }.forEach { put(it) } }
        val request = JSONObject()
            .put("searchType", "games").put("searchTerms", terms).put("searchPage", 1).put("size", 20)
            .put("searchOptions", JSONObject()
                .put("games", JSONObject().put("userId", 0).put("platform", "").put("sortCategory", "popular").put("rangeCategory", "main")
                    .put("rangeTime", JSONObject().put("min", 0).put("max", 0))
                    .put("gameplay", JSONObject().put("perspective", "").put("flow", "").put("genre", ""))
                    .put("year", "").put("modifier", ""))
                .put("users", JSONObject().put("sortCategory", "postcount"))
                .put("lists", JSONObject().put("sortCategory", "follows"))
                .put("filter", "").put("sort", 0).put("randomizer", 0))
            .put("useCache", true)
            .toString()
        // A token can lapse between init and search; one fresh one is worth a second try.
        var body: String? = null
        for (renew in listOf(false, true)) {
            val token = hltbToken(renew) ?: return Fetched.Failed
            body = StoreNet.postJson("$HLTB/api/search/site", request, timeoutMs = TIMEOUT_MS, headers = hltbHeaders + ("x-auth-token" to token))
            if (body != null) break
        }
        if (body == null) return Fetched.Failed
        return runCatching {
            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
            val games = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
                .sortedByDescending { it.optString("game_type") == "game" }
            val best = TitleMatch.pick(title, games) { g -> listOf(g.optString("game_name")) + g.optString("game_alias").split(',') }
            Fetched.Answer(best?.let {
                val page = it.optInt("game_id").takeIf { id -> id > 0 }?.let { id -> "$HLTB/game/$id" } ?: hltbSearch(title)
                Hltb(it.optInt("comp_main"), it.optInt("comp_plus"), it.optInt("comp_100"), page)
            }?.takeUnless { it.isEmpty })
        }.getOrElse {
            Log.w(TAG, "HowLongToBeat answer unreadable: ${it.javaClass.simpleName}")
            Fetched.Failed
        }
    }

    /** A store's release date ("2015.05.19", "2015-05-19T00:00:00+02:00", ...) in the reader's own words, or null. */
    fun releaseLabel(raw: String): String? {
        val m = Regex("""^(\d{4})[.\-/](\d{2})[.\-/](\d{2})""").find(raw.trim()) ?: return null
        val (y, mo, d) = m.destructured
        // Epic dates a title it has no date for 2099-01-01.
        val date = runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()?.takeIf { it.year in 1970..2098 } ?: return null
        return date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }

    /** Whole hours, with a half when the time is nearer one: "51½". Under an hour, null (the caller says minutes). */
    fun hours(seconds: Int): String? {
        if (seconds < 3600) return null
        val halves = Math.round(seconds / 1800.0).toInt()
        return "${halves / 2}" + if (halves % 2 == 1) "½" else ""
    }
}

/** A fact looked up off the page: still asking, found, or not to be had. */
sealed interface Lookup<out T> {
    object Pending : Lookup<Nothing>
    object None : Lookup<Nothing>
    data class Found<T>(val value: T) : Lookup<T>
}

/**
 * Whether two catalogs mean the same game. Store titles carry marks, editions and subtitles the
 * other site spells differently ("The Witcher 3: Wild Hunt" is Steam's "...Wild Hunt — Remastered");
 * a sequel is never the same game ("Hades" is not "Hades II").
 */
internal object TitleMatch {
    private val MARKS = Regex("[™®©]")
    private val EDITION = Regex(
        "(?i)[\\s:\\-–—]*\\b(?:(?:complete|definitive|legendary|deluxe|ultimate|enhanced|gold|premium|standard|special|anniversary|collector'?s|digital deluxe|game of the year|goty)\\s+edition|goty|director'?s cut)\\b",
    )
    // Where a subtitle starts: a colon or dash with space around it, or an opening bracket.
    private val SUBTITLE = Regex("\\s*[:–—]\\s*|\\s+-\\s+|\\s*\\(")

    fun normalize(s: String): String = s.lowercase().replace(MARKS, "").replace("&", "and").replace(Regex("[^\\p{L}\\p{N}]"), "")

    fun stripEdition(s: String): String = s.replace(EDITION, " ").trim().ifEmpty { s }

    /**
     * What to type into another site's search: the title without its marks, edition or subtitle
     * punctuation (Steam finds nothing for "Disco Elysium - The Final Cut", all of it for the words).
     */
    fun searchTerm(title: String): String =
        stripEdition(title.replace(MARKS, "")).replace(Regex("\\s+[-–—]\\s+|[:–—()\\[\\]]"), " ").replace(Regex("\\s+"), " ").trim()

    /** 3 the same name, 2 the same but for the edition, 1 one is the other with a subtitle, 0 not the same game. */
    fun score(want: String, got: String): Int {
        val w = normalize(want)
        val g = normalize(got)
        if (w.isEmpty() || g.isEmpty()) return 0
        if (w == g) return 3
        val ws = normalize(stripEdition(want))
        val gs = normalize(stripEdition(got))
        if (ws == gs || ws == g || w == gs) return 2
        if (ws in heads(got) || gs in heads(want)) return 1
        return 0
    }

    /** The title up to each place a subtitle could start, normalized. */
    private fun heads(title: String): Set<String> =
        SUBTITLE.findAll(title).map { normalize(title.substring(0, it.range.first)) }.filter { it.isNotEmpty() }.toSet()

    /** The best-named of [items] in their order (the site's own ranking breaks a tie), or null when none is the game. */
    fun <T> pick(want: String, items: List<T>, names: (T) -> List<String>): T? {
        var best: T? = null
        var bestScore = 0
        for (item in items) {
            val s = names(item).filter { it.isNotBlank() }.maxOfOrNull { score(want, it.trim()) } ?: 0
            if (s > bestScore) { best = item; bestScore = s }
        }
        return best
    }
}
