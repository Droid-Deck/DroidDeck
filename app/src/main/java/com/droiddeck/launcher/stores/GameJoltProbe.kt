package com.droiddeck.launcher.stores

import com.droiddeck.launcher.core.SecretScrub
import java.net.HttpURLConnection
import java.net.URL

internal object GameJoltProbe {
    private const val MAX_PAGE = 800_000
    private const val MAX_SCRIPT = 3_000_000
    private const val MAX_SCRIPTS = 700
    private const val PARALLEL = 8
    private const val MAX_JSON = 3_000_000
    private const val MAX_FOUND = 300
    private const val JSON_DEPTH = 5
    private const val MAX_STRUCTURE_LINES = 30
    private const val MAX_REDIRECTS = 5
    private const val MIN_ROUTE_COUNT = 3
    private const val MAX_WRAPS = 4

    private val endpoint = Regex("[\"'`](/?(?:site-api|web|x)/[^\"'`\\s]{3,140})[\"'`]")
    private val absolute = Regex("https?://[A-Za-z0-9.\\-]*gamejolt\\.(?:com|net)/[^\"'`\\s<>)]{0,140}")
    private val scriptRef = Regex("[\"'`]([^\"'`\\s]{3,140}\\.js)[\"'`]")
    private val interesting = Regex("discover|download|build|search|library|token|login|oauth|auth|game|site-api", RegexOption.IGNORE_CASE)
    private val scriptSrc = Regex("<script[^>]+src=[\"']([^\"']+)[\"']")
    private val WORD = Regex("[A-Za-z][A-Za-z_\\-]{0,30}")
    private val TEMPLATE = Regex("[{$][A-Za-z0-9_.{}$]{0,38}")
    private val gameId = Regex("/(\\d+)/?(?:[?#].*)?$")

    private fun resolve(base: String, ref: String): String? = runCatching {
        val root = URL(base)
        when {
            ref.startsWith("http://") || ref.startsWith("https://") -> ref
            ref.startsWith("/") -> URL(root, ref).toString()
            ref.startsWith("assets/") -> URL(root, "/$ref").toString()
            else -> URL(root, ref).toString()
        }
    }.getOrNull()

    private val noise = Regex("/dash/(developer|communities|sites|avatar|header|profile|email)|communities|/posts/|stickers|polls|realms|custom-buttons|collaborators|playlists", RegexOption.IGNORE_CASE)
    private val markers = listOf("get-download-url/", "/web/download/info/", "/web/discover/games\"", "/web/discover/games`", "/web/search\"", "/web/search`", "/web/auth/check\"", "/web/library\"", "/web/checkout/setup-order")
    private val IDENT = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
    private val CODE_WORDS = setOf(
        "id", "key", "game", "build", "builds", "params", "query", "page", "sort", "filter", "type", "os", "limit", "price", "free", "tag", "tags",
        "section", "offset", "cursor", "search", "discover", "web", "download", "info", "games", "get", "url", "post", "method", "body", "headers",
        "Api", "sendRequest", "sendFieldsRequest", "noErrorRedirect", "detach", "package", "release", "payload", "user", "token", "owned", "sellable",
        "checkout", "order", "library", "auth", "check", "true", "false", "null", "new", "return", "await", "async", "const", "let", "var",
    )
    private const val MAX_CONTEXTS = 3
    private const val CONTEXT_BEFORE = 60
    private const val CONTEXT_AFTER = 180

    private fun maskCode(text: String): String =
        IDENT.replace(text.replace('\n', ' ')) { m -> if (m.value.length <= 2 || m.value in CODE_WORDS) m.value else "_" }

    private val CHUNK_EXTENSIONS = setOf("js", "mjs", "css", "json", "map")
    private val CHUNK_DIRS = setOf("assets", "chunks", "static", "js", "dist")

    private fun chunkShape(raw: String): String {
        val clean = raw.substringBefore('?').substringBefore('#')
        val absoluteUrl = clean.startsWith("http://") || clean.startsWith("https://")
        val host = if (absoluteUrl) runCatching { URL(clean).host.lowercase() }.getOrNull().orEmpty() else ""
        val path = if (absoluteUrl) runCatching { URL(clean).path }.getOrNull().orEmpty() else clean
        val parts = path.split('/')
        val shaped = parts.mapIndexed { i, seg ->
            when {
                seg.isEmpty() -> seg
                i == parts.lastIndex -> seg.substringAfterLast('.', "").lowercase().let { ext -> if (ext in CHUNK_EXTENSIONS) "*.$ext" else "*" }
                seg in CHUNK_DIRS || seg == "." || seg == ".." -> seg
                else -> "*"
            }
        }
        val prefix = if (absoluteUrl && isGameJoltHost(clean)) "https://$host" else ""
        return prefix + shaped.joinToString("/")
    }

    private class Reply(val code: Int, val type: String, val finalUrl: String, val body: String)

    fun run(input: String) {
        Thread({
            runCatching { probe(input.trim()) }.onFailure { log("stopped: ${it.javaClass.simpleName} ${it.message}") }
        }, "gamejolt-probe").start()
    }

    private fun log(line: String, urls: SecretScrub.Urls = SecretScrub.Urls.SHORT) =
        StoresState.logLine("gamejolt probe: ${line.replace('\n', ' ').take(500)}", urls)

    private fun logLong(label: String, text: String) {
        text.chunked(450).take(MAX_STRUCTURE_LINES).forEachIndexed { i, part -> log("$label[$i] $part") }
    }

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

    private fun routeWords(found: Collection<String>, minCount: Int = MIN_ROUTE_COUNT): Set<String> {
        val counts = HashMap<String, Int>()
        found.forEach { raw ->
            val path = raw.substringBefore('?').substringBefore('#').substringAfter("://").let { if (raw.contains("://")) it.substringAfter('/', "") else it }
            path.split('/').filter { it.matches(WORD) }.toSet().forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        return counts.filterValues { it >= minCount }.keys
    }

    private fun keys(value: Any?, depth: Int, wraps: Int = MAX_WRAPS): String = when {
        value is org.json.JSONObject && depth > 0 -> value.keys().asSequence().take(40).joinToString(",", "{", "}") { k -> k + ":" + keys(value.opt(k), depth - 1) }
        value is org.json.JSONObject -> "{...}"
        value is org.json.JSONArray -> "[" + (if (value.length() > 0 && wraps > 0) keys(value.opt(0), depth, wraps - 1) else "") + "]"
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
            val structure = runCatching { keys(org.json.JSONTokener(r.body).nextValue(), JSON_DEPTH) }.getOrElse { "unreadable: ${it.javaClass.simpleName}" }
            logLong("$label json keys", structure)
        }
        return r
    }

    private fun scan(label: String, text: String, into: MutableSet<String>, quiet: Boolean = false) {
        val before = into.size
        endpoint.findAll(text).forEach { into.add(it.groupValues[1]) }
        absolute.findAll(text).forEach { into.add(it.value) }
        if (!quiet) log("$label found ${into.size - before} new strings")
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
        val queue = ArrayDeque(scriptSrc.findAll(game.body).map { it.groupValues[1] }
            .mapNotNull { runCatching { URL(host, it).toString() }.getOrNull() }
            .filter { isGameJoltHost(it) }.distinct().toList())
        log("scripts on page: ${queue.size}")
        val seen = HashSet<String>(queue)
        val fromScripts = linkedSetOf<String>()
        var fetched = 0
        val contexts = HashMap<String, Int>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(PARALLEL)
        try {
            while (queue.isNotEmpty() && fetched < MAX_SCRIPTS) {
                val batch = ArrayList<String>()
                while (queue.isNotEmpty() && batch.size < PARALLEL && fetched + batch.size < MAX_SCRIPTS) batch.add(queue.removeFirst())
                val replies = batch.map { src -> pool.submit(java.util.concurrent.Callable<Reply?> { fetch(src, "*/*", MAX_SCRIPT) }) }.map { it.get() }
                replies.forEach { js ->
                    fetched++
                    if (js == null) return@forEach
                    if (js.code != 200) log("script code=${js.code} url=${chunkShape(js.finalUrl)}", SecretScrub.Urls.KEEP_PATH)
                    scan("script", js.body, fromScripts, quiet = true)
                    markers.forEach { marker ->
                        var from = 0
                        while ((contexts[marker] ?: 0) < MAX_CONTEXTS) {
                            val at = js.body.indexOf(marker, from)
                            if (at < 0) break
                            from = at + marker.length
                            contexts[marker] = (contexts[marker] ?: 0) + 1
                            val window = js.body.substring(maxOf(0, at - CONTEXT_BEFORE), minOf(js.body.length, at + marker.length + CONTEXT_AFTER))
                            log("code shape [${marker.trim('"', '`')}]: ${maskCode(window)}", SecretScrub.Urls.KEEP_PATH)
                        }
                    }
                    val refs = scriptRef.findAll(js.body).map { it.groupValues[1] }.toList()
                    if (fetched == 1) {
                        log("script refs found: ${refs.size}")
                        refs.map { chunkShape(it) }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(8)
                            .forEach { log("script ref shape: ${it.key} x${it.value}", SecretScrub.Urls.KEEP_PATH) }
                    }
                    refs.mapNotNull { resolve(js.finalUrl, it) }
                        .filter { isGameJoltHost(it) && seen.add(it) }
                        .forEach { queue.addLast(it) }
                }
            }
        } finally {
            pool.shutdown()
        }
        log("scripts fetched: $fetched, still queued: ${queue.size}")
        val common = routeWords(found)
        found.map { shape(it, common) }.distinct().take(MAX_FOUND).forEach { log("page endpoint: $it", SecretScrub.Urls.KEEP_PATH) }
        val scriptWords = routeWords(fromScripts, 1)
        val ranked = fromScripts.map { shape(it, scriptWords) }.distinct().filterNot { noise.containsMatchIn(it) }.sortedByDescending { interesting.containsMatchIn(it) }
        ranked.take(MAX_FOUND).forEach { log("script endpoint: $it", SecretScrub.Urls.KEEP_PATH) }
        log("endpoint strings total=${found.size + fromScripts.size}")
        if (id != null) {
            val overview = report("candidate overview", "https://gamejolt.com/site-api/web/discover/games/overview/$id", "application/json", MAX_JSON)
            val buildId = overview?.let {
                runCatching {
                    org.json.JSONObject(it.body).optJSONObject("payload")?.optJSONArray("builds")?.optJSONObject(0)?.optLong("id") ?: 0L
                }.getOrDefault(0L)
            } ?: 0L
            if (buildId > 0) {
                val reply = report("candidate download url", "https://gamejolt.com/site-api/web/discover/games/builds/get-download-url/$buildId", "application/json", 50_000)
                reply?.let { r ->
                    runCatching {
                        val node = org.json.JSONObject(r.body).optJSONObject("payload")?.optString("url").orEmpty()
                        if (node.isNotEmpty()) log("candidate download url shape: ${shape(node, emptySet())}", SecretScrub.Urls.KEEP_PATH)
                    }
                }
            } else {
                log("no build id in the overview, skipped the download candidate")
            }
        } else {
            log("no numeric game id in the input, skipped the overview candidate")
        }
        report("candidate browse", "https://gamejolt.com/site-api/web/discover/games", "application/json", MAX_JSON)
        val slug = input.substringAfter("/games/", "").substringBefore('/')
        if (slug.isNotEmpty()) report("candidate search", "https://gamejolt.com/site-api/web/search?q=" + java.net.URLEncoder.encode(slug, "UTF-8"), "application/json", MAX_JSON)
        report("game api without a key", "https://api.gamejolt.com/api/game/v1_2/users/", "application/json", 20_000)
        val query = input.substringAfter("/games/", "").substringBefore('/')
        if (query.isNotEmpty()) report("search page", "https://gamejolt.com/search?q=" + java.net.URLEncoder.encode(query, "UTF-8"), "text/html", MAX_PAGE)
        log("done")
    }
}
