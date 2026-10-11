package com.droiddeck.launcher.core

/**
 * The one set of rules that keeps URLs and tokens out of anything the app writes or shares: the
 * stores' log ([com.droiddeck.launcher.stores.StoreLog.redactLine]) and the last pass over every
 * text file in a shared log zip ([LogRedactor.redactForShare]).
 *
 * - Every URL loses its query, fragment and `user:password@`. [Urls.SHORT] then keeps only the host
 *   and a plain first path segment (the stores' lines, where a GOG secure link carries its token in
 *   the path); [Urls.KEEP_PATH] keeps the path but blanks any segment that carries a token.
 * - Outside URLs, the values of token-like keys (`token`, `__token__`, `f_token`, `hdnts`, access /
 *   refresh / id tokens; exchange and authorization codes of four characters or more, and a bare
 *   `code=` where it is one: in a query string or on a line with OAuth keys such as `client_id`,
 *   `redirect_uri` or `state`, never an 8-hex exception code like Proton's `code=c0000005`) and of
 *   Authorization / Cookie headers are blanked with [mark].
 *
 * A line already scrubbed comes out unchanged.
 */
object SecretScrub {
    enum class Urls { SHORT, KEEP_PATH }

    private val URL = Regex("""[A-Za-z][A-Za-z0-9+.\-]*://[^\s"'<>]+""")
    private val SAFE_SEGMENT = Regex("""[A-Za-z0-9._\-]{1,40}""")
    private val TOKEN_SEGMENT = Regex("""(?i)(token|hdnts|hmac|signature|sig|exp)=""")
    private val TOKEN = Regex("""(?i)(?<![A-Za-z0-9_])(__token__|f_token|hdnts|access_token|refresh_token|id_token|token)(["']?\s*[=:]\s*["']?)[^\s&;,"'<>}]+""")
    /** Codes that are only ever OAuth grants. */
    private val GRANT_CODE = Regex("""(?i)(?<![A-Za-z0-9_])(exchange_code|authorization_?code)(["']?\s*=\s*["']?)[^\s&;,"'<>}]{4,}""")
    /** A bare `code=`: a grant only right after `?` / `&` (a query string) or beside OAuth keys ([OAUTH_CONTEXT]). */
    private val CODE = Regex("""(?i)(?<![A-Za-z0-9_])(code)(["']?\s*=\s*["']?)([^\s&;,"'<>}]{4,})""")
    private val OAUTH_CONTEXT = Regex("""(?i)(?<![A-Za-z0-9_])(client_id|client_secret|redirect_uri|state|grant_type|response_type|access_token|refresh_token|id_token)["']?\s*[=:]""")
    /** A Windows exception or status code (`code=c0000005`, `code=0x80000003`): never a grant. */
    private val STATUS_CODE = Regex("""(?i)(0x)?[0-9a-f]{8}""")
    private val HEADER = Regex("""(?i)\b(authorization|cookie|set-cookie)(\s*[:=]\s*).+""")

    fun scrub(text: String, urls: Urls, mark: String): String {
        var out = URL.replace(text) { m -> if (urls == Urls.SHORT) short(m.value) else keepPath(m.value, mark) }
        out = TOKEN.replace(out) { m -> m.groupValues[1] + m.groupValues[2] + mark }
        out = GRANT_CODE.replace(out) { m -> m.groupValues[1] + m.groupValues[2] + mark }
        val oauth = OAUTH_CONTEXT.containsMatchIn(text)
        val src = out
        out = CODE.replace(src) { m ->
            val inQuery = m.range.first > 0 && src[m.range.first - 1] in "?&"
            val grant = !STATUS_CODE.matches(m.groupValues[3]) && (oauth || inQuery)
            if (grant) m.groupValues[1] + m.groupValues[2] + mark else m.value
        }
        out = HEADER.replace(out) { m -> m.groupValues[1] + m.groupValues[2] + mark }
        return out
    }

    private fun split(url: String): Triple<String, String, String?> {
        val schemeEnd = url.indexOf("://")
        val rest = url.substring(schemeEnd + 3).substringBefore('#').substringBefore('?')
        val slash = rest.indexOf('/')
        val authority = (if (slash >= 0) rest.substring(0, slash) else rest).substringAfterLast('@')
        return Triple(url.substring(0, schemeEnd), authority, if (slash >= 0) rest.substring(slash + 1) else null)
    }

    private fun short(url: String): String {
        val (scheme, authority, path) = split(url)
        if (path == null) return "$scheme://$authority"
        val first = path.substringBefore('/')
        if (first.isEmpty()) return "$scheme://$authority/"
        val shown = if (SAFE_SEGMENT.matches(first)) first else "…"
        return "$scheme://$authority/$shown" + if (path.length > first.length && shown != "…") "/…" else ""
    }

    private fun keepPath(url: String, mark: String): String {
        val (scheme, authority, path) = split(url)
        val clean = path?.split('/')?.joinToString("/") { seg -> if (TOKEN_SEGMENT.containsMatchIn(seg)) mark else seg }
        return "$scheme://$authority" + (clean?.let { "/$it" } ?: "")
    }
}
