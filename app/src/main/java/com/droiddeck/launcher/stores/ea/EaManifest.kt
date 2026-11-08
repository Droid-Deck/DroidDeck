package com.droiddeck.launcher.stores.ea

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader

/** What `__Installer/installerdata.xml` in an EA build says about running and finishing the install. */
class EaManifest(
    val version: String,
    val launchers: List<Launcher>,
    val touchupPath: String,
    val touchupParameters: String,
) {
    class Launcher(val path: String, val parameters: String, val trial: Boolean)

    fun launcher(): Launcher? = launchers.firstOrNull { !it.trial } ?: launchers.firstOrNull()

    companion object {
        const val RELATIVE = "__Installer/installerdata.xml"

        fun decode(bytes: ByteArray): String {
            val utf16 = bytes.size >= 2 && ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) || (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))
            val text = if (utf16) String(bytes, Charsets.UTF_16) else String(bytes, Charsets.UTF_8)
            return text.trimStart('﻿')
        }

        fun read(folder: File): EaManifest? {
            val file = File(folder, RELATIVE).takeIf { it.isFile } ?: EaPaths.find(folder, RELATIVE) ?: return null
            return runCatching { parse(decode(file.readBytes())) }.getOrNull()
        }

        fun parse(xml: String): EaManifest {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(xml.replace(Regex("^<\\?xml[^>]*\\?>"), "")))
            val stack = ArrayList<String>()
            var version = ""
            val launchers = ArrayList<Launcher>()
            var touchupPath = ""
            var touchupParams = ""
            var path = ""
            var params = ""
            var trial = false
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name
                        stack += name
                        if (name.equals("gameVersion", true)) {
                            version = parser.getAttributeValue(null, "version") ?: version
                        }
                        if (name.equals("launcher", true)) { path = ""; params = ""; trial = false }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text.trim()
                        val tag = stack.lastOrNull().orEmpty()
                        val parent = stack.getOrNull(stack.size - 2).orEmpty()
                        if (text.isNotEmpty()) when {
                            parent.equals("launcher", true) && tag.equals("filePath", true) -> path = text
                            parent.equals("launcher", true) && tag.equals("parameters", true) -> params = text
                            parent.equals("launcher", true) && tag.equals("trial", true) -> trial = text.equals("true", true)
                            (parent.equals("touchup", true) || parent.equals("executable", true)) && tag.equals("filePath", true) -> touchupPath = text
                            (parent.equals("touchup", true) || parent.equals("executable", true)) && tag.equals("parameters", true) -> touchupParams = text
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name.equals("launcher", true) && path.isNotEmpty()) launchers += Launcher(path, params, trial)
                        stack.removeLastOrNull()
                    }
                }
                parser.next()
            }
            return EaManifest(version, launchers, touchupPath, touchupParams)
        }
    }
}

object EaPaths {
    private val KEY = Regex("^\\[([^\\]]+)\\](.*)$")

    /** `[HKEY_LOCAL_MACHINE\SOFTWARE\Publisher\Game\Install Dir]bin\game.exe` splits into the registry key, value and relative path. */
    class Registered(val key: String, val value: String, val rest: String)

    fun registered(path: String): Registered? {
        val m = KEY.find(path.trim()) ?: return null
        val full = m.groupValues[1]
        val cut = full.lastIndexOf('\\').takeIf { it > 0 } ?: return null
        return Registered(full.substring(0, cut), full.substring(cut + 1), m.groupValues[2])
    }

    fun relative(path: String): String {
        val rest = registered(path)?.rest ?: path.replace("{installLocation}", "", ignoreCase = true)
        return rest.replace('\\', '/').trimStart('/').split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
    }

    /** The real file for a path the manifest may spell in a different case. */
    fun find(folder: File, relative: String): File? {
        var cur = folder
        for (part in relative.replace('\\', '/').split('/').filter { it.isNotEmpty() }) {
            val names = cur.listFiles() ?: return null
            cur = names.firstOrNull { it.name == part } ?: names.firstOrNull { it.name.equals(part, true) } ?: return null
        }
        return cur.takeIf { it.exists() && it != folder }
    }

    fun relativeOnDisk(folder: File, relative: String): String? =
        find(folder, relative)?.relativeTo(folder)?.path?.replace(File.separatorChar, '/')

    fun splitArgs(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var any = false
        for (c in text) {
            when {
                c == '"' -> { quoted = !quoted; any = true }
                c.isWhitespace() && !quoted -> { if (any || cur.isNotEmpty()) out += cur.toString(); cur.clear(); any = false }
                else -> cur.append(c)
            }
        }
        if (any || cur.isNotEmpty()) out += cur.toString()
        return out
    }
}
