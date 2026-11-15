package com.droiddeck.launcher.stores.gog

import android.content.Context
import com.droiddeck.launcher.stores.StoreLanguage
import com.droiddeck.launcher.stores.StoreNet
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

internal object GogContent {
    const val SYSTEM = "https://content-system.gog.com"
    const val CDN = "https://gog-cdn-fastly.gog.com/content-system"

    fun token(context: Context) = GogAuth.validToken(context) ?: error("Sign in to GOG and retry")

    fun builds(id: String, token: String): JSONObject {
        val result = json("$SYSTEM/products/$id/os/windows/builds?generation=2", token)
        if (result.optJSONArray("items")?.length() != 0) return result
        return json("$SYSTEM/products/$id/os/windows/builds?generation=1", token)
    }

    fun product(id: String, token: String): JSONObject = json("https://api.gog.com/products/$id?expand=downloads", token)

    fun downloadFilename(url: String): String = java.net.URLDecoder.decode(URL(url).path.substringAfterLast('/').replace("+", "%2B"), "UTF-8")

    fun resolveDownload(download: GogOfflineDownload, token: String): GogOfflineDownload = download.copy(parts = download.parts.map { part ->
        val resolved = GogDownloadManager.resolveRedirect(part.url, token) ?: error("The GOG download link is unavailable")
        val connection = URL(resolved).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 20_000; connection.readTimeout = 30_000
            check(connection.responseCode in 200..299) { "GOG download metadata failed (HTTP ${connection.responseCode})" }
            val disposition = connection.getHeaderField("Content-Disposition").orEmpty()
            val supplied = Regex("filename=\"?([^\";]+)", RegexOption.IGNORE_CASE).find(disposition)?.groupValues?.get(1).orEmpty()
            val remote = downloadFilename(resolved).takeIf { it.endsWith(".exe", true) || it.endsWith(".bin", true) }.orEmpty()
            val name = part.name.ifBlank { supplied.ifBlank { remote } }
            check(name.isNotBlank()) { "GOG did not provide the installer part filename" }
            part.copy(name = name, size = connection.contentLengthLong.coerceAtLeast(0))
        } finally { connection.disconnect() }
    })

    fun json(url: String, token: String? = null): JSONObject {
        require(URL(url).protocol == "https") { "GOG metadata requires HTTPS" }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000; connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", GogAuth.GALAXY_UA)
            if (token != null && URL(url).host.let { it == "gog.com" || it.endsWith(".gog.com") }) connection.setRequestProperty("Authorization", "Bearer $token")
            check(connection.responseCode in 200..299) { "GOG metadata request failed (HTTP ${connection.responseCode})" }
            val raw = connection.inputStream.readBytes()
            val text = when {
                raw.size > 1 && raw[0] == 0x78.toByte() -> InflaterInputStream(raw.inputStream()).bufferedReader().use { it.readText() }
                raw.size > 1 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte() -> GZIPInputStream(raw.inputStream()).bufferedReader().use { it.readText() }
                else -> raw.toString(Charsets.UTF_8)
            }
            return JSONObject(text)
        } finally { connection.disconnect() }
    }

    fun snapshot(old: GogInstallRecord, build: GogBuild, token: String,
        metadata: (String, String?) -> JSONObject = ::json): GogInstallRecord {
        check(build.link.isNotBlank()) { "GOG build manifest is unavailable" }
        val manifest = metadata(build.link, token)
        val files = JSONArray()
        val depots = if (build.generation == 1) manifest.optJSONObject("product")?.optJSONArray("depots") ?: manifest.optJSONArray("depot") else manifest.optJSONArray("depots")
        check(depots != null) { "GOG build has no depot list" }
        val resolvedLanguage = StoreLanguage.pick(StoreLanguage.codesFromDepots(depots), old.language)
        val selectedBitness = if (depots.objects().any { it.optJSONArray("osBitness")?.strings()?.contains("64") == true }) "64" else "32"
        val owned = if (old.products.any { it != old.id }) StoreNet.get("https://embed.gog.com/user/data/games", bearer = token)?.let { JSONObject(it).optJSONArray("owned")?.strings()?.toSet() } else emptySet()
        val products = old.products.filter { it == old.id || owned?.contains(it) == true }
        check(old.products.all { it == old.id || owned?.contains(it) == true }) { "Could not confirm ownership of installed GOG DLC" }
        for (depot in depots.objects()) {
            if (depot.has("redist")) continue
            val ids = depot.optJSONArray("gameIDs")?.strings() ?: listOf(depot.optString("productId", old.id))
            val product = ids.firstOrNull { it in products } ?: continue
            val languages = depot.optJSONArray("languages")?.strings().orEmpty()
            if (!languageSelected(languages, resolvedLanguage)) continue
            val bitness = depot.optJSONArray("osBitness")?.strings().orEmpty()
            if (bitness.isNotEmpty() && selectedBitness !in bitness) continue
            val hash = depot.optString("manifest")
            val content = when {
                hash.isNotEmpty() && build.generation == 2 -> metadata("$CDN/v2/meta/${cdnPath(hash)}", null)
                hash.isNotEmpty() -> {
                    val timestamp = manifest.getJSONObject("product").getString("timestamp")
                    metadata("$CDN/v1/manifests/$product/windows/$timestamp/$hash", token)
                }
                depot.has("files") -> JSONObject().put("depot", depot)
                else -> error("GOG depot manifest is missing")
            }
            val entries = content.getJSONObject("depot").optJSONArray(if (build.generation == 2) "items" else "files") ?: error("GOG depot files are missing")
            for (entry in entries.objects()) {
                val type = entry.optString("type")
                check(type != "DepotLink") { "This GOG build contains unsupported file links" }
                val support = depot.optBoolean("support") || entry.optBoolean("support") || entry.optJSONArray("flags")?.strings()?.contains("support") == true
                val relative = entry.getString("path").replace('\\', '/').trimEnd('/').let { if (build.generation == 1) it.trimStart('/') else it }
                val path = if (support) (if (build.generation == 1) "support/$relative" else "support/$product/$relative") else relative
                val directory = type == "DepotDirectory" || entry.has("directory")
                val chunks = entry.optJSONArray("chunks") ?: JSONArray()
                val size = if (build.generation == 2) chunks.objects().sumOf { it.optLong("size") } else entry.optLong("size")
                files.put(JSONObject().put("path", path).put("size", size).put("directory", directory)
                    .put("md5", entry.optString("md5").ifBlank { entry.optString("hash") }.takeIf { it.matches(Regex("[a-fA-F0-9]{32}")) }.orEmpty())
                    .put("sha256", entry.optString("sha256")).put("chunks", chunks).put("product", product)
                    .put("offset", entry.optLong("offset")).put("remotePath", entry.optString("url").substringBefore('?')))
            }
        }
        check(files.length() > 0) { "GOG build contains no matching files" }
        return old.copy(source = "depot", generation = build.generation, build = build.id, version = build.version,
            branch = build.branch, language = resolvedLanguage, manifest = sanitize(manifest), files = files, products = products, verified = true)
    }

    fun languageSelected(list: List<String>, selected: String): Boolean = list.any { it.equals("Neutral", true) } || StoreLanguage.depotMatchesResolved(list.toTypedArray(), selected)

    fun cdnPath(hash: String): String {
        require(hash.matches(Regex("[a-fA-F0-9]{32,64}"))) { "Invalid GOG content hash" }
        return "${hash.take(2)}/${hash.substring(2, 4)}/$hash"
    }

    fun links(record: GogInstallRecord, token: String, patches: Boolean = false): JSONObject {
        val out = JSONObject()
        for (id in record.products) {
            val path = if (record.generation == 1) "/windows/${record.manifest.optJSONObject("product")?.optString("timestamp")}/" else "/"
            val query = "_version=2&" + (if (record.generation == 1) "type=depot" else "generation=2") + "&path=${java.net.URLEncoder.encode(path, "UTF-8")}" + if (patches) "&root=%2Fpatches%2Fstore" else ""
            val response = json("$SYSTEM/products/$id/secure_link?$query", token)
            val bases = response.optJSONArray("urls")?.objects().orEmpty().mapNotNull { item ->
                val params = item.optJSONObject("parameters")
                var url = item.optString("url_format")
                params?.keys()?.forEach { key -> url = url.replace("{$key}", params.getString(key)) }
                url.takeIf { it.startsWith("https://") }
            }
            check(bases.isNotEmpty()) { "GOG refused a download link" }
            out.put(id, JSONArray(bases))
        }
        return out
    }

    fun patches(old: GogInstallRecord, target: GogInstallRecord, token: String): JSONArray {
        if (old.generation != 2 || target.generation != 2 || old.build.isBlank() || old.build == target.build) return JSONArray()
        return runCatching {
            val index = json("$SYSTEM/products/${old.id}/patches?_version=4&from_build_id=${old.build}&to_build_id=${target.build}", token)
            val meta = json(index.getString("link"), token)
            if (meta.optString("algorithm") != "xdelta3") return@runCatching JSONArray()
            val result = JSONArray()
            for (depot in meta.getJSONArray("depots").objects()) {
                if (depot.optString("productId") !in target.products || !languageSelected(depot.optJSONArray("languages")?.strings().orEmpty(), target.language)) continue
                val data = json("$CDN/v2/patches/meta/${cdnPath(depot.getString("manifest"))}")
                for (item in data.getJSONObject("depot").getJSONArray("items").objects()) if (item.optString("type") == "DepotDiff")
                    result.put(JSONObject(item.toString()).put("product", depot.getString("productId")))
            }
            result
        }.getOrElse { JSONArray() }
    }

    fun setupFiles(record: GogInstallRecord, token: String): Pair<JSONArray, JSONObject>? {
        if (!record.manifest.optBoolean("scriptInterpreter")) return null
        if (record.files.objects().none { file -> record.products.any { file.optString("path").substringAfterLast('/') == "goggame-$it.script" } }) return null
        val index = json("$SYSTEM/dependencies/repository?generation=2")
        val repository = json(index.getString("repository_manifest"))
        val depot = repository.getJSONArray("depots").objects().firstOrNull { it.optString("dependencyId") == "ISI" }
            ?: error("GOG setup interpreter is unavailable")
        val content = json("$CDN/v2/dependencies/meta/${cdnPath(depot.getString("manifest"))}")
        val files = JSONArray()
        for (entry in content.getJSONObject("depot").getJSONArray("items").objects()) {
            if (entry.optString("type") != "DepotFile") continue
            val chunks = entry.getJSONArray("chunks")
            files.put(JSONObject().put("path", "support/" + entry.getString("path").replace('\\', '/'))
                .put("size", chunks.objects().sumOf { it.getLong("size") }).put("md5", entry.optString("md5"))
                .put("chunks", chunks).put("product", "redist"))
        }
        val response = json("$SYSTEM/open_link?generation=2&_version=2&path=%2Fdependencies%2Fstore%2F", token)
        val bases = GogDownloadManager.parseCdnUrls(response.toString())
        check(bases.isNotEmpty()) { "GOG setup download links are unavailable" }
        return files to JSONObject().put("redist", JSONArray(bases))
    }

    fun sanitize(value: JSONObject): JSONObject {
        val result = JSONObject()
        for (key in value.keys()) {
            if (key.lowercase() in setOf("clientsecret", "access_token", "refresh_token", "link", "urls", "url")) continue
            val v = value.get(key)
            result.put(key, when (v) { is JSONObject -> sanitize(v); is JSONArray -> JSONArray((0 until v.length()).map { i -> v.get(i).let { if (it is JSONObject) sanitize(it) else it } }); else -> v })
        }
        return result
    }
}
