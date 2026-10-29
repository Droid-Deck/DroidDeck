package com.droiddeck.launcher.stores

import java.net.HttpURLConnection
import java.net.URL

internal object GameJoltProbe {
    private const val MAX_PAGE = 800_000
    private const val MAX_SCRIPT = 3_000_000
    private const val MAX_SCRIPTS = 8
    private const val MAX_FOUND = 80

    private val endpoint = Regex("[\"'`](/?(?:site-api|web|x)/[^\"'`\\s]{3,140})[\"'`]")
    private val absolute = Regex("https?://[A-Za-z0-9.\\-]*gamejolt\\.(?:com|net)/[^\"'`\\s<>)]{0,140}")
    private val scriptSrc = Regex("<script[^>]+src=[\"']([^\"']+)[\"']")
    private val gameId = Regex("/(\\d+)/?(?:[?#].*)?$")

    private class Reply(val code: Int, val type: String, val finalUrl: String, val body: String)

    fun run(input: String) {
        Thread({
            runCatching { probe(input.trim()) }.onFailure { log("stopped: ${it.javaClass.simpleName} ${it.message}") }
        }, "gamejolt-probe").start()
    }

    private fun log(line: String) = StoresState.logLine("gamejolt probe: ${line.replace('\n', ' ').take(500)}")

    private fun fetch(url: String, accept: String, limit: Int): Reply? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", StoreNet.BROWSER_UA)
            setRequestProperty("Accept", accept)
        }
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
        log("GET failed $url: ${e.javaClass.simpleName} ${e.message}")
        null
    }

    private fun report(label: String, url: String, accept: String, limit: Int): Reply? {
        val r = fetch(url, accept, limit)
        if (r == null) return null
        log("$label code=${r.code} type=${r.type} bytes=${r.body.length} url=${r.finalUrl}")
        log("$label head: ${r.body.take(400)}")
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
        val game = report("game page", input, "text/html", MAX_PAGE) ?: return
        val found = linkedSetOf<String>()
        scan("game page", game.body, found)
        val host = URL(game.finalUrl)
        val scripts = scriptSrc.findAll(game.body).map { it.groupValues[1] }
            .map { runCatching { URL(host, it).toString() }.getOrNull() }
            .filterNotNull()
            .filter { it.contains("gamejolt") }
            .distinct()
            .toList()
        log("scripts on page: ${scripts.size}")
        scripts.take(MAX_SCRIPTS).forEach { src ->
            val js = fetch(src, "*/*", MAX_SCRIPT)
            if (js == null) return@forEach
            log("script code=${js.code} bytes=${js.body.length} url=$src")
            scan("script", js.body, found)
        }
        found.take(MAX_FOUND).forEach { log("endpoint: $it") }
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
