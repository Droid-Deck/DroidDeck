package com.droiddeck.launcher.stores

import android.content.Context
import android.util.Base64
import android.util.Log
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.security.SecureRandom

/**
 * Adds and removes a store game's shortcut in a Steam client that is running right now, so it
 * shows without waiting for the next client start. The runtime starts the client with its
 * DevTools port open (droiddeck-session writes it to session/agent/cdp-port) and the client's own
 * JavaScript API has `SteamClient.Apps.AddShortcut`; this speaks the small part of the DevTools
 * protocol that one `Runtime.evaluate` needs, over a plain socket, nothing else.
 *
 * Best-effort throughout: no session, no port, no SharedJSContext, or an API that moved and the
 * shortcut simply arrives with the shortcuts writer at the next start. The appid Steam derives
 * (CRC of the quoted exe plus the name) is the one the writer computes, so the two never disagree.
 */
object SteamLiveShortcuts {
    private const val TAG = "SteamLive"
    private const val TAG_NAME = "droiddeck-app"
    private const val COMPAT_TOOL = "droiddeck-proton-arm64"

    /** Adds every store game in [games] the client does not list yet. Blocking; worker thread. */
    fun sync(context: Context, games: List<AddedGames.Game>) {
        val wanted = games.filter { it.source != Library.ADDED }
        if (wanted.isEmpty() || !clientRunning()) return
        val list = JSONArray()
        for (g in wanted) list.put(JSONObject().put("name", g.name).put("exe", g.guestExe).put("dir", g.guestDir))
        val js = """
            (async () => {
              const want = ${list};
              const have = await SteamClient.Apps.GetAllShortcuts();
              const strip = s => (s || "").replace(/^"|"$/g, "");
              let added = 0;
              for (const w of want) {
                if (have.some(a => a.data && strip(a.data.strExePath) === w.exe && a.data.strAppName === w.name)) continue;
                const id = await SteamClient.Apps.AddShortcut(w.name, w.exe, w.dir, "");
                if (!id) continue;
                try { SteamClient.Apps.AddUserTagToApps([id], ${JSONObject.quote(TAG_NAME)}); } catch (e) {}
                try { SteamClient.Apps.SpecifyCompatTool(id, ${JSONObject.quote(COMPAT_TOOL)}); } catch (e) {}
                added++;
              }
              return added;
            })()
        """.trimIndent()
        val result = evaluate(context, js) ?: return
        Log.i(TAG, "live shortcuts: $result added")
        if (result != "0") StoresState.logLine("Steam: $result shortcut(s) added to the running client")
    }

    /** Removes the shortcut with [appId] from the running client. Blocking; worker thread. */
    fun remove(context: Context, appId: Long) {
        if (!clientRunning()) return
        val result = evaluate(context, "(async () => { await SteamClient.Apps.RemoveShortcut(${appId and 0xFFFFFFFFL}); return 1; })()")
        if (result != null) Log.i(TAG, "live shortcuts: removed $appId")
    }

    private fun clientRunning(): Boolean = SessionState.running && SessionState.mode == SessionService.MODE_STEAM && !SessionState.stopRequested

    /** `Runtime.evaluate` of [expression] in the client's SharedJSContext; the value as text, or null. */
    private fun evaluate(context: Context, expression: String): String? {
        val portFile = File(LinuxRuntime.sessionRoot(context), "agent/cdp-port")
        val port = runCatching { portFile.readText().trim().toInt() }.getOrNull() ?: return null
        val targets = runCatching { JSONArray(get("http://127.0.0.1:$port/json")) }.getOrNull() ?: return null
        var wsUrl: String? = null
        for (i in 0 until targets.length()) {
            val t = targets.optJSONObject(i) ?: continue
            if (t.optString("title").contains("SharedJSContext") || t.optString("url").contains("SharedJSContext")) { wsUrl = t.optString("webSocketDebuggerUrl").ifEmpty { null }; break }
        }
        if (wsUrl == null) { Log.w(TAG, "no SharedJSContext target"); return null }
        return try {
            WebSocket(wsUrl, 8000).use { ws ->
                ws.sendText(JSONObject().put("id", 1).put("method", "Runtime.evaluate").put("params", JSONObject()
                    .put("expression", expression).put("returnByValue", true).put("awaitPromise", true).put("userGesture", true)).toString())
                val deadline = System.currentTimeMillis() + 15_000
                while (System.currentTimeMillis() < deadline) {
                    val message = JSONObject(ws.receiveText() ?: return null)
                    if (message.optInt("id") != 1) continue
                    if (message.has("error")) { Log.w(TAG, "cdp: ${message.optJSONObject("error")}"); return null }
                    val result = message.optJSONObject("result") ?: return null
                    result.optJSONObject("exceptionDetails")?.let { Log.w(TAG, "js: ${it.optJSONObject("exception")?.optString("description") ?: it.optString("text")}"); return null }
                    return result.optJSONObject("result")?.opt("value")?.toString()
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "cdp: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 3000
        try { return c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    /** The least of RFC 6455 a localhost DevTools talk needs: upgrade, masked text out, text in. */
    private class WebSocket(url: String, timeoutMs: Int) : AutoCloseable {
        private val socket: Socket
        private val out: OutputStream
        private val input: DataInputStream

        init {
            val u = URL(url.replaceFirst("ws://", "http://"))
            socket = Socket(u.host, if (u.port > 0) u.port else 80).apply { soTimeout = timeoutMs }
            out = socket.getOutputStream()
            input = DataInputStream(socket.getInputStream())
            val key = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val path = u.path.ifEmpty { "/" } + (u.query?.let { "?$it" } ?: "")
            out.write(("GET $path HTTP/1.1\r\nHost: ${u.host}:${u.port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: ${Base64.encodeToString(key, Base64.NO_WRAP)}\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray())
            out.flush()
            val status = readLine(input) ?: throw java.io.IOException("no upgrade response")
            if (!status.contains(" 101 ")) throw java.io.IOException("upgrade refused: $status")
            while (true) { val line = readLine(input) ?: break; if (line.isEmpty()) break }
        }

        private fun readLine(s: InputStream): String? {
            val buf = ByteArrayOutputStream()
            while (true) {
                val b = s.read()
                if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
                if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
                buf.write(b)
            }
        }

        fun sendText(text: String) {
            val payload = text.toByteArray()
            val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
            val frame = ByteArrayOutputStream()
            frame.write(0x81)
            when {
                payload.size < 126 -> frame.write(0x80 or payload.size)
                payload.size < 65536 -> { frame.write(0x80 or 126); frame.write(payload.size ushr 8); frame.write(payload.size and 0xFF) }
                else -> { frame.write(0x80 or 127); for (i in 7 downTo 0) frame.write(((payload.size.toLong() ushr (8 * i)) and 0xFF).toInt()) }
            }
            frame.write(mask)
            for (i in payload.indices) frame.write((payload[i].toInt() xor mask[i % 4].toInt()) and 0xFF)
            out.write(frame.toByteArray()); out.flush()
        }

        /** The next text message (fragments joined); null on close. */
        fun receiveText(): String? {
            val message = ByteArrayOutputStream()
            while (true) {
                val b0 = input.read(); if (b0 < 0) return null
                val b1 = input.read(); if (b1 < 0) return null
                val fin = b0 and 0x80 != 0
                val opcode = b0 and 0x0F
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = input.readUnsignedShort().toLong()
                else if (len == 127L) len = input.readLong()
                val masked = b1 and 0x80 != 0
                val mask = if (masked) ByteArray(4).also { input.readFully(it) } else null
                if (len > 16L * 1024 * 1024) throw java.io.IOException("frame too large")
                val data = ByteArray(len.toInt()).also { input.readFully(it) }
                if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (opcode) {
                    0x8 -> return null
                    0x9 -> { out.write(byteArrayOf(0x8A.toByte(), 0x80.toByte(), 0, 0, 0, 0)); out.flush() }
                    0x1, 0x0 -> { message.write(data); if (fin) return message.toString("UTF-8") }
                    else -> {}
                }
            }
        }

        override fun close() { runCatching { out.write(byteArrayOf(0x88.toByte(), 0x80.toByte(), 0, 0, 0, 0)); out.flush() }; runCatching { socket.close() } }
    }
}
