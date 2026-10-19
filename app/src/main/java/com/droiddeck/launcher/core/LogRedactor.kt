package com.droiddeck.launcher.core

object LogRedactor {
    private val EMAIL = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val JWT_LABELLED = Regex("(?i)(\\bJWT[\\s=:]+)(\\d{8,})")
    private val JWT_BASE64 = Regex("ey[A-Za-z0-9_\\-]{6,}\\.[A-Za-z0-9_\\-]{6,}\\.[A-Za-z0-9_\\-]{6,}")
    private val GUID = Regex("\\b[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\\b")
    private val SECRET_KV = Regex(
        "(?i)\\b(access[_-]?token|refresh[_-]?token|auth[_-]?token|authtoken|token|authcode|" +
            "auth[_-]?ticket|ticket|sessionid|steamloginsecure|webapikey|api[_-]?key|" +
            "machine[_-]?auth(?:[_-]?token)?|machineauth|password|passwd|pwd|secret)" +
            "(\\s*[=:]\\s*|=)([^\\s\"'<>&;,]{4,})"
    )
    private val GUARD_CODE = Regex(
        "(?i)((?:steam\\s*)?guard\\s*code[\\s:=]*|two[\\s-]?factor[\\s:=]*|2fa[\\s:=]*)([A-Za-z0-9]{5})"
    )
    private val WEBAPI_KEY = Regex("\\b[0-9A-Fa-f]{32}\\b")
    private val RESIDUAL = Regex(
        "(?i)\\b(jwt|token|ticket|sessionid|steamloginsecure|machineauth)\\b[\\s=:]*([A-Za-z0-9+/=_\\-]{12,})"
    )
    private val LONG_TOKEN = Regex("[A-Za-z0-9_\\-]{88,}")
    private val STEAMID64 = Regex("\\b(76561)(\\d{8})(\\d{4})\\b")
    private val STEAMID3 = Regex("\\[U:1:(\\d+)]")
    private val EXTERNAL_ADDR = Regex("(?i)(external\\s+(?:ip\\s+)?address\\s*[:=]?\\s*|external\\s+ip\\s*[:=]?\\s*)([0-9A-Fa-f:.]{7,})")
    private val IPV4 = Regex("(?<![0-9.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?![0-9.])")
    private val IPV6 = Regex("(?<![0-9A-Za-z:])[0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7}(?:%[A-Za-z0-9_.]+)?(?![0-9A-Za-z:])")
    private val MAC = Regex("(?i)(?<![0-9a-z:-])[0-9a-f]{2}([:-])(?:[0-9a-f]{2}\\1){4}[0-9a-f]{2}(?![0-9a-z:-])")
    private val MAC_FIELD = Regex(
        "(?i)(\\bmac(?:[_ ]?address)?[\"']?\\s*[:=]\\s*[\"']?)[0-9a-f]{2}([:-])" +
            "(?:[0-9a-f]{2}\\2){4}[0-9a-f]{2}(?![0-9a-z:-])"
    )
    private val DEVICE_IDENTIFIER = Regex(
        "(?i)(\\b(?:android[_ ]?id|(?:device|controller)[_ ]?serial|serial(?:[_ ]?number)?|build\\.serial)" +
            "[\"']?\\s*[:=]\\s*)(\"[^\"\\r\\n<>]+\"|'[^'\\r\\n<>]+'|[^\\s\"'<>&;,\\[\\]{}]+)"
    )

    private val LOGIN_STATE = Regex("(?i)(OnLoginStateChange:? )([^\\s<]\\S*)")
    private val LOGIN_USERS = Regex("(OnLoginUsersChanged )(\\S.*)$")

    private const val ACCOUNT_VALUE = "(\"[^\"<\\r\\n]+\"|'[^'<\\r\\n]+'|[^\\s\"'<>&;,}]+)"
    private val ACCOUNT_KV = Regex(
        "(?i)(?<![A-Za-z0-9_])(account[_-]?name|user[_-]?name|epicusername)" +
            "([\"']?\\s*[=:]\\s*|[\"']\\s+)" + ACCOUNT_VALUE
    )
    private val LOGIN_KV = Regex(
        "(?i)(?<![A-Za-z0-9_])(login)(\\s*=\\s*|[\"']\\s*[=:]\\s*|[\"']\\s+)" + ACCOUNT_VALUE
    )

    private class Learned(val regex: Regex, val needs: String?)

    @Volatile
    private var accounts: List<Learned> = emptyList()

    fun learnAccounts(vararg loginUsers: java.io.File) {
        val names = LinkedHashSet<String>()
        val kv = Regex("\"(AccountName|PersonaName)\"\\s+\"([^\"]*)\"")
        var unread = false
        for (file in loginUsers) {
            if (!file.isFile) continue
            val text = try { file.readText() } catch (e: Exception) { unread = true; continue }
            kv.findAll(text).map { it.groupValues[2].trim() }.filter { it.length >= 3 }.forEach { names += it }
        }
        val learned = names.map { name ->
            val needs = name.lowercase().takeIf { name.all { it.code < 0x80 } }
            Learned(Regex("(?i)(?<![A-Za-z0-9_])" + Regex.escape(name) + "(?![A-Za-z0-9_])"), needs)
        }
        // A file that could not be read keeps the names learned from it before.
        accounts = if (unread) (accounts + learned).distinctBy { it.regex.pattern } else learned
    }

    fun learnFromRuntime(root: java.io.File) {
        learnOwnAddresses(java.io.File(root, "etc/droiddeck-net"))
        learnAccounts(
            java.io.File(root, "root/.local/share/Steam/config/loginusers.vdf"),
            java.io.File(root, "root/.droiddeck-x64/.local/share/Steam/config/loginusers.vdf"),
        )
    }

    @Volatile
    private var own: List<Learned> = emptyList()

    fun learnOwnAddresses(linkFile: java.io.File) {
        val found = ArrayList<Learned>()
        try {
            if (linkFile.isFile) linkFile.forEachLine { line ->
                val addr = line.trim().takeIf { it.startsWith("addr ") }?.split(Regex("\\s+"))?.getOrNull(1) ?: return@forEachLine
                when (kind(addr)) {
                    "public IPv6" -> {
                        val groups = expand6(addr)?.take(4) ?: return@forEachLine
                        val prefix = groups.joinToString(":") { g -> "0{0,3}" + Regex.escape(g.trimStart('0').ifEmpty { "0" }) }
                        found += Learned(
                            Regex("(?i)(?<![0-9A-Fa-f:])$prefix(?::[0-9A-Fa-f]{0,4}){1,4}(?:%[A-Za-z0-9_.]+)?"),
                            groups[0].trimStart('0').ifEmpty { "0" },
                        )
                    }
                    "public IPv4" -> found += Learned(Regex("(?<![0-9.])" + Regex.escape(addr) + "(?![0-9.])"), addr)
                }
            }
        } catch (e: Exception) {
            return
        }
        own = found
    }

    fun kind(address: String): String? {
        val a = address.substringBefore('%')
        IPV4.matchEntire(a)?.let { m ->
            val o = m.groupValues.drop(1).map { it.toInt() }
            if (o.any { it > 255 }) return null
            return when {
                o[0] == 127 -> "loopback IPv4"
                o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168) -> "private IPv4"
                o[0] == 169 && o[1] == 254 -> "link-local IPv4"
                o[0] == 100 && o[1] in 64..127 -> "carrier-NAT IPv4"
                else -> "public IPv4"
            }
        }
        val g = expand6(a) ?: return null
        val first = g[0].toInt(16)
        return when {
            g.all { it.toInt(16) == 0 } || (g.take(7).all { it.toInt(16) == 0 } && g[7].toInt(16) == 1) -> "loopback IPv6"
            first and 0xffc0 == 0xfe80 -> "link-local IPv6"
            first and 0xfe00 == 0xfc00 -> "private IPv6"
            else -> "public IPv6"
        }
    }

    private fun expand6(address: String): List<String>? {
        val a = address.substringBefore('%')
        if (!a.contains(':') || a.count { it == ':' } > 7 || a.contains(":::")) return null
        if (!a.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }) return null
        val parts = if (a.contains("::")) {
            val (head, tail) = a.split("::", limit = 2)
            val h = if (head.isEmpty()) emptyList() else head.split(':')
            val t = if (tail.isEmpty()) emptyList() else tail.split(':')
            if (h.size + t.size > 7) return null
            h + List(8 - h.size - t.size) { "0" } + t
        } else a.split(':')
        if (parts.size != 8 || parts.any { it.isEmpty() || it.length > 4 }) return null
        return parts.map { it.lowercase() }
    }

    fun describeAddresses(text: String): String {
        var out = IPV6.replace(text) { m -> kind(m.value)?.let { "<$it>" } ?: m.value }
        out = IPV4.replace(out) { m -> kind(m.value)?.let { "<$it>" } ?: m.value }
        return out
    }

    /** The at-rest rewrite reads a file into memory twice; larger logs are only scrubbed on the way out. */
    const val IN_MEMORY_SCRUB_MAX = 64L * 1024 * 1024

    fun isBinary(file: java.io.File): Boolean = try {
        file.inputStream().use { input ->
            val buf = ByteArray(4096)
            val n = input.read(buf)
            n > 0 && (0 until n).any { buf[it].toInt() == 0 }
        }
    } catch (e: Exception) {
        true
    }

    fun isText(file: java.io.File): Boolean = file.isFile && file.length() < IN_MEMORY_SCRUB_MAX && !isBinary(file)

    const val RULES_VERSION = 3

    private val LAN_IPV4 = Regex(
        "(?<![\\d.])(?:10\\.\\d{1,3}|192\\.168|172\\.(?:1[6-9]|2\\d|3[01])|169\\.254)\\.\\d{1,3}\\.\\d{1,3}(?!\\.?\\d)"
    )

    fun scrubTo(src: java.io.File, out: java.io.Writer) {
        src.forEachLine { line -> out.write(redact(line)); out.write("\n") }
    }

    fun redactForShare(line: String): String {
        if (line.isEmpty()) return line
        return try {
            val out = SecretScrub.scrub(redact(line), SecretScrub.Urls.KEEP_PATH, "<redacted:token>")
            LAN_IPV4.replace(out, "<lan-address>")
        } catch (t: Throwable) {
            "<redaction failed; line withheld>"
        }
    }

    private const val SHARE_LINE_MAX = 64 * 1024

    fun scrubForShare(src: java.io.File, out: java.io.Writer) {
        src.inputStream().buffered().use { input ->
            input.mark(2)
            val first = input.read()
            val second = input.read()
            input.reset()
            if ((first == 0xff && second == 0xfe) || (first == 0xfe && second == 0xff)) {
                out.write("<unsupported encoding; file withheld>\n")
                return
            }
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            java.io.InputStreamReader(input, decoder).use { reader ->
                val buffer = CharArray(8192)
                val line = StringBuilder()
                var withheld = false
                var afterCr = false
                fun emit() {
                    out.write(if (withheld) "<unsupported or oversized line withheld>" else redactForShare(line.toString()))
                    out.write("\n")
                    line.setLength(0)
                    withheld = false
                }
                try {
                    while (true) {
                        val size = reader.read(buffer)
                        if (size < 0) break
                        for (i in 0 until size) {
                            val ch = buffer[i]
                            if (afterCr) {
                                afterCr = false
                                if (ch == '\n') continue
                            }
                            if (ch == '\r' || ch == '\n') {
                                emit()
                                afterCr = ch == '\r'
                            } else if (!withheld) {
                                // Splitting a line can separate a credential from its label.
                                if (ch == '\u0000' || line.length == SHARE_LINE_MAX) {
                                    withheld = true
                                    line.setLength(0)
                                } else {
                                    line.append(ch)
                                }
                            }
                        }
                    }
                    if (withheld || line.isNotEmpty()) emit()
                } catch (_: java.nio.charset.CharacterCodingException) {
                    out.write("<unsupported encoding; file withheld>\n")
                }
            }
        }
    }

    private fun quoted(value: String): String {
        val q = value.first().takeIf { it == '"' || it == '\'' }?.toString().orEmpty()
        return "$q<redacted:account>$q"
    }

    private class Line(var text: String) {
        private val unfolded = text.any { it.code >= 0x80 }
        private var lower: String? = null

        fun has(vararg words: String): Boolean {
            if (unfolded) return true
            val folded = lower ?: text.lowercase().also { lower = it }
            return words.any { folded.contains(it) }
        }

        fun set(next: String) {
            if (next != text) {
                text = next
                lower = null
            }
        }

        fun holds(c: Char, times: Int): Boolean {
            var n = 0
            for (ch in text) if (ch == c && ++n >= times) return true
            return false
        }

        inline fun hasRun(length: Int, inRun: (Char) -> Boolean): Boolean {
            var n = 0
            for (ch in text) if (!inRun(ch)) n = 0 else if (++n >= length) return true
            return false
        }
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    fun redact(line: String): String {
        if (line.isEmpty()) return line
        return try {
            val out = Line(line)
            if (out.has("mac")) out.set(MAC_FIELD.replace(out.text) { "${it.groupValues[1]}<redacted:mac>" })
            if (out.holds(':', 5) || out.holds('-', 5)) out.set(MAC.replace(out.text, "<redacted:mac>"))
            if (out.has("serial", "android")) out.set(DEVICE_IDENTIFIER.replace(out.text) {
                val value = it.groupValues[2]
                val quote = value.first().takeIf { char -> char == '\"' || char == '\'' }?.toString().orEmpty()
                "${it.groupValues[1]}${quote}<redacted:serial>${quote}"
            })
            if (out.holds('-', 4)) out.set(GUID.replace(out.text, "<redacted:guid>"))
            if (out.has("jwt")) out.set(JWT_LABELLED.replace(out.text) { "${it.groupValues[1]}<redacted:jwt>" })
            if ("ey" in out.text) out.set(JWT_BASE64.replace(out.text, "<redacted:jwt>"))
            if (out.has("token", "auth", "ticket", "sessionid", "steamloginsecure", "api", "machine", "pass", "pwd", "secret")) {
                out.set(SECRET_KV.replace(out.text) { "${it.groupValues[1]}${it.groupValues[2]}<redacted:token>" })
            }
            if (out.has("guard", "factor", "2fa")) out.set(GUARD_CODE.replace(out.text) { "${it.groupValues[1]}<redacted:code>" })
            if (out.hasRun(32, ::isHex)) out.set(WEBAPI_KEY.replace(out.text, "<redacted:key>"))
            if ("76561" in out.text) out.set(STEAMID64.replace(out.text) { "${it.groupValues[1]}********${it.groupValues[3]}" })
            if ("[U:1:" in out.text) out.set(STEAMID3.replace(out.text) { m ->
                val id = m.groupValues[1]
                "[U:1:${if (id.length > 4) "*".repeat(id.length - 4) + id.takeLast(4) else id}]"
            })
            if (out.has("external")) out.set(EXTERNAL_ADDR.replace(out.text) { "${it.groupValues[1]}<redacted:ip>" })
            for (r in own) if (r.needs == null || out.has(r.needs)) out.set(r.regex.replace(out.text, "<redacted:ip>"))
            if (out.has("onloginstatechange")) out.set(LOGIN_STATE.replace(out.text) { "${it.groupValues[1]}<redacted:account>" })
            if ("OnLoginUsersChanged " in out.text) out.set(LOGIN_USERS.replace(out.text) { "${it.groupValues[1]}<redacted:account>" })
            if (out.has("account", "user")) {
                out.set(ACCOUNT_KV.replace(out.text) { "${it.groupValues[1]}${it.groupValues[2]}${quoted(it.groupValues[3])}" })
            }
            if (out.has("login")) out.set(LOGIN_KV.replace(out.text) { "${it.groupValues[1]}${it.groupValues[2]}${quoted(it.groupValues[3])}" })
            for (r in accounts) if (r.needs == null || out.has(r.needs)) out.set(r.regex.replace(out.text, "<redacted:account>"))
            if ('@' in out.text) out.set(EMAIL.replace(out.text, "<redacted:email>"))
            if (out.has("jwt", "token", "ticket", "sessionid", "steamloginsecure", "machineauth")) {
                out.set(RESIDUAL.replace(out.text) { "${it.groupValues[1]}=<redacted:token>" })
            }
            if (out.hasRun(88) { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }) {
                out.set(LONG_TOKEN.replace(out.text, "<redacted:token>"))
            }
            out.text
        } catch (t: Throwable) {
            "<redaction failed; line withheld>"
        }
    }
}
