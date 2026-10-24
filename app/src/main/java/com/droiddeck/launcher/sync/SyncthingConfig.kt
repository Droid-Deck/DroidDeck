package com.droiddeck.launcher.sync

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.xml.sax.InputSource

object SyncthingConfig {
    const val GUI_USER = "droiddeck"
    private const val RELAYS = "dynamic+https://relays.syncthing.net/endpoint"

    fun apiKey(file: File): String? = runCatching { apiKey(file.readText()) }.getOrNull()

    internal fun apiKey(xml: String): String? =
        parse(xml).documentElement.child("gui")?.child("apikey")?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    fun apply(file: File, ports: SyncthingPorts, folderRoot: File, pauseDevices: Boolean, allows: (String) -> Boolean) {
        file.writeText(apply(file.readText(), ports, folderRoot.path, pauseDevices, allows))
    }

    internal fun apply(xml: String, ports: SyncthingPorts, folderRoot: String, pauseDevices: Boolean = false, allows: (String) -> Boolean): String {
        val doc = parse(xml)
        val root = doc.documentElement
        val gui = root.child("gui") ?: doc.createElement("gui").also { root.appendChild(it) }
        gui.setAttribute("tls", "false")
        gui.set("address", "127.0.0.1:${ports.gui}")
        val options = root.child("options") ?: doc.createElement("options").also { root.appendChild(it) }
        options.setList("listenAddress", listOf("tcp://:${ports.sync}", "quic://:${ports.sync}", RELAYS))
        options.set("localAnnouncePort", ports.discovery.toString())
        options.set("urAccepted", "-1")
        options.set("crashReportingEnabled", "false")
        options.set("autoUpgradeIntervalH", "0")
        options.set("startBrowser", "false")
        val defaults = root.child("defaults") ?: doc.createElement("defaults").also { root.appendChild(it) }
        val folder = defaults.child("folder") ?: doc.createElement("folder").also { defaults.appendChild(it) }
        folder.setAttribute("path", folderRoot)
        root.children("folder").filter { !allows(it.getAttribute("path")) }.forEach { it.set("paused", "true") }
        if (pauseDevices) root.children("device").forEach { it.set("paused", "true") }
        return write(doc)
    }

    private fun parse(xml: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xml)))

    private fun write(doc: Document): String = StringWriter().also {
        TransformerFactory.newInstance().newTransformer().apply { setOutputProperty(OutputKeys.INDENT, "no") }
            .transform(DOMSource(doc), StreamResult(it))
    }.toString()

    private fun Element.children(name: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == name }

    private fun Element.child(name: String): Element? = children(name).firstOrNull()

    private fun Element.set(name: String, value: String) = setList(name, listOf(value))

    private fun Element.setList(name: String, values: List<String>) {
        val existing = children(name)
        val anchor = existing.lastOrNull()?.nextSibling
        existing.forEach { removeChild(it) }
        values.forEach { value ->
            val e = ownerDocument.createElement(name).apply { textContent = value }
            if (anchor != null) insertBefore(e, anchor) else appendChild(e)
        }
    }
}
