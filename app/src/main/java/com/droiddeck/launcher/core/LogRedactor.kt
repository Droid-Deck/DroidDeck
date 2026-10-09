package com.droiddeck.launcher.core

/**
 * Scrubs credentials out of a log line before it is written somewhere a user will share.
 *
 * The Steam client's own logs are the reason this exists: they carry the account's session token
 * (logged as a bare number after "Using JWT"), machine-auth GUIDs, `token=`/`sessionid=` pairs,
 * Steam Guard codes, 32-hex WebAPI keys and the account's SteamID. A session folder from this app
 * is meant to be attached to a bug report as it is, so none of that may be in it.
 *
 * Ported from Bannerlator's {@code SteamLogRedactor} (GPL-3.0), which was written against real
 * client logs; the account-name registration is left out because this app never learns it.
 *
 * What is deliberately KEPT, because it is what makes a log worth reading: EResult codes, CM host
 * names and Valve's server addresses, timings, token expiry dates, app IDs, pids, connection-state
 * changes and file paths. A SteamID is masked rather than deleted - first four and last four digits
 * survive - so a reader can still tell two accounts' lines apart without the value identifying either.
 *
 * The device's OWN addresses are not kept: the client's IPv6 check logs "external address <ours>"
 * about twenty times a session, and a public IPv6 address is the user's. [learnOwnAddresses] reads
 * the link the session was given (etc/droiddeck-net) and every address on the same /64 - privacy
 * addresses rotate inside it - or an own public IPv4 is replaced wherever it appears. Likewise every
 * Steam account and persona name on the device ([learnAccounts]), for users who sign in with an
 * account name rather than an email.
 */
object LogRedactor {
    private val EMAIL = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    /** "Using JWT 25484942796017334" - the client logs its session token as digits, not base64. */
    private val JWT_LABELLED = Regex("(?i)(\\bJWT[\\s=:]+)(\\d{8,})")
    /** A 3-part base64url JWT logged verbatim (a refresh or access token). */
    private val JWT_BASE64 = Regex("ey[A-Za-z0-9_\\-]{6,}\\.[A-Za-z0-9_\\-]{6,}\\.[A-Za-z0-9_\\-]{6,}")
    /** Machine-auth and other GUIDs. */
    private val GUID = Regex("\\b[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\\b")
    /**
     * key=value secrets. A bare `key` is excluded on purpose so "key=english" survives; a real
     * WebAPI key is caught by [WEBAPI_KEY]. The value class excludes `<` and `>` so running this
     * twice over an already-scrubbed line changes nothing.
     */
    private val SECRET_KV = Regex(
        "(?i)\\b(access[_-]?token|refresh[_-]?token|auth[_-]?token|authtoken|token|authcode|" +
            "auth[_-]?ticket|ticket|sessionid|steamloginsecure|webapikey|api[_-]?key|" +
            "machine[_-]?auth(?:[_-]?token)?|machineauth|password|passwd|pwd|secret)" +
            "(\\s*[=:]\\s*|=)([^\\s\"'<>&;,]{4,})"
    )
    /** A Steam Guard code, only where the text around it says that is what it is. */
    private val GUARD_CODE = Regex(
        "(?i)((?:steam\\s*)?guard\\s*code[\\s:=]*|two[\\s-]?factor[\\s:=]*|2fa[\\s:=]*)([A-Za-z0-9]{5})"
    )
    /** Exactly 32 hex - a WebAPI key. Bounded so a 40-hex depot chunk id is left alone. */
    private val WEBAPI_KEY = Regex("\\b[0-9A-Fa-f]{32}\\b")
    /** A long opaque run after a sensitive word, for anything the rules above missed. */
    private val RESIDUAL = Regex(
        "(?i)\\b(jwt|token|ticket|sessionid|steamloginsecure|machineauth)\\b[\\s=:]*([A-Za-z0-9+/=_\\-]{12,})"
    )
    private val LONG_TOKEN = Regex("[A-Za-z0-9_\\-]{88,}")
    private val STEAMID64 = Regex("\\b(76561)(\\d{8})(\\d{4})\\b")
    private val STEAMID3 = Regex("\\[U:1:(\\d+)]")
    /** "external address 2607:..." / "external IP: 203.0.113.9" - the client stating ours. */
    private val EXTERNAL_ADDR = Regex("(?i)(external\\s+(?:ip\\s+)?address\\s*[:=]?\\s*|external\\s+ip\\s*[:=]?\\s*)([0-9A-Fa-f:.]{7,})")
    private val IPV4 = Regex("(?<![0-9.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?![0-9.])")
    /** An IPv6 literal: "::" somewhere, or all eight groups (a clock's 10:03:05 is neither). */
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

    /**
     * The account a Steam UI login line names ("Login: OnLoginStateChange <account> 2 1 0 0"): a
     * user who signs in with a plain account name, not an email, had it in every log. The single
     * space then a non-space keeps "OnLoginStateChange  0 1 0 0" (no account yet) as it is.
     */
    private val LOGIN_STATE = Regex("(?i)(OnLoginStateChange:? )([^\\s<]\\S*)")
    private val LOGIN_USERS = Regex("(OnLoginUsersChanged )(\\S.*)$")

    /**
     * An account name as a field: `AccountName`, `account_name`, `username` (`"AccountName"
     * "someone"` in a VDF, `account_name=...`, `"username": "..."`) and `login` - the last only
     * as `login=`, a quoted key or a VDF pair, so "Login: OnLoginStateChange ..." keeps its label.
     * A quoted value is blanked whole; one already blanked is left as it is.
     */
    private const val ACCOUNT_VALUE = "(\"[^\"<\\r\\n]+\"|'[^'<\\r\\n]+'|[^\\s\"'<>&;,}]+)"
    private val ACCOUNT_KV = Regex(
        "(?i)(?<![A-Za-z0-9_])(account[_-]?name|user[_-]?name|epicusername)" +
            "([\"']?\\s*[=:]\\s*|[\"']\\s+)" + ACCOUNT_VALUE
    )
    private val LOGIN_KV = Regex(
        "(?i)(?<![A-Za-z0-9_])(login)(\\s*=\\s*|[\"']\\s*[=:]\\s*|[\"']\\s+)" + ACCOUNT_VALUE
    )

    /** The device's Steam accounts and persona names as patterns (see [learnAccounts]). */
    @Volatile
    private var accounts: List<Regex> = emptyList()

    /**
     * Learns every Steam account on the device from the client's loginusers.vdf - its AccountName
     * and PersonaName - so they are replaced wherever a log mentions them, whoever the user is.
     * Names under three characters are skipped: blanking every "a" would ruin a log.
     */
    fun learnAccounts(loginUsers: java.io.File) {
        val found = ArrayList<Regex>()
        try {
            if (loginUsers.isFile) {
                val kv = Regex("\"(AccountName|PersonaName)\"\\s+\"([^\"]*)\"")
                kv.findAll(loginUsers.readText()).map { it.groupValues[2].trim() }.filter { it.length >= 3 }.distinct().forEach { name ->
                    found += Regex("(?i)(?<![A-Za-z0-9_])" + Regex.escape(name) + "(?![A-Za-z0-9_])")
                }
            }
        } catch (e: Exception) {
            return
        }
        accounts = found
    }

    /** Everything the session's runtime can tell about whose logs these are: addresses and accounts. */
    fun learnFromRuntime(root: java.io.File) {
        learnOwnAddresses(java.io.File(root, "etc/droiddeck-net"))
        learnAccounts(java.io.File(root, "root/.local/share/Steam/config/loginusers.vdf"))
    }

    /** This device's public addresses as patterns (see [learnOwnAddresses]); empty until learned. */
    @Volatile
    private var own: List<Regex> = emptyList()

    /**
     * Learns the device's addresses from the link file the app writes for the session
     * (`addr <address> <prefix>` lines). Global IPv6 become their /64, public IPv4 stay exact;
     * private, link-local and loopback addresses identify nobody and are left out.
     */
    fun learnOwnAddresses(linkFile: java.io.File) {
        val found = ArrayList<Regex>()
        try {
            if (linkFile.isFile) linkFile.forEachLine { line ->
                val addr = line.trim().takeIf { it.startsWith("addr ") }?.split(Regex("\\s+"))?.getOrNull(1) ?: return@forEachLine
                when (kind(addr)) {
                    "public IPv6" -> {
                        val groups = expand6(addr)?.take(4) ?: return@forEachLine
                        // Any spelling of an address in this /64: leading zeros dropped, :: anywhere after.
                        val prefix = groups.joinToString(":") { g -> "0{0,3}" + Regex.escape(g.trimStart('0').ifEmpty { "0" }) }
                        found += Regex("(?i)(?<![0-9A-Fa-f:])$prefix(?::[0-9A-Fa-f]{0,4}){1,4}(?:%[A-Za-z0-9_.]+)?")
                    }
                    "public IPv4" -> found += Regex("(?<![0-9.])" + Regex.escape(addr) + "(?![0-9.])")
                }
            }
        } catch (e: Exception) {
            return
        }
        own = found
    }

    /** "public IPv6", "private IPv4", "link-local IPv6", ... for an address literal; null if it is not one. */
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

    /** The eight groups of an IPv6 literal, or null. */
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

    /**
     * Every IP address in [text] replaced by what kind it is ("<public IPv6>"), for reports whose
     * point is the shape of the network, not its numbers.
     */
    fun describeAddresses(text: String): String {
        var out = IPV6.replace(text) { m -> kind(m.value)?.let { "<$it>" } ?: m.value }
        out = IPV4.replace(out) { m -> kind(m.value)?.let { "<$it>" } ?: m.value }
        return out
    }

    /** A file worth scrubbing: text (no NUL in its first 4 KB) and not huge. */
    fun isText(file: java.io.File): Boolean = try {
        file.isFile && file.length() < 64L * 1024 * 1024 && file.inputStream().use { input ->
            val buf = ByteArray(4096)
            val n = input.read(buf)
            n <= 0 || (0 until n).none { buf[it].toInt() == 0 }
        }
    } catch (e: Exception) {
        false
    }

    /**
     * Bump whenever a pattern is added or tightened. A session folder records the version it was
     * scrubbed under (SessionArtifacts.SCRUBBED_TREE_MARKER), and a share copies a file as it is
     * only when that matches, so a folder scrubbed under older rules is scrubbed again on the way
     * out.
     */
    const val RULES_VERSION = 3

    /**
     * A private IPv4 address (10/8, 172.16/12, 192.168/16, link-local 169.254/16): the home
     * network's layout, e.g. its router as the resolver. Blanked in a shared zip only; public
     * server addresses stay. Not part of a longer dotted number (a version like 10.0.19041.1).
     */
    private val LAN_IPV4 = Regex(
        "(?<![\\d.])(?:10\\.\\d{1,3}|192\\.168|172\\.(?:1[6-9]|2\\d|3[01])|169\\.254)\\.\\d{1,3}\\.\\d{1,3}(?!\\.?\\d)"
    )

    /** [src]'s lines, scrubbed, to [out]. */
    fun scrubTo(src: java.io.File, out: java.io.Writer) {
        src.forEachLine { line -> out.write(redact(line)); out.write("\n") }
    }

    /**
     * The last pass for a file going into a shared zip: [redact], then the rules the stores' log
     * uses too ([SecretScrub]) - URLs without query, fragment and user:password and with any
     * token-bearing path segment blanked, token values and Authorization / Cookie headers blanked
     * - and private IPv4 addresses as `<lan-address>`. A line already clean comes out unchanged.
     */
    fun redactForShare(line: String): String {
        if (line.isEmpty()) return line
        return try {
            val out = SecretScrub.scrub(redact(line), SecretScrub.Urls.KEEP_PATH, "<redacted:token>")
            LAN_IPV4.replace(out, "<lan-address>")
        } catch (t: Throwable) {
            "<redaction failed; line withheld>"
        }
    }

    /** [src]'s lines through [redactForShare], to [out]: the pass every text file in a shared zip gets. */
    fun scrubForShare(src: java.io.File, out: java.io.Writer) {
        src.forEachLine { line -> out.write(redactForShare(line)); out.write("\n") }
    }

    /** `<redacted:account>`, inside [value]'s quotes when it had them. */
    private fun quoted(value: String): String {
        val q = value.first().takeIf { it == '"' || it == '\'' }?.toString().orEmpty()
        return "$q<redacted:account>$q"
    }

    /** [line] with every credential shape replaced. Null- and exception-safe by construction. */
    fun redact(line: String): String {
        if (line.isEmpty()) return line
        return try {
            var out = line
            out = MAC_FIELD.replace(out) { "${it.groupValues[1]}<redacted:mac>" }
            out = MAC.replace(out, "<redacted:mac>")
            out = DEVICE_IDENTIFIER.replace(out) {
                val value = it.groupValues[2]
                val quote = value.first().takeIf { char -> char == '\"' || char == '\'' }?.toString().orEmpty()
                "${it.groupValues[1]}${quote}<redacted:serial>${quote}"
            }
            out = GUID.replace(out, "<redacted:guid>")
            out = JWT_LABELLED.replace(out) { "${it.groupValues[1]}<redacted:jwt>" }
            out = JWT_BASE64.replace(out, "<redacted:jwt>")
            out = SECRET_KV.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}<redacted:token>" }
            out = GUARD_CODE.replace(out) { "${it.groupValues[1]}<redacted:code>" }
            out = WEBAPI_KEY.replace(out, "<redacted:key>")
            // Mask, not delete: the last four digits let a reader correlate lines to one account.
            out = STEAMID64.replace(out) { "${it.groupValues[1]}********${it.groupValues[3]}" }
            out = STEAMID3.replace(out) { m ->
                val id = m.groupValues[1]
                "[U:1:${if (id.length > 4) "*".repeat(id.length - 4) + id.takeLast(4) else id}]"
            }
            out = EXTERNAL_ADDR.replace(out) { "${it.groupValues[1]}<redacted:ip>" }
            for (r in own) out = r.replace(out, "<redacted:ip>")
            out = LOGIN_STATE.replace(out) { "${it.groupValues[1]}<redacted:account>" }
            out = LOGIN_USERS.replace(out) { "${it.groupValues[1]}<redacted:account>" }
            out = ACCOUNT_KV.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}${quoted(it.groupValues[3])}" }
            out = LOGIN_KV.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}${quoted(it.groupValues[3])}" }
            for (r in accounts) out = r.replace(out, "<redacted:account>")
            out = EMAIL.replace(out, "<redacted:email>")
            out = RESIDUAL.replace(out) { "${it.groupValues[1]}=<redacted:token>" }
            out = LONG_TOKEN.replace(out, "<redacted:token>")
            out
        } catch (t: Throwable) {
            // A log line is never worth crashing a session over, but an unscrubbed one must not
            // reach the file either.
            "<redaction failed; line withheld>"
        }
    }
}
