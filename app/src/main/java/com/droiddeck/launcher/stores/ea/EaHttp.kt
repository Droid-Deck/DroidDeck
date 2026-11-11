package com.droiddeck.launcher.stores.ea

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal class EaReply(val status: Int, val body: ByteArray, val headers: Map<String, List<String>>) {
    val ok: Boolean get() = status in 200..299
    val text: String get() = body.toString(Charsets.UTF_8)
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

internal object EaHttp {
    const val USER_AGENT = "Mozilla/5.0 EA Desktop/13.735.2.6250"
    private const val TIMEOUT = 30_000

    fun form(values: List<Pair<String, String>>): String =
        values.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }

    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        follow: Boolean = true,
    ): EaReply {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = TIMEOUT
            c.readTimeout = TIMEOUT
            c.instanceFollowRedirects = follow
            c.setRequestProperty("User-Agent", USER_AGENT)
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body) }
            }
            val status = c.responseCode
            val stream = if (status >= 400) c.errorStream else c.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return EaReply(status, bytes, c.headerFields.filterKeys { it != null })
        } finally {
            c.disconnect()
        }
    }
}
