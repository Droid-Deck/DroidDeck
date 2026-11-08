package com.droiddeck.launcher.stores.ea

import android.content.Context
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.StringReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The local SDK endpoint EA games talk to in place of the EA app (LSX: XML over TCP, AES after a
 * challenge). It answers for the one game DroidDeck last prepared.
 */
object EaLsx {
    private const val TAG = "EaLsx"
    const val PORT = 3216
    private const val SENDER = "EALS"
    private const val CHALLENGE_KEY = "cacf897a20b6d612ad0c05e011df52bb"
    private const val CHALLENGE_VERSION = "10,5,30,15625"
    private const val LANGUAGES = "ar_SA,de_DE,en_US,es_ES,es_MX,fr_FR,it_IT,ja_JP,ko_KR,pl_PL,pt_BR,ru_RU,zh_CN,zh_TW"

    private val SERVICES = listOf(
        "EbisuSDK" to "SDK", "EbisuSDK" to "PROFILE", "XMPP" to "PRESENCE", "XMPP" to "FRIENDS", "Commerce" to "COMMERCE",
        "EbisuSDK" to "RECENTPLAYER", "EbisuSDK" to "IGO", "EbisuSDK" to "MISC", "EALS" to "LOGIN", "EbisuSDK" to "UTILITY",
        "XMPP" to "XMPP", "XMPP" to "CHAT", "EbisuSDK" to "IGO_EVENT", "EALS" to "EALS_EVENTS", "EbisuSDK" to "LOGIN_EVENT",
        "XMPP" to "INVITE_EVENT", "EbisuSDK" to "PROFILE_EVENT", "XMPP" to "PRESENCE_EVENT", "XMPP" to "FRIENDS_EVENT",
        "Commerce" to "COMMERCE_EVENT", "XMPP" to "CHAT_EVENT", "EbisuSDK" to "DOWNLOAD_EVENT", "EbisuSDK" to "PERMISSION",
        "EbisuSDK" to "RESOURCES", "EbisuSDK" to "BLOCKED_USERS", "EbisuSDK" to "BLOCKED_USER_EVENT", "EbisuSDK" to "GET_USERID",
        "EbisuSDK" to "ONLINE_STATUS_EVENT", "EbisuSDK" to "ACHIEVEMENT", "EbisuSDK" to "ACHIEVEMENT_EVENT",
        "EbisuSDK" to "BROADCAST_EVENT", "PI" to "PROGRESSIVE_INSTALLATION", "PI" to "PROGRESSIVE_INSTALLATION_EVENT",
        "EbisuSDK" to "CONTENT",
    )

    class Game(
        val offerId: String,
        val contentId: String,
        val title: String,
        val version: String,
        val locale: String,
        val hardware: EaOoa.Hardware?,
        val hashVersion: Int,
        val token: String = "",
        val tokenExpiresAt: Long = 0L,
        val contentIds: Set<String> = emptySet(),
    ) {
        val validToken: String? get() = token.takeIf { it.isNotEmpty() && System.currentTimeMillis() < tokenExpiresAt }
    }

    /** The parts of a request a reply depends on. */
    class Message(val recipient: String, val id: String, val name: String, val attrs: Map<String, String>, val children: List<Pair<String, String>>)

    interface Account {
        val userId: String
        val personaId: String
        val persona: String
        fun authCode(clientId: String, scope: String?): String?
        fun license(contentId: String, requestTicket: String, ticketEngine: String): String?
        fun entitlements(group: String): List<Map<String, String>>
        fun token(): String?
    }

    @Volatile var game: Game? = null
    @Volatile private var account: Account? = null
    /** The one session of the current launch that may act for the account. */
    /** Bumped whenever the launch changes; a connection accepted under an older one is not admitted. */
    @Volatile private var generation = 0
    private val holder = java.util.concurrent.atomic.AtomicReference<Any?>(null)
    private var server: ServerSocket? = null
    private val lock = Any()
    private const val MAX_CLIENTS = 8
    private val WHITESPACE = Regex("[ \t]+")

    fun start(context: Context, game: Game): Boolean {
        synchronized(lock) { closeSessions() }
        this.game = game
        account = AppAccount(context.applicationContext)
        synchronized(lock) {
            if (server?.isClosed == false) return true
            return try {
                val socket = ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1")).apply { reuseAddress = true }
                server = socket
                Thread({ accept(socket) }, "ea-lsx").apply { isDaemon = true }.start()
                Log.i(TAG, "listening on $PORT")
                true
            } catch (e: Exception) {
                Log.w(TAG, "could not listen on $PORT: ${e.javaClass.simpleName}")
                false
            }
        }
    }

    /** Ends the endpoint; with [offerId], only if it still serves that game, so a late exit cannot stop a newer launch. */
    fun stop(offerId: String? = null) {
        synchronized(lock) {
            if (offerId != null && game?.offerId != offerId) return
            runCatching { server?.close() }
            server = null
            game = null
            account = null
            closeSessions()
        }
    }

    private fun closeSessions() {
        generation++
        holder.set(null)
        synchronized(sessions) { sessions.forEach { it.trusted = false } }
        for (socket in open) runCatching { socket.close() }
        open.clear()
    }

    private val open = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())
    private const val HANDSHAKE_TIMEOUT_MS = 15_000
    private const val UNTRUSTED_IDLE_MS = 10 * 60 * 1000

    private fun accept(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: continue
            val verified = ownerUid(client)?.let { it == android.os.Process.myUid() }
            if (verified == false) {
                Log.w(TAG, "refused a connection from another app")
                runCatching { client.close() }
                continue
            }
            val born = generation
            val session = runCatching { Session(client, client.getOutputStream(), verified == true) }.getOrNull()
            val live = session != null && synchronized(lock) { server === socket && born == generation && admit(session).also { if (it) open += client } }
            if (!live) {
                runCatching { client.close() }
                continue
            }
            Thread({ try { serve(client, session!!) } finally { synchronized(sessions) { sessions -= session!! }; open -= client } }, "ea-lsx-client").apply { isDaemon = true }.start()
        }
    }

    /**
     * The uid owning the connecting socket, or null where SELinux hides /proc/net. The guest runs
     * under this app's uid, so any other uid is another app; an unknown owner never gets credentials.
     */
    private fun ownerUid(client: Socket): Int? {
        val port = client.port
        return listOf("/proc/net/tcp6", "/proc/net/tcp").firstNotNullOfOrNull { path ->
            runCatching { java.io.File(path).readLines() }.getOrNull()?.let { owner(it, port) }
        }
    }

    internal fun owner(lines: List<String>, port: Int): Int? = lines.drop(1).firstNotNullOfOrNull { line ->
        val f = line.trim().split(WHITESPACE)
        val local = f.getOrNull(1)?.substringAfterLast(':')?.toIntOrNull(16)
        val remote = f.getOrNull(2)?.substringAfterLast(':')?.toIntOrNull(16)
        if (local == port && remote == PORT) f.getOrNull(7)?.toIntOrNull() else null
    }

    /**
     * Adds [session] unless the cap is reached by sessions that cannot be replaced; otherwise the
     * oldest session that never claimed the prepared game (then the oldest untrusted one) makes room.
     * Counting and eviction share one lock, so the live total never passes the cap.
     */
    internal fun admit(session: Session): Boolean = synchronized(sessions) {
        if (sessions.count { !it.evicted } >= MAX_CLIENTS) {
            val candidates = sessions.filter { !it.trusted && !it.evicted }
            val victim = candidates.firstOrNull { !it.claimed } ?: candidates.firstOrNull() ?: return false
            victim.evicted = true
            runCatching { victim.socket.close() }
        }
        sessions += session
        true
    }

    internal val sessions = LinkedHashSet<Session>()

    /** Trust and eviction exclude each other: an evicted session never becomes trusted, a trusted one is never evicted. */
    internal fun promote(session: Session, matches: Boolean) {
        synchronized(sessions) { session.trusted = matches && !session.evicted && holder.compareAndSet(null, session) }
    }

    internal class Session(val socket: Socket, val output: OutputStream, val verified: Boolean) {
        val connectedAt = System.currentTimeMillis()
        @Volatile var evicted = false
        @Volatile var claimed = false
        var key: ByteArray? = null
        @Volatile var trusted = false
        var pending: ByteArray? = null

        @Synchronized
        fun send(xml: String) {
            val key = this.key
            val text = if (key == null) xml else EaCrypto.hex(EaCrypto.ecbEncrypt(xml.toByteArray(), key))
            output.write(text.toByteArray())
            output.write(0)
            output.flush()
        }
    }

    private fun note(line: String) = com.droiddeck.launcher.stores.StoresState.logLine("ea lsx: $line")

    private fun serve(client: Socket, session: Session) {
        client.tcpNoDelay = true
        client.soTimeout = HANDSHAKE_TIMEOUT_MS
        val seen = LinkedHashSet<String>()
        var why = "closed by the game"
        note("game connected")
        try {
            session.send("<LSX><Event sender=\"$SENDER\"><Challenge build=\"release\" key=\"$CHALLENGE_KEY\" version=\"$CHALLENGE_VERSION\"/></Event></LSX>")
            val input = client.getInputStream()
            val pending = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            while (true) {
                if (session.key == null) {
                    val left = session.connectedAt + HANDSHAKE_TIMEOUT_MS - System.currentTimeMillis()
                    if (left <= 0) throw java.net.SocketTimeoutException()
                    client.soTimeout = left.toInt()
                }
                val n = input.read(chunk)
                if (n < 0) break
                pending.write(chunk, 0, n)
                for (frame in frames(pending, session.key)) {
                    for (one in Regex("<LSX>.*?</LSX>", RegexOption.DOT_MATCHES_ALL).findAll(frame)) {
                        val message = parse(one.value) ?: continue
                        if (seen.add(message.name)) note("request ${message.name}")
                        val reply = runCatching { answer(session, message) }
                            .onFailure { note("${message.name} failed: ${it.javaClass.simpleName}") }.getOrNull()
                        if (reply != null) session.send("<LSX><Response sender=\"${esc(message.recipient)}\" id=\"${esc(message.id)}\">$reply</Response></LSX>")
                        session.pending?.let { session.key = it; session.pending = null }
                        if (session.key != null) {
                            val idle = if (session.trusted) 0 else UNTRUSTED_IDLE_MS
                            if (client.soTimeout != idle) client.soTimeout = idle
                        }
                    }
                }
                if (pending.size() > 4 * 1024 * 1024) { why = "message too large"; break }
            }
        } catch (e: java.net.SocketTimeoutException) {
            why = "no trusted handshake in time"
        } catch (e: Exception) {
            why = e.javaClass.simpleName
        } finally {
            holder.compareAndSet(session, null)
            note("game disconnected ($why)")
            runCatching { client.close() }
        }
    }

    /**
     * Complete messages in what has arrived so far. Messages normally end in a NUL; one that does
     * not counts once it holds a whole `<LSX>` element, plain or decrypted.
     */
    private fun frames(pending: ByteArrayOutputStream, key: ByteArray?): List<String> {
        val bytes = pending.toByteArray()
        val out = ArrayList<String>()
        var start = 0
        for (i in bytes.indices) {
            if (bytes[i].toInt() != 0) continue
            if (i > start) decode(String(bytes, start, i - start, Charsets.UTF_8), key)?.let(out::add)
            start = i + 1
        }
        var rest = String(bytes, start, bytes.size - start, Charsets.UTF_8)
        if (rest.isNotBlank()) decode(rest, key)?.takeIf { it.contains("</LSX>") }?.let { out += it; rest = "" }
        pending.reset()
        pending.write(rest.toByteArray(Charsets.UTF_8))
        return out
    }

    private fun decode(frame: String, key: ByteArray?): String? {
        val text = frame.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("<")) return text
        val k = key ?: return null
        return runCatching { String(EaCrypto.ecbDecrypt(EaCrypto.unhex(text), k)) }.getOrNull()
    }

    internal fun parse(xml: String): Message? = runCatching {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        var recipient = ""
        var id = ""
        var name = ""
        val attrs = HashMap<String, String>()
        val children = ArrayList<Pair<String, String>>()
        var depth = 0
        var child = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (depth) {
                        2 -> if (parser.name == "Request") {
                            recipient = parser.getAttributeValue(null, "recipient").orEmpty()
                            id = parser.getAttributeValue(null, "id").orEmpty()
                        } else return@runCatching null
                        3 -> {
                            name = parser.name
                            for (i in 0 until parser.attributeCount) attrs[parser.getAttributeName(i)] = parser.getAttributeValue(i)
                        }
                        4 -> child = parser.name
                    }
                }
                XmlPullParser.TEXT -> if (depth == 4) children += child to parser.text.trim()
                XmlPullParser.END_TAG -> depth--
            }
            parser.next()
        }
        if (name.isEmpty()) null else Message(recipient, id, name, attrs, children)
    }.getOrNull()

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun tag(name: String, vararg attrs: Pair<String, Any?>, body: String? = null): String {
        val a = attrs.filter { it.second != null }.joinToString("") { " ${it.first}=\"${esc(it.second.toString())}\"" }
        return if (body == null) "<$name$a/>" else "<$name$a>$body</$name>"
    }

    private fun now() = LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

    private fun answer(session: Session, m: Message): String? {
        val g = game
        val who = account
        val person = who?.takeIf { session.trusted }
        val locale = g?.locale ?: "en_US"
        return when (m.name) {
            "ChallengeResponse" -> {
                val answer = EaCrypto.challengeAnswer(m.attrs["key"].orEmpty())
                val version = m.attrs["version"].orEmpty()
                val seed = EaCrypto.challengeSeed(version, answer) ?: 0
                val content = m.children.firstOrNull { it.first == "ContentId" }?.second.orEmpty()
                session.claimed = g != null && content.isNotEmpty() && (content == g.contentId || content in g.contentIds)
                val matches = session.verified && session.claimed
                promote(session, matches)
                note("challenge version '$version' content '$content' ${when { session.trusted -> "matches the prepared game"; matches -> "matches, but another session holds this launch"; !session.verified -> "comes from an unverified peer"; else -> "does not match the prepared game" }}")
                session.pending = EaCrypto.lsxKey(seed)
                Log.i(TAG, "game connected: ${m.children.firstOrNull { it.first == "Title" }?.second.orEmpty()}")
                tag("ChallengeAccepted", "response" to answer)
            }
            "GetConfig" -> tag("GetConfigResponse", body = SERVICES.joinToString("") { tag("Service", "Name" to it.first, "Facility" to it.second) })
            "GetProfile" -> tag(
                "GetProfileResponse",
                "Persona" to person?.persona.orEmpty(), "SubscriberLevel" to 0, "CommerceCurrency" to "USD", "IsTrialSubscriber" to false,
                "Country" to "US", "UserId" to person?.userId.orEmpty(), "GeoCountry" to "US", "AvatarId" to "", "IsSubscriber" to false,
                "IsSteamSubscriber" to false, "PersonaId" to person?.personaId.orEmpty(), "IsUnderAge" to false, "UserIndex" to 0,
            )
            "GetSetting" -> tag("GetSettingResponse", "Setting" to when (m.attrs["SettingId"]) {
                "ENVIRONMENT" -> "production"
                "LANGUAGE" -> locale
                else -> "false"
            })
            "GetGameInfo" -> tag("GetGameInfoResponse", "GameInfo" to when (m.attrs["GameInfoId"]) {
                "FREETRIAL" -> "false"
                "LANGUAGES" -> LANGUAGES
                "INSTALLED_LANGUAGE" -> locale
                "INSTALLED_VERSION", "AVAILABLE_VERSION" -> g?.version.orEmpty()
                else -> ""
            })
            "GetAllGameInfo" -> tag(
                "GetAllGameInfoResponse",
                "FullGamePurchased" to true, "FullGameReleased" to true, "InstalledVersion" to (g?.version ?: "0"), "MaxGroupSize" to 16,
                "Languages" to LANGUAGES, "Expiration" to "0000-00-00T00:00:00", "UpToDate" to true, "HasExpiration" to false,
                "EntitlementSource" to "EA", "AvailableVersion" to (g?.version ?: "0"), "DisplayName" to g?.title.orEmpty(),
                "FreeTrial" to false, "InstalledLanguage" to locale, "FullGameReleaseDate" to "2000-01-01T00:00:00", "SystemTime" to now(),
            )
            "GetInternetConnectedState" -> tag("InternetConnectedState", "connected" to 1)
            "IsProgressiveInstallationAvailable" -> tag("IsProgressiveInstallationAvailableResponse", "ItemId" to m.attrs["ItemId"].orEmpty(), "Available" to false)
            "AreChunksInstalled" -> tag(
                "AreChunksInstalledResponse", "ItemId" to m.attrs["ItemId"].orEmpty(), "Installed" to true,
                body = m.children.joinToString("") { tag(it.first, body = esc(it.second)) },
            )
            "GetAuthCode" -> tag("AuthCode", "value" to (who?.takeIf { session.trusted }?.authCode(m.attrs["ClientId"].orEmpty(), m.attrs["Scope"])?.takeIf { session.trusted } ?: "invalid"))
            "GetAuthToken" -> tag("AuthToken", "value" to if (!session.trusted) "" else (g?.validToken ?: who?.token().orEmpty()))
            "RequestLicense" -> {
                val token = who?.takeIf { session.trusted }?.license(g?.contentId.orEmpty(), m.attrs["RequestTicket"].orEmpty(), m.attrs["TicketEngine"].orEmpty())?.takeIf { session.trusted }.orEmpty()
                tag("RequestLicenseResponse", "License" to token)
            }
            "QueryEntitlements" -> {
                val group = m.attrs["Group"].orEmpty()
                val rows = who?.takeIf { session.trusted }?.entitlements(group).orEmpty()
                tag("QueryEntitlementsResponse", body = rows.joinToString("") { e ->
                    tag(
                        "Entitlement",
                        "LastModifiedDate" to "0000-00-00T00:00:00", "EntitlementId" to e["id"], "UseCount" to (e["useCount"] ?: "0"),
                        "Version" to (e["version"] ?: "0"), "ItemId" to e["productId"], "ResourceId" to "", "GrantDate" to e["grantDate"],
                        "Group" to group, "EntitlementTag" to e["entitlementTag"], "Type" to e["entitlementType"],
                        "Expiration" to (e["terminationDate"]?.takeIf { it.isNotEmpty() && it != "null" } ?: "0000-00-00T00:00:00"), "Source" to "",
                    )
                })
            }
            "GetPresence" -> tag("GetPresenceResponse", "UserId" to person?.userId.orEmpty(), "Presence" to "INGAME")
            "QueryPresence" -> tag("QueryPresenceResponse", body = "")
            "QueryFriends" -> tag("QueryFriendsResponse", body = "")
            "GetBlockList" -> tag("GetBlockListResponse", "Return" to "Success", body = "")
            "QueryOffers" -> tag("QueryOffersResponse", body = "")
            "QueryImage" -> tag("QueryImageResponse", "Result" to 0, body = "")
            "GetVoipStatus" -> tag("GetVoipStatusResponse", "Available" to false, "Active" to false)
            "ShowIGOWindow", "SetDownloaderUtilization" -> null
            else -> tag("ErrorSuccess", "Code" to 0, "Description" to "")
        }
    }

    private class AppAccount(private val app: Context) : Account {
        private val saved get() = EaAuth.load(app)
        override val userId: String get() = saved?.userId.orEmpty()
        override val personaId: String get() = saved?.personaId.orEmpty()
        override val persona: String get() = saved?.eaId?.ifEmpty { null } ?: saved?.displayName.orEmpty()

        override fun authCode(clientId: String, scope: String?): String? =
            EaAuth.mint(app, clientId, "code", scopes = scope?.split(' ')?.filter { it.isNotBlank() }.orEmpty())

        override fun license(contentId: String, requestTicket: String, ticketEngine: String): String? {
            val g = game ?: return null
            val hw = g.hardware ?: return null
            val token = EaAuth.accessToken(app) ?: return null
            return runCatching {
                EaOoa.request(token, contentId, EaOoa.machineHash(hw, g.hashVersion), requestTicket.ifEmpty { null }, ticketEngine.ifEmpty { null }).gameToken
            }.onFailure { Log.w(TAG, "licence: ${it.message}") }.getOrNull()
        }

        override fun token(): String? = EaAuth.mint(app, EaAuth.CLIENT_ID, "token", EaAuth.OPAQUE_SCOPES, "OPAQUE", 14400)

        override fun entitlements(group: String): List<Map<String, String>> {
            val token = EaAuth.accessToken(app) ?: return emptyList()
            val arr = runCatching { EaApi.entitlements(token, group) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
                o.keys().asSequence().associateWith { k -> o.optString(k) }
            }
        }
    }
}
