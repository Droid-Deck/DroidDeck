package com.droiddeck.launcher.stores.gamejolt

import android.content.Context
import com.droiddeck.launcher.stores.StoreNet
import com.droiddeck.launcher.stores.StoresState
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object GameJoltSessionProbe {
    private const val MAX_LINES = 40
    private const val MAX_PATHS = 120
    private const val DEPTH = 7
    private val wordsKept = setOf("type", "currency", "status", "key", "message", "error", "reason", "os", "code", "kind", "state", "access", "level")
    private val interesting = Regex("own|access|purchas|bought|paid|licen|price|amount|sellable|pricing|key|order|library|count|total|has_|is_|can_", RegexOption.IGNORE_CASE)
    private val trailingId = Regex("/(\\d+)/?(?:[?#].*)?$")

    fun run(context: Context, input: String) {
        val app = context.applicationContext
        Thread({
            runCatching { probe(app, input.trim()) }.onFailure { log("stopped: ${it.javaClass.simpleName} ${it.message}") }
        }, "gamejolt-session-probe").start()
    }

    private fun log(line: String) = StoresState.logLine("gamejolt session probe: ${line.replace('\n', ' ').take(450)}")

    private fun logLong(label: String, text: String) {
        text.chunked(420).take(MAX_LINES).forEachIndexed { i, part -> log("$label[$i] $part") }
    }

    private fun scalar(key: String, value: Any?): String? = when (value) {
        is Boolean -> value.toString()
        is Int, is Long, is Double -> value.toString()
        is String -> if (key in wordsKept && value.length <= 60 && !value.contains("://")) "\"$value\"" else "s${value.length.coerceAtMost(99)}"
        JSONObject.NULL -> "null"
        else -> null
    }

    private fun flatten(value: Any?, path: String, depth: Int, out: MutableMap<String, String>) {
        if (out.size >= MAX_PATHS * 4) return
        when (value) {
            is JSONObject -> if (depth > 0) value.keys().asSequence().toList().sorted().forEach { k -> flatten(value.opt(k), "$path.$k", depth - 1, out) } else out[path] = "{...}"
            is JSONArray -> {
                out["$path#"] = value.length().toString()
                if (value.length() > 0) flatten(value.opt(0), "$path[0]", depth, out)
            }
            else -> scalar(path.substringAfterLast('.').substringBefore('['), value)?.let { out[path] = it }
        }
    }

    private fun flat(json: JSONObject?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        flatten(json, "", DEPTH, out)
        return out
    }

    private fun describe(label: String, answer: GameJoltAnswer) {
        val top = answer.json?.keys()?.asSequence()?.toList()?.sorted()?.joinToString(",").orEmpty()
        log("$label code=${answer.code} top=[$top] user=${answer.user != null}")
        val map = flat(answer.payload)
        val picked = map.filter { (path, _) -> interesting.containsMatchIn(path) }.entries.take(MAX_PATHS)
        logLong("$label facts", picked.joinToString(" ") { "${it.key}=${it.value}" })
        logLong("$label paths", map.keys.take(MAX_PATHS).joinToString(" "))
    }

    private fun probe(app: Context, input: String) {
        val cookies = GameJoltAuth.cookies(app)
        log("signedIn=${cookies.isNotBlank()} userId=${GameJoltAuth.userId(app).isNotBlank()} cookieNames=[${cookies.split(';').joinToString(",") { it.substringBefore('=').trim() }}]")
        if (cookies.isBlank()) {
            log("not signed in, sign in to Game Jolt first")
            return
        }
        val gameId = trailingId.find(input)?.groupValues?.get(1) ?: input.filter { it.isDigit() }
        if (gameId.isBlank()) {
            log("no game id in the link")
            return
        }
        for (path in listOf("/web/auth/check", "/web/dash/shell", "/web/library", "/web/library/playlists", "/web/dash/purchases")) {
            describe(path, GameJoltApi.get(path, cookies))
        }
        for (kind in listOf("owned", "followed", "developer", "recommended")) {
            val answer = GameJoltApi.get("/web/library/games/$kind", cookies)
            val games = answer.payload?.optJSONArray("games")
            log("/web/library/games/$kind code=${answer.code} games=${games?.length() ?: -1} ids=[${ids(games)}] keys=[${answer.payload?.keys()?.asSequence()?.toList()?.sorted()?.joinToString(",").orEmpty()}]")
        }
        val library = GameJoltApi.get("/web/library", cookies).payload
        val playlists = library?.optJSONArray("playlists") ?: GameJoltApi.get("/web/library/playlists", cookies).payload?.optJSONArray("playlists")
        log("playlists=${playlists?.length() ?: -1}")
        if (playlists != null) for (i in 0 until minOf(playlists.length(), 3)) {
            val playlist = playlists.optJSONObject(i) ?: continue
            val id = playlist.optLong("id")
            val answer = GameJoltApi.get("/web/library/games/playlist/$id", cookies)
            val games = answer.payload?.optJSONArray("games")
            log("playlist[$i] keys=[${playlist.keys().asSequence().toList().sorted().joinToString(",")}] code=${answer.code} games=${games?.length() ?: -1} ids=[${ids(games)}]")
        }
        val signed = GameJoltApi.overview(gameId, cookies)
        val guest = GameJoltApi.overview(gameId, "")
        describe("overview signed", signed)
        describe("overview guest", guest)
        val signedMap = flat(signed.payload)
        val guestMap = flat(guest.payload)
        logLong("only signed", (signedMap.keys - guestMap.keys).sorted().take(MAX_PATHS).joinToString(" "))
        logLong("only guest", (guestMap.keys - signedMap.keys).sorted().take(MAX_PATHS).joinToString(" "))
        logLong("value differs", signedMap.keys.intersect(guestMap.keys).filter { signedMap[it] != guestMap[it] }.sorted().take(MAX_PATHS).joinToString(" ") { "$it signed=${signedMap[it]} guest=${guestMap[it]}" })
        val builds = GameJoltBuilds.parse(signed.payload)
        log("builds signed=${builds.size} guest=${GameJoltBuilds.parse(guest.payload).size}")
        builds.take(6).forEach { log("build ${it.id} package=${it.packageId} platforms=${it.platforms.joinToString(",")} size=${it.size} installable=${it.installable} launch=${it.executable.isNotBlank()}") }
        val target = builds.firstOrNull { it.installable } ?: builds.firstOrNull()
        if (target == null) {
            log("no build to ask a download link for")
            return
        }
        for ((label, jar) in listOf("signed" to cookies, "guest" to "")) {
            val answer = GameJoltApi.post("/web/discover/games/builds/get-download-url/${target.id}", jar)
            val url = answer.payload?.optString("url").orEmpty()
            val host = runCatching { URL(url).host }.getOrNull().orEmpty()
            log("download $label code=${answer.code} top=[${answer.json?.keys()?.asSequence()?.toList()?.sorted()?.joinToString(",").orEmpty()}] hasUrl=${url.startsWith("https://")} host=$host")
            logLong("download $label facts", flat(answer.payload).entries.take(MAX_PATHS).joinToString(" ") { "${it.key}=${it.value}" })
            if (url.startsWith("https://")) rangeCheck(url)
        }
    }

    private fun ids(games: JSONArray?): String =
        games?.let { list -> (0 until minOf(list.length(), 5)).joinToString(",") { list.optJSONObject(it)?.optLong("id")?.toString().orEmpty() } }.orEmpty()

    private fun rangeCheck(url: String) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", StoreNet.BROWSER_UA)
                setRequestProperty("Range", "bytes=0-0")
            }
            val code = conn.responseCode
            log("download link answered $code range=${conn.getHeaderField("Content-Range").orEmpty()} length=${conn.contentLengthLong} type=${conn.contentType.orEmpty().take(40)}")
        } catch (e: Exception) {
            log("download link check failed ${e.javaClass.simpleName}")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
