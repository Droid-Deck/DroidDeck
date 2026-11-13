package com.droiddeck.launcher.stores.rockstar

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal object RockstarRos {
    const val HOST = "https://rgl-prod.ros.rockstargames.com"
    const val PUBLIC_HOST = "https://prod.ros.rockstargames.com"
    const val SERVICES = "/launcher/11/launcherservices/"
    private const val MAX_BYTES = 32 * 1024 * 1024

    class RosException(message: String, val code: String = "", val status: Int = 0) : Exception(message)

    class Reply(val status: Int, val body: String)

    fun form(fields: List<Pair<String, String>>): String =
        fields.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }

    fun call(method: String, fields: List<Pair<String, String>>, host: String = HOST, headers: Map<String, String> = emptyMap()): Reply {
        val c = URL(host + SERVICES + method).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = false
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
        c.setRequestProperty("User-Agent", RockstarAuth.USER_AGENT)
        for ((k, v) in headers) c.setRequestProperty(k, v)
        try {
            c.outputStream.use { it.write(form(fields).toByteArray(Charsets.UTF_8)) }
            val status = c.responseCode
            val stream = (if (status in 200..299) c.inputStream else c.errorStream)
            val bytes = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) throw RosException("$method: answer too large", status = status)
                }
                out.toByteArray()
            } ?: ByteArray(0)
            return Reply(status, bytes.toString(Charsets.UTF_8))
        } finally {
            c.disconnect()
        }
    }

    fun result(method: String, fields: List<Pair<String, String>>, host: String = HOST, headers: Map<String, String> = emptyMap()): Element {
        val reply = call(method, fields, host, headers)
        if (reply.status !in 200..299) throw RosException("$method: HTTP ${reply.status}", status = reply.status)
        val root = runCatching { parse(reply.body) }.getOrNull()
            ?: throw RosException("$method: HTTP ${reply.status} invalid response", status = reply.status)
        if (text(root, "Status") != "1") {
            val error = first(root, "Error")
            val code = listOfNotNull(error?.getAttribute("Code")?.takeIf { it.isNotEmpty() }, error?.getAttribute("CodeEx")?.takeIf { it.isNotEmpty() }).joinToString("/")
            throw RosException("$method: ${code.ifEmpty { "HTTP ${reply.status}" }}", code, reply.status)
        }
        return root
    }

    fun parse(xml: String): Element {
        require(!xml.contains("<!DOCTYPE", ignoreCase = true)) { "XML document types are not supported" }
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        runCatching { f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        f.isExpandEntityReferences = false
        return f.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))).documentElement
    }

    fun first(root: Element, tag: String): Element? = root.getElementsByTagName(tag).let { if (it.length > 0) it.item(0) as Element else null }

    fun all(root: Element, tag: String): List<Element> = root.getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as Element } }

    fun text(root: Element, tag: String): String? = first(root, tag)?.textContent?.trim()

    fun children(parent: Element, tag: String): List<Element> {
        val out = ArrayList<Element>()
        var n = parent.firstChild
        while (n != null) {
            if (n is Element && n.tagName == tag) out += n
            n = n.nextSibling
        }
        return out
    }
}
