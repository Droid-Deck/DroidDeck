package com.droiddeck.launcher.stores

import com.droiddeck.launcher.core.SecretScrub
import java.net.HttpURLConnection
import java.net.URL

internal object GameJoltProbe {
    private const val MAX_PAGE = 800_000
    private const val MAX_SCRIPT = 3_000_000
    private const val MAX_SCRIPTS = 8
    private const val MAX_FOUND = 80
    private const val MAX_REDIRECTS = 5
    private const val MIN_ROUTE_COUNT = 3

    private val endpoint = Regex("[\"'`](/?(?:site-api|web|x)/[^\"'`\\s]{3,140})[\"'`]")
    private val absolute = Regex("https?://[A-Za-z0-9.\\-]*gamejolt\\.(?:com|net)/[^\"'`\\s<>)]{0,140}")
    private val scriptSrc = Regex("<script[^>]+src=[\"']([^\"']+)[\"']")
    private val WORD = Regex("[A-Za-z][A-Za-z_\\-]{0,30}")
    private val TEMPLATE = Regex("[{$][A-Za-z0-9_.{}$]{0,38}")
    private val gameId = Regex("/(\\d+)/?(?:[?#].*)?$")

    private class Reply(val code: Int, val type: String, val finalUrl: String, val body: String)

    fun run(input: String) {
        Thread({
            runCatching { probe(input.trim()) }.onFailure { log("stopped: ${it.javaClass.simpleName} ${it.message}") }
        }, "gamejolt-probe").start()
    }

    private fun log(line: String, urls: SecretScrub.Urls = SecretScrub.Urls.SHORT) =
        StoresState.logLine("gamejolt probe: ${line.replace('\n', ' ').take(500)}", urls)

    private fun isGameJoltHost(url: String): Boolean {
        val host = runCatching { URL(url).host.lowercase() }.getOrNull() ?: return false
        return host == "gjcdn.net" || host.endsWith(".gjcdn.net") ||
            host == "gamejolt.com" || host.endsWith(".gamejolt.com") ||
            host == "gamejolt.net" || host.endsWith(".gamejolt.net")
    }

    private fun open(url: String, accept: String): HttpURLConnection {
        var target = url
        repeat(MAX_REDIRECTS + 1) {
            require(target.startsWith("https://") && isGameJoltHost(target)) { "host not allowed" }
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", StoreNet.BROWSER_UA)
                setRequestProperty("Accept", accept)
            }
            val code = conn.responseCode
            val location = conn.getHeaderField("Location")
            if (code !in 300..399 || location == null) return conn
            conn.disconnect()
            target = URL(URL(target), location).toString()
        }
        throw java.io.IOException("too many redirects")
    }

    private fun fetch(url: String, accept: String, limit: Int): Reply? = try {
        val conn = open(url, accept)
        try {
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = stream?.use { s ->
                val out = StringBuilder()
                val buf = ByteArray(16_384)
                var total = 0
                while (total < limit) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.append(String(buf, 0, n, Charsets.UTF_8))
                    total += n
                }
                out.toString()
            }.orEmpty()
            Reply(code, conn.contentType.orEmpty(), conn.url.toString(), text)
        } finally {
            runCatching { conn.disconnect() }
        }
    } catch (e: Exception) {
        log("GET failed ${e.javaClass.simpleName} ${e.message}")
        null
    }

    private fun routeWords(found: Collection<String>): Set<String> {
        val counts = HashMap<String, Int>()
        found.forEach { raw ->
            val path = raw.substringBefore('?').substringBefore('#').substringAfter("://").let { if (raw.contains("://")) it.substringAfter('/', "") else it }
            path.split('/').filter { it.matches(WORD) }.toSet().forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        return counts.filterValues { it >= MIN_ROUTE_COUNT }.keys
    }

    private fun keys(value: Any?, depth: Int): String = when {
        value is org.json.JSONObject && depth > 0 -> value.keys().asSequence().take(40).joinToString(",", "{", "}") { k -> k + ":" + keys(value.opt(k), depth - 1) }
        value is org.json.JSONObject -> "{...}"
        value is org.json.JSONArray -> "[" + (if (value.length() > 0 && depth > 0) keys(value.opt(0), depth - 1) else "") + "]"
        else -> ""
    }

    private fun shape(raw: String, common: Set<String>): String {
        val clean = raw.substringBefore('?').substringBefore('#')
        val absoluteUrl = clean.startsWith("http://") || clean.startsWith("https://")
        val host = if (absoluteUrl) runCatching { URL(clean).host.lowercase() }.getOrNull().orEmpty() else ""
        val path = if (absoluteUrl) runCatching { URL(clean).path }.getOrNull().orEmpty() else clean
        val segments = path.split('/').map { seg ->
            when {
                seg.isEmpty() -> seg
                seg.matches(WORD) && seg in common -> seg
                seg.length <= 40 && (seg.startsWith("{") || seg.startsWith("$")) && seg.matches(TEMPLATE) -> seg
                else -> "*"
            }
        }
        val scheme = if (clean.startsWith("http://")) "http" else "https"
        val prefix = if (!absoluteUrl) "" else if (isGameJoltHost(clean)) "$scheme://$host" else "$scheme://[host]"
        return prefix + segments.joinToString("/")
    }

    private fun report(label: String, url: String, accept: String, limit: Int): Reply? {
        val r = fetch(url, accept, limit)
        if (r == null) return null
        log("$label code=${r.code} type=${r.type} bytes=${r.body.length} url=${r.finalUrl}")
        if (r.type.contains("json", ignoreCase = true)) {
            val structure = runCatching { keys(org.json.JSONTokener(r.body).nextValue(), 3) }.getOrDefault("")
            log("$label json keys: $structure")
        }
        return r
    }

    private fun scan(label: String, text: String, into: MutableSet<String>) {
        val before = into.size
        endpoint.findAll(text).forEach { into.add(it.groupValues[1]) }
        absolute.findAll(text).forEach { into.add(it.value) }
        log("$label found ${into.size - before} new strings")
    }

    private fun probe(input: String) {
        log("start input=$input")
        val id = gameId.find(input)?.groupValues?.get(1)
        if (!input.startsWith("https://") || !isGameJoltHost(input)) {
            log("the link must be an https Game Jolt address")
            return
        }
        val game = report("game page", input, "text/html", MAX_PAGE) ?: return
        val found = linkedSetOf<String>()
        scan("game page", game.body, found)
        val host = URL(game.finalUrl)
        val scripts = scriptSrc.findAll(game.body).map { it.groupValues[1] }
            .map { runCatching { URL(host, it).toString() }.getOrNull() }
            .filterNotNull()
            .filter { isGameJoltHost(it) }
            .distinct()
            .toList()
        log("scripts on page: ${scripts.size}")
        scripts.take(MAX_SCRIPTS).forEach { src ->
            val js = fetch(src, "*/*", MAX_SCRIPT)
            if (js == null) return@forEach
            log("script code=${js.code} bytes=${js.body.length} url=$src")
            scan("script", js.body, found)
        }
        val common = routeWords(found)
        found.map { shape(it, common) }.distinct().take(MAX_FOUND).forEach { log("endpoint: $it", SecretScrub.Urls.KEEP_PATH) }
        log("endpoint strings total=${found.size}")
        if (id != null) {
            report("candidate overview", "https://gamejolt.com/site-api/web/discover/games/overview/$id", "application/json", 60_000)
        } else {
            log("no numeric game id in the input, skipped the overview candidate")
        }
        report("game api without a key", "https://api.gamejolt.com/api/game/v1_2/users/", "application/json", 20_000)
        val query = input.substringAfter("/games/", "").substringBefore('/')
        if (query.isNotEmpty()) report("search page", "https://gamejolt.com/search?q=" + java.net.URLEncoder.encode(query, "UTF-8"), "text/html", MAX_PAGE)
        log("done")
    }
}
