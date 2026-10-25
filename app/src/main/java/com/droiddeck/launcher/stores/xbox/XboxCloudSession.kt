package com.droiddeck.launcher.stores.xbox

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Streaming-service tokens stay in memory only. Request shapes follow Greenlight / xal-node by unknownskl (MIT). */
internal object XboxCloudSession {
    class Region(val name: String, val baseUri: String, val isDefault: Boolean)

    class Session(val account: String, val token: String, val offering: String, val baseUri: String, val regionList: List<Region>,
                  val userToken: String?, internal val expiresAt: Long,
                  val osName: String = "windows") {
        val fresh get() = System.currentTimeMillis() < expiresAt - 5 * 60_000L
        val regions get() = regionList.size

        fun forStream(regionName: String, os: String): Session = Session(account, token, offering,
            regionList.firstOrNull { it.name == regionName }?.baseUri ?: baseUri, regionList, userToken, expiresAt, os)
    }

    @Volatile private var cached: Session? = null

    /** One fetch at a time (about 20 s: two ticket exchanges); a second caller waits and gets the same tokens. */
    @Synchronized
    fun get(app: Context, account: String): Session {
        cached?.takeIf { it.account == account && it.fresh }?.let { return it }
        val json = Xodus.run(app, listOf("cloud-token"), timeout = 60)
        require(json.getInt("schema") == 1 && json.getString("account") == account)
        val array = json.getJSONArray("regions")
        val regions = (0 until array.length()).map { array.getJSONObject(it) }
            .map { Region(it.getString("name"), it.getString("baseUri"), it.optBoolean("isDefault")) }
        val default = regions.firstOrNull { it.isDefault } ?: regions.firstOrNull() ?: error("No Xbox Cloud Gaming region")
        val seconds = json.optLong("durationInSeconds").coerceIn(0, 24 * 3600)
        return Session(account, json.getString("gsToken"), json.getString("offering"), default.baseUri, regions,
            json.optString("userToken").takeIf { !json.isNull("userToken") && it.isNotEmpty() }, System.currentTimeMillis() + seconds * 1000).also { cached = it }
    }

    fun clear() { cached = null; titleIds = null }

    /**
     * For the native player's own process: the main process's tokens (it keeps them, and fetches them ahead while a
     * cloud game's page is open), handed over in memory through XboxCloudTokenService; null if that is not reachable.
     */
    fun fromMainProcess(app: Context, account: String, timeoutSeconds: Long = 90): Session? {
        cached?.takeIf { it.account == account && it.fresh }?.let { return it }
        val reply = XboxCloudTokenService.request(app, account, timeoutSeconds) ?: return null
        reply.getString("error")?.let { error(it) }
        return fromBundle(reply).takeIf { it.account == account && it.fresh }?.also { cached = it }
    }

    internal fun toBundle(s: Session) = android.os.Bundle().apply {
        putString("account", s.account); putString("token", s.token); putString("offering", s.offering)
        putString("baseUri", s.baseUri); putString("userToken", s.userToken); putLong("expiresAt", s.expiresAt)
        putStringArrayList("regionNames", ArrayList(s.regionList.map { it.name }))
        putStringArrayList("regionUris", ArrayList(s.regionList.map { it.baseUri }))
        putBooleanArray("regionDefaults", s.regionList.map { it.isDefault }.toBooleanArray())
    }

    internal fun fromBundle(b: android.os.Bundle): Session {
        val names = b.getStringArrayList("regionNames").orEmpty()
        val uris = b.getStringArrayList("regionUris").orEmpty()
        val defaults = b.getBooleanArray("regionDefaults") ?: BooleanArray(0)
        val regions = names.indices.map { Region(names[it], uris.getOrElse(it) { "" }, defaults.getOrElse(it) { false }) }
        return Session(b.getString("account").orEmpty(), b.getString("token").orEmpty(), b.getString("offering").orEmpty(),
            b.getString("baseUri").orEmpty(), regions, b.getString("userToken"), b.getLong("expiresAt"))
    }

    @Volatile private var titleIds: Pair<String, Map<String, String>>? = null

    fun titles(session: Session): Map<String, String> {
        titleIds?.takeIf { it.first == session.account }?.let { return it.second }
        val reply = request(session, "GET", "/v2/titles")
        check(reply.status == 200) { "Xbox Cloud Gaming answered ${reply.status}" }
        val results = JSONObject(reply.body).optJSONArray("results") ?: JSONArray()
        val map = (0 until results.length()).mapNotNull { index ->
            val row = results.optJSONObject(index) ?: return@mapNotNull null
            val product = row.optJSONObject("details")?.optString("productId")?.uppercase().orEmpty()
            val title = row.optString("titleId")
            if (product.matches(Regex("[A-Z0-9]{12}")) && title.isNotBlank() && title.length <= 64) product to title else null
        }.toMap()
        titleIds = session.account to map
        return map
    }

    class Reply(val status: Int, val body: String)

    fun request(session: Session, method: String, path: String, body: String? = null): Reply {
        val connection = URL(session.baseUri + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000; connection.readTimeout = 30_000
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer ${session.token}")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Gssv-Client", "XboxComBrowser")
            connection.setRequestProperty("X-MS-Device-Info", deviceInfo(session.osName))
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes() }?.also { require(it.size <= 16_000_000) } ?: ByteArray(0)
            return Reply(status, String(text, Charsets.UTF_8))
        } finally { connection.disconnect() }
    }

    private fun deviceInfo(os: String) = JSONObject()
        .put("appInfo", JSONObject().put("env", JSONObject().put("clientAppId", "www.xbox.com").put("clientAppType", "browser")
            .put("clientAppVersion", "21.1.98").put("clientSdkVersion", "8.5.3").put("httpEnvironment", "prod").put("sdkInstallId", "")))
        .put("dev", JSONObject()
            .put("hw", JSONObject().put("make", "Microsoft").put("model", "unknown").put("sdktype", "web"))
            .put("os", JSONObject().put("name", os).put("ver", "22631.2715").put("platform", "desktop"))
            .put("displayInfo", JSONObject().put("dimensions", JSONObject().put("widthInPixels", 1920).put("heightInPixels", 1080))
                .put("pixelDensity", JSONObject().put("dpiX", 2).put("dpiY", 2)))
            .put("browser", JSONObject().put("browserName", "chrome").put("browserVersion", "119.0")))
        .toString()
}
