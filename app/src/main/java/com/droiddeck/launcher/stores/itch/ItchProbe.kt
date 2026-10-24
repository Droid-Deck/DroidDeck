package com.droiddeck.launcher.stores.itch

import android.content.Context
import com.droiddeck.launcher.stores.StoreNet
import com.droiddeck.launcher.stores.StoresState
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object ItchProbe {
    private const val PAGE = "https://kay-yu.itch.io/holocure"
    private const val API = "https://api.itch.io"

    private class Answer(val code: Int, val type: String, val length: String, val range: String, val body: String)

    fun run(context: Context) {
        val app = context.applicationContext
        Thread({
            runCatching { probe(app) }.onFailure { log("stopped: ${it.javaClass.simpleName} ${it.message}") }
        }, "itch-probe").start()
    }

    private fun log(line: String) = StoresState.logLine("itch probe: $line")

    private fun request(url: String, key: String?, range: String? = null): Answer? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", StoreNet.BROWSER_UA)
            conn.setRequestProperty("Accept", "*/*")
            if (key != null) conn.setRequestProperty("Authorization", "Bearer $key")
            if (range != null) conn.setRequestProperty("Range", range)
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = if (range != null) "" else stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            Answer(code, conn.contentType.orEmpty(), conn.getHeaderField("Content-Length").orEmpty(), conn.getHeaderField("Content-Range").orEmpty(), body)
        } finally {
            conn.disconnect()
        }
    }.getOrElse { log("$url failed: ${it.javaClass.simpleName}"); null }

    private fun probe(app: Context) {
        val key = ItchAuth.key(app)
        log("signed in: ${key.isNotBlank()}")
        val page = request(PAGE, null) ?: return
        log("game page: ${page.code} ${page.type} ${page.body.length} chars")
        val path = Regex("""name="itch:path"[^>]*content="([^"]+)"""").find(page.body)?.groupValues?.get(1)
        val alt = Regex("""content="([^"]*)"[^>]*name="itch:path"""").find(page.body)?.groupValues?.get(1)
        log("itch:path meta: ${path ?: alt ?: "not found"}")
        val ids = Regex("""game_id["':= ]+(\d+)""").findAll(page.body).map { it.groupValues[1] }.distinct().take(5).toList()
        log("game_id matches: $ids")
        val id = (path ?: alt)?.substringAfterLast('/')?.takeIf { it.all(Char::isDigit) } ?: ids.firstOrNull()
        log("game id used: ${id ?: "none"}")
        if (key.isBlank()) { log("no key: the API steps are skipped"); return }
        if (id != null) {
            val game = request("$API/games/$id", key)
            log("GET /games/{id}: ${game?.code}")
            runCatching { JSONObject(game?.body.orEmpty()).optJSONObject("game") }.getOrNull()?.let { g ->
                log("game fields: ${g.keys().asSequence().toList()}")
                log("game: title=${g.optString("title")} type=${g.optString("type")} classification=${g.optString("classification")} min_price=${g.opt("min_price")} can_be_bought=${g.opt("can_be_bought")} traits=${g.optJSONArray("traits")}")
            }
            val uploads = request("$API/games/$id/uploads", key)
            log("GET /games/{id}/uploads without a download key: ${uploads?.code}")
            val list: JSONArray? = runCatching { JSONObject(uploads?.body.orEmpty()).optJSONArray("uploads") }.getOrNull()
            log("uploads: ${list?.length()}")
            var first: String? = null
            for (i in 0 until (list?.length() ?: 0)) {
                val u = list!!.optJSONObject(i) ?: continue
                log("upload ${u.optString("id")}: file=${u.optString("filename")} size=${u.opt("size")} type=${u.optString("type")} storage=${u.optString("storage")} demo=${u.opt("demo")} platforms=${u.optJSONObject("platforms")} traits=${u.optJSONArray("traits")} updated=${u.optString("updated_at")}")
                if (first == null && u.optString("storage") != "external") first = u.optString("id")
            }
            if (first != null) {
                val dl = request("$API/uploads/$first/download", key, "bytes=0-0")
                log("download without a download key: ${dl?.code} type=${dl?.type} length=${dl?.length} range=${dl?.range}")
            }
        }
        val collections = request("$API/profile/collections", key)
        log("GET /profile/collections: ${collections?.code}")
        runCatching { JSONObject(collections?.body.orEmpty()).optJSONArray("collections") }.getOrNull()?.let { c ->
            log("collections: ${c.length()}")
            for (i in 0 until minOf(3, c.length())) c.optJSONObject(i)?.let { log("collection ${it.opt("id")}: ${it.optString("title")} games=${it.opt("games_count")}") }
        }
        val search = request("$API/search/games?query=zelda&page=1", key)
        log("GET /search/games: ${search?.code}")
        runCatching { JSONObject(search?.body.orEmpty()).optJSONArray("games") }.getOrNull()?.let { g ->
            log("search games: ${g.length()} first=${g.optJSONObject(0)?.optString("title")}")
        }
        log("done")
    }
}
