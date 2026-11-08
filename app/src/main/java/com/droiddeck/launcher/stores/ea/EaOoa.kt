package com.droiddeck.launcher.stores.ea

import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.net.URLEncoder
import java.security.SecureRandom

/** Origin Online Activation: the per-machine licence an EA game's activation layer checks before it runs. */
object EaOoa {
    private const val LICENSES = "https://proxy.novafusion.ea.com/licenses"
    const val LICENSE_DIR = "ProgramData/Electronic Arts/EA Services/License"
    private const val SIGNATURE_SLOT = 65

    /** What the game sees of the machine inside its Wine prefix, as reported by Wine's WMI. */
    class Hardware(
        val boardManufacturer: String,
        val boardSerial: String,
        val biosManufacturer: String,
        val biosSerial: String,
        val osInstallDate: String,
        val osSerial: String,
        val volumeSerial: String,
        val gpuPnpId: String,
        val cpuManufacturer: String,
        val processorId: String,
        val cpuEcx: String,
        val brand: String,
        val hostname: String,
    ) {
        companion object {
            fun fromJson(o: JSONObject): Hardware {
                fun s(k: String, d: String) = o.optString(k).trim().ifEmpty { d }
                return Hardware(
                    boardManufacturer = s("boardManufacturer", "Microsoft Corporation"),
                    boardSerial = s("boardSerial", "None"),
                    biosManufacturer = s("biosManufacturer", "Microsoft Corporation"),
                    biosSerial = s("biosSerial", "None"),
                    osInstallDate = s("osInstallDate", "1970-01-0100:00:00.000000000+0000"),
                    osSerial = s("osSerial", "None"),
                    volumeSerial = s("volumeSerial", "00000000").replace("-", "").lowercase().padStart(8, '0'),
                    gpuPnpId = s("gpuPnpId", "None"),
                    cpuManufacturer = s("cpuManufacturer", ""),
                    processorId = s("processorId", "0000000000000000").uppercase().padStart(16, '0'),
                    cpuEcx = s("cpuEcx", "00000000").lowercase(),
                    brand = o.optString("brand").trim(),
                    hostname = s("hostname", "localhost"),
                )
            }
        }
    }

    fun machineHash(hw: Hardware, version: Int): String {
        val edxEax = hw.processorId.takeLast(16)
        val edx = edxEax.take(8).lowercase()
        val shortGpu = hw.gpuPnpId.substringBeforeLast('\\', hw.gpuPnpId)
        val parts = arrayListOf(hw.boardManufacturer, hw.boardSerial)
        when {
            version <= 1 -> parts += listOf(hw.hostname, hw.biosManufacturer, hw.biosSerial, hw.osInstallDate, hw.osSerial)
            version == 2 -> parts += listOf(hw.biosManufacturer, hw.biosSerial, hw.osInstallDate, hw.osSerial, hw.volumeSerial, shortGpu, hw.cpuManufacturer, edx, hw.cpuEcx)
            version == 3 -> parts += listOf(hw.biosManufacturer, hw.biosSerial, hw.volumeSerial, shortGpu, hw.cpuManufacturer, edx, hw.cpuEcx)
            else -> parts += listOf(hw.biosManufacturer, hw.biosSerial, hw.volumeSerial, hw.gpuPnpId, hw.cpuManufacturer, edxEax)
        }
        val text = StringBuilder(parts.joinToString(";")).append(';')
        if (version >= 2) text.append(hw.brand.take(47).padEnd(47, '\u0000')).append(';')
        val digest = EaCrypto.sha1(text.toString().toByteArray(Charsets.ISO_8859_1))
        return if (version < 4) digest.joinToString("") { Integer.toHexString(it.toInt() and 0xFF) } else EaCrypto.hex(digest)
    }

    /** Version of the game's activation layer, which decides how the machine hash is built. */
    fun activationVersion(game: File): Int {
        val core = EaPaths.find(game, "Core") ?: return 1
        val dll = listOf("activation.dll", "awc.dll").firstNotNullOfOrNull { EaPaths.find(core, it) } ?: return 1
        val (major, minor, patch) = productVersion(dll.readBytes()) ?: return 1
        return when {
            major >= 5 -> 4
            major == 4 && (minor > 7 || (minor == 7 && patch >= 6)) -> 3
            major == 4 && minor == 7 -> 2
            major == 4 -> 1
            else -> 0
        }
    }

    fun needsLicense(game: File): Boolean = EaPaths.find(game, "Core")?.isDirectory == true

    private fun encodedSignature(game: File): Boolean = EaPaths.find(game, "Core")?.let { EaPaths.find(it, "activation.exe") } != null

    internal fun productVersion(pe: ByteArray): Triple<Int, Int, Int>? {
        val magic = byteArrayOf(0xBD.toByte(), 0x04, 0xEF.toByte(), 0xFE.toByte())
        var i = 0
        while (i + 24 <= pe.size) {
            if (pe[i] == magic[0] && pe[i + 1] == magic[1] && pe[i + 2] == magic[2] && pe[i + 3] == magic[3]) {
                fun u32(at: Int) = (pe[at].toLong() and 0xFF) or ((pe[at + 1].toLong() and 0xFF) shl 8) or ((pe[at + 2].toLong() and 0xFF) shl 16) or ((pe[at + 3].toLong() and 0xFF) shl 24)
                val ms = u32(i + 16)
                val ls = u32(i + 20)
                return Triple((ms ushr 16).toInt(), (ms and 0xFFFF).toInt(), (ls ushr 16).toInt())
            }
            i++
        }
        return null
    }

    class License(val fields: LinkedHashMap<String, String>, val namespace: String, val signature: String) {
        val gameToken: String? get() = fields["GameToken"]?.takeIf { it.isNotEmpty() }
        val contentId: String get() = fields["ContentId"].orEmpty()
    }

    internal fun parseLicense(xml: String, signature: String): License {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
        parser.setInput(StringReader(xml.replace(Regex("^<\\?xml[^>]*\\?>"), "").trim()))
        val fields = LinkedHashMap<String, String>()
        var ns = ""
        var depth = 0
        var tag = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    depth++
                    if (depth == 1) ns = parser.getAttributeValue(null, "xmlns").orEmpty() else tag = parser.name
                    if (depth == 2) fields[tag] = ""
                }
                XmlPullParser.TEXT -> if (depth == 2) fields[tag] = fields[tag].orEmpty() + parser.text
                XmlPullParser.END_TAG -> depth--
            }
            parser.next()
        }
        return License(fields, ns, signature)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun licenseXml(l: License): String {
        val order = listOf("CipherKey", "MachineHash", "ContentId", "UserId", "GameToken", "GrantTime", "StartTime", "Nonce")
        val body = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        body.append("<License xmlns=\"").append(esc(l.namespace)).append("\">")
        for (k in order) {
            val v = l.fields[k] ?: continue
            if (k == "GameToken" && v.isEmpty()) continue
            body.append('<').append(k).append('>').append(esc(v)).append("</").append(k).append('>')
        }
        return body.append("</License>").toString()
    }

    fun licenseBlob(l: License, encoded: Boolean): ByteArray {
        val signature = if (encoded) l.signature.toByteArray() else java.util.Base64.getDecoder().decode(l.signature)
        val head = ByteArray(SIGNATURE_SLOT)
        System.arraycopy(signature, 0, head, 0, signature.size.coerceAtMost(SIGNATURE_SLOT))
        return head + EaCrypto.licenseEncrypt(licenseXml(l).toByteArray())
    }

    fun request(token: String, contentId: String, machineHash: String, requestToken: String? = null, requestType: String? = null): License {
        val nonce = EaCrypto.hex(ByteArray(4).also(SecureRandom()::nextBytes))
        val params = mutableListOf("contentId" to contentId, "machineHash" to machineHash, "nonce" to nonce, "ea_eadmtoken" to token)
        if (requestToken != null) params += "requestToken" to requestToken
        if (requestType != null) params += "requestType" to requestType
        val url = LICENSES + "?" + params.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val reply = EaHttp.request(url, headers = mapOf("X-Requester-Id" to "Origin Online Activation", "User-Agent" to "EACTransaction"))
        if (!reply.ok) throw IllegalStateException("EA refused the licence (${reply.status}): ${reply.text.take(160)}")
        val signature = reply.header("x-signature") ?: throw IllegalStateException("EA's licence had no signature")
        return parseLicense(String(EaCrypto.licenseDecrypt(reply.body), Charsets.UTF_8), signature)
    }

    /** Licence files for a game about to start, named as the activation layer looks for them. */
    fun files(game: File, license: License): List<Pair<String, ByteArray>> {
        val blob = licenseBlob(license, encodedSignature(game))
        val id = license.contentId
        return listOf("$LICENSE_DIR/$id.dlf" to blob, "$LICENSE_DIR/${id}_cached.dlf" to blob)
    }
}
