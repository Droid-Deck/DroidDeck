package com.droiddeck.launcher.frontend

import android.util.Log
import androidx.annotation.VisibleForTesting
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Steam's own app info (PICS) for apps the client in the runtime has never seen, asked of Steam
 * anonymously: a connection to one of Steam's connection managers (the list from Steam's public
 * ISteamDirectory/GetCMListForConnect, the WebSocket transport Steam's web clients use), an
 * anonymous logon - no account, nothing of the user's Steam sign-in - a request for the apps'
 * access tokens and then their product info, the app KeyValues read for their Steamworks Shared
 * depots and name, and the connection closed. What steamcmd's anonymous `app_info_print` sees.
 */
object SteamPics {
    private const val TAG = "SteamPics"
    private const val CM_LIST = "https://api.steampowered.com/ISteamDirectory/GetCMListForConnect/v1/?cellid=0&format=json"
    /** At most this many apps per request. */
    const val BATCH = 25
    private const val PROTO = 0x80000000.toInt()
    private const val EMSG_MULTI = 1
    private const val EMSG_LOGON = 5514
    private const val EMSG_LOGON_RESPONSE = 751
    private const val EMSG_LOGOFF = 706
    private const val EMSG_TOKENS_REQUEST = 8905
    private const val EMSG_TOKENS_RESPONSE = 8906
    private const val EMSG_INFO_REQUEST = 8903
    private const val EMSG_INFO_RESPONSE = 8904
    private const val PROTOCOL_VERSION = 65580
    /** An anonymous user's SteamID: universe Public, account type AnonUser, instance 0. */
    private const val ANON_STEAMID = (1L shl 56) or (10L shl 52)

    /**
     * [appIds]' info, one connection for all of them in batches of [BATCH]: each known app's
     * info, null for an app Steam does not know. Null when Steam could not be reached. Blocking.
     */
    fun lookup(appIds: List<Int>): Map<Int, SteamAppInfo.Info?>? {
        if (appIds.isEmpty()) return emptyMap()
        val host = runCatching { cmHost() }.getOrNull() ?: return null
        return try {
            Wss(host, 20_000).use { ws ->
                ws.send(message(EMSG_LOGON, header(ANON_STEAMID, 0), Proto().varint(1, PROTOCOL_VERSION.toLong()).varint(7, 16).string(6, "english").bytes()))
                var steamId = 0L
                var session = 0
                // The logon's answer (other messages before it are passed over).
                loop@ while (true) for ((emsg, hdr, body) in messages(ws.receive() ?: return null)) if (emsg == EMSG_LOGON_RESPONSE) {
                    val h = fields(hdr); val b = fields(body)
                    if ((b[1]?.firstOrNull() as? Long ?: 0L) != 1L) { Log.w(TAG, "anonymous logon refused: ${b[1]}"); return null }
                    steamId = h[1]?.firstOrNull() as? Long ?: 0L
                    session = (h[2]?.firstOrNull() as? Long ?: 0L).toInt()
                    break@loop
                }
                val out = HashMap<Int, SteamAppInfo.Info?>()
                for (batch in batches(appIds)) {
                    val tokens = HashMap<Int, Long>()
                    ws.send(message(EMSG_TOKENS_REQUEST, header(steamId, session), Proto().apply { batch.forEach { varint(2, it.toLong()) } }.bytes()))
                    tokens@ while (true) for ((emsg, _, body) in messages(ws.receive() ?: return null)) if (emsg == EMSG_TOKENS_RESPONSE) {
                        for (t in fields(body)[3].orEmpty()) {
                            val f = fields(t as ByteArray)
                            val id = (f[1]?.firstOrNull() as? Long)?.toInt() ?: continue
                            tokens[id] = f[2]?.firstOrNull() as? Long ?: 0L
                        }
                        break@tokens
                    }
                    ws.send(message(EMSG_INFO_REQUEST, header(steamId, session), Proto().apply {
                        batch.forEach { id -> bytes(2, Proto().varint(1, id.toLong()).apply { tokens[id]?.takeIf { it != 0L }?.let { varint(2, it) } }.bytes()) }
                        varint(3, 0)
                    }.bytes()))
                    var pending = true
                    while (pending) for ((emsg, _, body) in messages(ws.receive() ?: return null)) if (emsg == EMSG_INFO_RESPONSE) {
                        val r = parseInfoResponse(body)
                        out.putAll(r.apps)
                        r.unknown.forEach { out[it] = null }
                        pending = r.pending
                    }
                    batch.forEach { if (it !in out) out[it] = null }
                }
                runCatching { ws.send(message(EMSG_LOGOFF, header(steamId, session), ByteArray(0))) }
                Log.i(TAG, "steam pics: ${out.size} apps, ${out.values.count { it?.sharedDepots?.isNotEmpty() == true }} with shared redists")
                out
            }
        } catch (e: Exception) {
            Log.w(TAG, "steam pics: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** [appIds] in requests of at most [BATCH], each once. */
    fun batches(appIds: List<Int>): List<List<Int>> = appIds.distinct().chunked(BATCH)

    /** A connection manager for the WebSocket transport, least loaded first as Steam lists them. */
    private fun cmHost(): String? {
        val c = URL(CM_LIST).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        val body = try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
        val list = JSONObject(body).optJSONObject("response")?.optJSONArray("serverlist") ?: return null
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .firstOrNull { it.optString("type") == "websockets" }?.optString("endpoint")?.substringBefore(':')
    }

    // ---- the product info answer ----

    class InfoResponse(val apps: Map<Int, SteamAppInfo.Info>, val unknown: List<Int>, val pending: Boolean)

    /** A CMsgClientPICSProductInfoResponse: each app's KeyValues read, the unknown ones, whether more follows. */
    @VisibleForTesting
    internal fun parseInfoResponse(body: ByteArray): InfoResponse {
        val f = fields(body)
        val apps = HashMap<Int, SteamAppInfo.Info>()
        for (a in f[1].orEmpty()) {
            val app = fields(a as ByteArray)
            val id = (app[1]?.firstOrNull() as? Long)?.toInt() ?: continue
            val buffer = app[5]?.firstOrNull() as? ByteArray ?: continue
            val text = String(buffer, Charsets.UTF_8).trimEnd('\u0000')
            infoFromKeyValues(text)?.let { apps[id] = it }
        }
        val unknown = f[2].orEmpty().mapNotNull { (it as? Long)?.toInt() } + f[2].orEmpty().filterIsInstance<ByteArray>().flatMap { packed(it) }
        return InfoResponse(apps, unknown, (f[6]?.firstOrNull() as? Long ?: 0L) != 0L)
    }

    /** An app's KeyValues text ("appinfo" { "common" {...} "depots" {...} }) to its name and shared depots. */
    @VisibleForTesting
    internal fun infoFromKeyValues(text: String): SteamAppInfo.Info? {
        val tree = runCatching { KeyValues(text).read() }.getOrNull() ?: return null
        @Suppress("UNCHECKED_CAST")
        val app = tree["appinfo"] as? Map<String, Any> ?: return null
        return SteamAppInfo.infoOf(app)
    }

    /** Valve's text KeyValues: quoted keys and values, nested braces. */
    private class KeyValues(private val s: String) {
        private var i = 0
        fun read(): Map<String, Any> = map(top = true)
        private fun map(top: Boolean): Map<String, Any> {
            val out = LinkedHashMap<String, Any>()
            while (true) {
                skip()
                if (i >= s.length) { if (top) return out else throw IOException("unclosed") }
                if (s[i] == '}') { i++; return out }
                val key = token()
                skip()
                if (i < s.length && s[i] == '{') { i++; out[key] = map(top = false) } else out[key] = token()
            }
        }
        private fun skip() {
            while (i < s.length) {
                if (s[i].isWhitespace()) i++
                else if (s.startsWith("//", i)) { while (i < s.length && s[i] != '\n') i++ }
                else break
            }
        }
        private fun token(): String {
            if (i < s.length && s[i] == '"') {
                i++
                val b = StringBuilder()
                while (i < s.length && s[i] != '"') {
                    if (s[i] == '\\' && i + 1 < s.length) { i++; b.append(when (s[i]) { 'n' -> '\n'; 't' -> '\t'; else -> s[i] }) } else b.append(s[i])
                    i++
                }
                i++
                return b.toString()
            }
            val start = i
            while (i < s.length && !s[i].isWhitespace() && s[i] != '{' && s[i] != '}') i++
            if (start == i) throw IOException("token expected at $i")
            return s.substring(start, i)
        }
    }

    // ---- messages ----

    private fun header(steamId: Long, session: Int) = Proto().fixed64(1, steamId).varint(2, session.toLong()).bytes()

    private fun message(emsg: Int, header: ByteArray, body: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + header.size + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(emsg or PROTO).putInt(header.size).put(header).put(body).array()

    /** One message off the wire as (emsg, header, body); a Multi unpacked (gunzipped when it says so) into the ones inside. */
    @VisibleForTesting
    internal fun messages(data: ByteArray): List<Triple<Int, ByteArray, ByteArray>> {
        if (data.size < 8) return emptyList()
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val raw = b.int
        if (raw and PROTO == 0) return emptyList()
        val emsg = raw and PROTO.inv()
        val hl = b.int
        if (hl < 0 || 8 + hl > data.size) return emptyList()
        val header = data.copyOfRange(8, 8 + hl)
        val body = data.copyOfRange(8 + hl, data.size)
        if (emsg != EMSG_MULTI) return listOf(Triple(emsg, header, body))
        val f = fields(body)
        var inner = f[2]?.firstOrNull() as? ByteArray ?: return emptyList()
        if ((f[1]?.firstOrNull() as? Long ?: 0L) > 0) inner = GZIPInputStream(ByteArrayInputStream(inner)).use { it.readBytes() }
        val out = ArrayList<Triple<Int, ByteArray, ByteArray>>()
        val ib = ByteBuffer.wrap(inner).order(ByteOrder.LITTLE_ENDIAN)
        while (ib.remaining() >= 4) {
            val len = ib.int
            if (len < 0 || len > ib.remaining()) break
            val one = ByteArray(len).also { ib.get(it) }
            out.addAll(messages(one))
        }
        return out
    }

    // ---- protobuf, the little needed ----

    /** Writes protobuf fields. */
    @VisibleForTesting
    internal class Proto {
        private val out = ByteArrayOutputStream()
        private fun raw(v: Long) { var x = v; while (true) { val b = (x and 0x7F).toInt(); x = x ushr 7; if (x == 0L) { out.write(b); return }; out.write(b or 0x80) } }
        fun varint(field: Int, v: Long) = apply { raw((field shl 3).toLong()); raw(v) }
        fun fixed64(field: Int, v: Long) = apply { raw(((field shl 3) or 1).toLong()); for (k in 0 until 8) out.write(((v ushr (8 * k)) and 0xFF).toInt()) }
        fun bytes(field: Int, b: ByteArray) = apply { raw(((field shl 3) or 2).toLong()); raw(b.size.toLong()); out.write(b) }
        fun string(field: Int, s: String) = bytes(field, s.toByteArray())
        fun bytes(): ByteArray = out.toByteArray()
    }

    /** Reads protobuf fields: number to values (Long for varint and fixed, ByteArray for length-delimited). */
    @VisibleForTesting
    internal fun fields(b: ByteArray): Map<Int, List<Any>> {
        val out = HashMap<Int, MutableList<Any>>()
        var i = 0
        fun varint(): Long { var v = 0L; var s = 0; while (true) { val c = b[i++].toInt() and 0xFF; v = v or ((c and 0x7F).toLong() shl s); s += 7; if (c < 0x80) return v } }
        while (i < b.size) {
            val key = varint()
            val field = (key ushr 3).toInt()
            val value: Any = when ((key and 7).toInt()) {
                0 -> varint()
                1 -> { var v = 0L; for (k in 0 until 8) v = v or ((b[i + k].toLong() and 0xFF) shl (8 * k)); i += 8; v }
                5 -> { var v = 0L; for (k in 0 until 4) v = v or ((b[i + k].toLong() and 0xFF) shl (8 * k)); i += 4; v }
                2 -> { val n = varint().toInt(); val v = b.copyOfRange(i, i + n); i += n; v }
                else -> throw IOException("wire type ${key and 7}")
            }
            out.getOrPut(field) { ArrayList() }.add(value)
        }
        return out
    }

    private fun packed(b: ByteArray): List<Int> {
        val out = ArrayList<Int>(); var i = 0
        while (i < b.size) { var v = 0L; var s = 0; while (true) { val c = b[i++].toInt() and 0xFF; v = v or ((c and 0x7F).toLong() shl s); s += 7; if (c < 0x80) break }; out.add(v.toInt()) }
        return out
    }

    // ---- WebSocket over TLS, binary frames ----

    private class Wss(host: String, timeoutMs: Int) : AutoCloseable {
        // Made with the host name, so it goes out as SNI.
        private val socket: SSLSocket = (SSLSocketFactory.getDefault().createSocket(host, 443) as SSLSocket).apply { soTimeout = timeoutMs }
        private val out: OutputStream
        private val input: DataInputStream

        init {
            // Host name checked against the certificate, as HTTPS does.
            socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            socket.startHandshake()
            out = socket.outputStream
            input = DataInputStream(socket.inputStream)
            val key = ByteArray(16).also { SecureRandom().nextBytes(it) }
            out.write(("GET /cmsocket/ HTTP/1.1\r\nHost: $host\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: ${android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)}\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray())
            out.flush()
            val status = line(input) ?: throw IOException("no upgrade response")
            if (!status.contains(" 101 ")) throw IOException("upgrade refused: $status")
            while (true) { val l = line(input) ?: break; if (l.isEmpty()) break }
        }

        private fun line(s: InputStream): String? {
            val buf = ByteArrayOutputStream()
            while (true) {
                val b = s.read()
                if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
                if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
                buf.write(b)
            }
        }

        fun send(payload: ByteArray) {
            val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
            val frame = ByteArrayOutputStream()
            frame.write(0x82)
            when {
                payload.size < 126 -> frame.write(0x80 or payload.size)
                payload.size < 65536 -> { frame.write(0x80 or 126); frame.write(payload.size ushr 8); frame.write(payload.size and 0xFF) }
                else -> { frame.write(0x80 or 127); for (k in 7 downTo 0) frame.write(((payload.size.toLong() ushr (8 * k)) and 0xFF).toInt()) }
            }
            frame.write(mask)
            for (k in payload.indices) frame.write((payload[k].toInt() xor mask[k % 4].toInt()) and 0xFF)
            out.write(frame.toByteArray()); out.flush()
        }

        /** The next binary message (fragments joined); null on close. */
        fun receive(): ByteArray? {
            val message = ByteArrayOutputStream()
            while (true) {
                val b0 = input.read(); if (b0 < 0) return null
                val b1 = input.read(); if (b1 < 0) return null
                val fin = b0 and 0x80 != 0
                val opcode = b0 and 0x0F
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = input.readUnsignedShort().toLong() else if (len == 127L) len = input.readLong()
                if (len > 32L * 1024 * 1024) throw IOException("frame too large")
                val data = ByteArray(len.toInt()).also { input.readFully(it) }
                when (opcode) {
                    0x8 -> return null
                    0x9 -> { out.write(byteArrayOf(0x8A.toByte(), 0x80.toByte(), 0, 0, 0, 0)); out.flush() }
                    0x2, 0x0 -> { message.write(data); if (fin) return message.toByteArray() }
                    else -> {}
                }
            }
        }

        override fun close() {
            runCatching { out.write(byteArrayOf(0x88.toByte(), 0x80.toByte(), 0, 0, 0, 0)); out.flush() }
            runCatching { socket.close() }
        }
    }
}
