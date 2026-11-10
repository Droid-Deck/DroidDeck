package com.droiddeck.launcher.stores.ubisoft

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** UbiServices sessions: a ticket from Ubisoft's own web sign-in, exchanged for a launcher session that remember-me renews. */
object UbisoftAuth {
    private const val TAG = "UbisoftAuth"
    const val SESSIONS = "https://public-ubiservices.ubi.com/v3/profiles/sessions"

    /** The web app and genome connect.ubisoft.com's sign-in page accepts. */
    private const val WEB_APP_ID = "f35adcb5-1911-440c-b1c9-48fdc1701c68"
    private const val WEB_GENOME_ID = "5b36b900-65d8-47f3-93c8-86bdaa48ab50"

    /** The Ubisoft Connect PC app id; its sessions are accepted by the library GraphQL and Demux. */
    const val APP_ID = "f68a4bb5-608a-4ff2-8123-be8ef797e0a6"
    private const val REFRESH_MARGIN_MS = 10 * 60 * 1000L
    private val lock = Any()

    class Session(
        val ticket: String,
        val sessionId: String,
        val profileId: String,
        val userId: String,
        val name: String,
        val expiresAt: Long,
        val rememberMe: String,
    )

    class Reply(val status: Int, val body: String, val headers: Map<String, String>) {
        val ok get() = status in 200..299
    }

    private val loggedIn = "https://connect.ubisoft.com/logged-in.html?appId=$WEB_APP_ID&lang=en-US&genomeId=$WEB_GENOME_ID"
    val loginUrl = "https://connect.ubisoft.com/login?appId=$WEB_APP_ID&genomeId=$WEB_GENOME_ID&lang=en-US&nextUrl=" + URLEncoder.encode(loggedIn, "UTF-8")
    val SIGN_IN_URLS = listOf("https://connect.ubisoft.com", "https://account.ubisoft.com", "https://ubisoft.com")

    fun isLoggedInPage(url: String) = url.startsWith("https://connect.ubisoft.com/logged-in.html")

    /** Reads connect.ubisoft.com's stored sign-in; the WebAuth SDK keeps it under keys holding `loginData` and `rememberMe`. */
    const val PAGE_SCRIPT = "(function(){try{var o={};for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);" +
        "if(/loginData|rememberMe|lastProfile/i.test(k))o[k]=localStorage.getItem(k);}return JSON.stringify(o);}catch(e){return '';}})()"

    internal fun request(url: String, method: String = "GET", authorization: String? = null, sessionId: String? = null, body: String? = null, extra: Map<String, String> = emptyMap()): Reply {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = 25_000
        c.setRequestProperty("Ubi-AppId", APP_ID)
        c.setRequestProperty("Ubi-RequestedPlatformType", "uplay")
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
        authorization?.let { c.setRequestProperty("Authorization", it) }
        sessionId?.let { c.setRequestProperty("Ubi-SessionId", it) }
        for ((k, v) in extra) c.setRequestProperty(k, v)
        try {
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = c.responseCode
            val text = (if (status in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            val headers = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.joinToString(",") }
            return Reply(status, text, headers)
        } finally {
            c.disconnect()
        }
    }

    /** POSTs a session request, replaying it once when UbiServices answers with a challenge. */
    private fun session(authorization: String): Reply {
        val body = JSONObject().put("rememberMe", true).toString()
        val first = request(SESSIONS, "POST", authorization, body = body)
        val challenge = first.headers["ubi-challenge"] ?: return first
        return request(SESSIONS, "POST", authorization, body = body, extra = mapOf("Ubi-Challenge" to challenge))
    }

    internal fun parseSession(body: String, rememberFallback: String = ""): Session? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val ticket = o.optString("ticket").takeIf { it.isNotEmpty() && it != "null" } ?: return null
        return Session(
            ticket = ticket,
            sessionId = o.optString("sessionId"),
            profileId = o.optString("profileId"),
            userId = o.optString("userId"),
            name = o.optString("nameOnPlatform"),
            expiresAt = parseTime(o.optString("expiration")),
            rememberMe = o.optString("rememberMeTicket").takeIf { it.isNotEmpty() && it != "null" } ?: rememberFallback,
        )
    }

    internal fun parseTime(s: String): Long = runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        .recoverCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrDefault(System.currentTimeMillis() + 60 * 60 * 1000L)

    /** Values the web sign-in stored, found wherever the SDK nests them. */
    internal fun webValues(dump: String): Map<String, String> {
        val out = HashMap<String, String>()
        fun walk(v: Any?) {
            when (v) {
                is JSONObject -> v.keys().forEach { k ->
                    val x = v.opt(k)
                    if (x is String && x.isNotEmpty() && k !in out) {
                        val nested = if (x.startsWith("{") || x.startsWith("[")) runCatching { org.json.JSONTokener(x).nextValue() }.getOrNull() else null
                        if (nested != null) walk(nested) else out[k] = x
                    } else walk(x)
                }
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
            }
        }
        val top = runCatching { org.json.JSONTokener(dump).nextValue() }.getOrNull()
        walk(if (top is String) runCatching { JSONObject(top) }.getOrNull() else top)
        return out
    }

    /** Turns the page's stored sign-in into a launcher session; returns null on success or a reason. */
    fun completeWebSignIn(context: Context, dump: String): String? {
        val web = webValues(dump)
        val ticket = web["ticket"]
        val remember = web["rememberMeTicket"].orEmpty()
        val fresh = when {
            ticket != null -> session("ubi_v1 t=$ticket").let { r ->
                parseSession(r.body, remember) ?: run { Log.w(TAG, "ticket exchange: ${r.status} ${r.body.take(200)}"); null }
            }
            else -> null
        } ?: remember.takeIf { it.isNotEmpty() }?.let { rm -> parseSession(session("rm_v1 t=$rm").body, rm) }
            ?: return "no ticket"
        save(context, fresh)
        com.droiddeck.launcher.core.AddonPack.UBISOFT.ensure(context)
        return null
    }

    private fun save(context: Context, s: Session) {
        val name = s.name.ifEmpty { profileName(s) }
        StoreAccounts.write(context.applicationContext, Store.UBISOFT, JSONObject()
            .put("access_token", s.ticket)
            .put("session_id", s.sessionId)
            .put("profile_id", s.profileId)
            .put("user_id", s.userId)
            .put("display_name", name)
            .put("expires_at", s.expiresAt)
            .put("remember_me", s.rememberMe))
    }

    private fun profileName(s: Session): String = runCatching {
        JSONObject(request("https://public-ubiservices.ubi.com/v3/profiles/me", authorization = "ubi_v1 t=${s.ticket}", sessionId = s.sessionId).body).optString("nameOnPlatform")
    }.getOrDefault("")

    fun load(context: Context): Session? {
        val o = StoreAccounts.read(context.applicationContext, Store.UBISOFT) ?: return null
        val ticket = o.optString("access_token").takeIf { it.isNotEmpty() } ?: return null
        return Session(ticket, o.optString("session_id"), o.optString("profile_id"), o.optString("user_id"), o.optString("display_name"), o.optLong("expires_at"), o.optString("remember_me"))
    }

    /** A live session, renewed through remember-me shortly before it expires. */
    fun current(context: Context): Session? = synchronized(lock) {
        val s = load(context) ?: return null
        if (s.expiresAt - System.currentTimeMillis() > REFRESH_MARGIN_MS || s.rememberMe.isEmpty()) return s
        val renewed = runCatching { session("rm_v1 t=${s.rememberMe}") }.getOrNull()?.let { parseSession(it.body, s.rememberMe) }
        if (renewed == null) {
            Log.w(TAG, "remember-me renewal failed")
            return if (s.expiresAt > System.currentTimeMillis()) s else null
        }
        save(context, Session(renewed.ticket, renewed.sessionId, renewed.profileId.ifEmpty { s.profileId }, renewed.userId.ifEmpty { s.userId },
            renewed.name.ifEmpty { s.name }, renewed.expiresAt, renewed.rememberMe))
        load(context)
    }

    fun signOut(context: Context) = synchronized(lock) { StoreAccounts.clear(context.applicationContext, Store.UBISOFT) }
}
