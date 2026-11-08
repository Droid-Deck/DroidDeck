package com.droiddeck.launcher.stores.epic

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.GuestCommand
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadStage
import com.droiddeck.launcher.stores.formatBytes
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal object EpicClientSources {
    const val LAUNCHER_URL = "https://launcher-public-service-prod06.ol.epicgames.com/launcher/api/installer/download/EpicGamesLauncher-x86_64.deb"
    const val LAUNCHER_FILE = "EpicGamesLauncher-x86_64.deb"
    const val RUNTIME_BASE = "https://repo.steampowered.com/steamrt4/images/"
    const val RUNTIME_FILE = "com.valvesoftware.SteamRuntime.Platform-amd64,i386-steamrt4-runtime.tar.gz"
    const val GLES_URL = "https://deb.debian.org/debian/pool/main/libg/libglvnd/libgles2_1.7.0-1+b2_amd64.deb"
    const val GLES_FILE = "libgles2_1.7.0-1+b2_amd64.deb"
    const val GLES_SHA256 = "e12c18aa77939d122ee1dea520ba0440b97dc19200b7396e03dff03a3ece74c1"
    const val DEBIAN = "https://deb.debian.org/debian/"
    const val SNAPSHOT = "https://snapshot.debian.org/archive/debian/20261101T000000Z/"
    const val USER_AGENT = "DroidDeck"
    private val VERSION = Regex("[A-Za-z0-9._+-]{1,64}")
    private val SUM = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$")

    class Source(val url: String, val version: String, val sha256: String? = null)

    class Package(val path: String, val sha256: String, val size: Long) {
        val file: String get() = path.substringAfterLast('/')
        val urls: List<String> get() = listOf(DEBIAN + path, SNAPSHOT + path)
    }

    val DESKTOP = listOf(
        Package("pool/main/a/abseil/libabsl20240722_20240722.0-4_amd64.deb", "78c6d7692890990a27cb1d5bbd95b7bfe47a4183ffafec5b30f689f9b58ec6f7", 491548L),
        Package("pool/main/a/aom/libaom3_3.12.1-1+deb13u1_amd64.deb", "743eec7c94ae30295bc557ebac3dfae948e7390e89a2743c585d56297e9d312e", 1871712L),
        Package("pool/main/a/aspell/libaspell15_0.60.8.1-4_amd64.deb", "8be22785198a4e5de1e2c3a157e8cd99ec7d5843c8ac96a174ec701ba4ac28e2", 337684L),
        Package("pool/main/a/at-spi2-core/libatk-bridge2.0-0t64_2.56.2-1+deb13u2_amd64.deb", "c0fe87ea1bdca2f938eaa8286af5ad38ecd15cf18c6cfb5961d7d46ac3192325", 68516L),
        Package("pool/main/a/at-spi2-core/libatspi2.0-0t64_2.56.2-1+deb13u2_amd64.deb", "65e9c9ecbd820fba2d04104cddd0bad33d256cbdfe0d1cb5365f10e363df2315", 81056L),
        Package("pool/main/liba/libavif/libavif16_1.2.1-1.2_amd64.deb", "ba6c334454d3562560ad583fbe2c998e203b0fc5a0e0e91112ddcd3cff4704bb", 133120L),
        Package("pool/main/libc/libcloudproviders/libcloudproviders0_0.3.6-2_amd64.deb", "a626bb23ed9ebc4bc3ce446b552048de63d76768bf0fb492754965c5ee28a7e4", 29232L),
        Package("pool/main/c/colord/libcolord2_1.4.7-3_amd64.deb", "f11fd3b492781122edf0790f1e1fe24c51a41c67d4bb76cff84a9426c5654fa7", 139204L),
        Package("pool/main/e/enchant-2/libenchant-2-2_2.8.2+dfsg1-3_amd64.deb", "83f208d89db1c48e6ede8a23e8ffa2bcddd4f99427bfd1279201247bbf27ca58", 56756L),
        Package("pool/main/libe/libepoxy/libepoxy0_1.5.10-2_amd64.deb", "4c4c8024f2175086de65bca9fdc3fbb967f2863ebf249d489c66d0c8103dd3a3", 192728L),
        Package("pool/main/libe/libevdev/libevdev2_1.13.4+dfsg-1_amd64.deb", "02e85fb9461b85b23f775d5fcf5a31c55d37bec40b63fd98ca5d3a2763714ae7", 32372L),
        Package("pool/main/f/flite/libflite1_2.2-7_amd64.deb", "5945190970be7d4a1f71fb6f7e3e340ba2675e770605df0754051d617a6c5ee6", 12811404L),
        Package("pool/main/libg/libgav1/libgav1-1_0.19.0-3+b1_amd64.deb", "94010041e59c7a8d2d3d1f5e62afd523f002b7b1caa3189419160b66d71c0195", 352956L),
        Package("pool/main/g/gst-plugins-base1.0/libgstreamer-gl1.0-0_1.26.2-1+deb13u1_amd64.deb", "efacc61acbd44a6a067612e4592b9fe81efdc5011ed4c6cf7afd8ed83c5f834a", 269984L),
        Package("pool/main/g/gst-plugins-base1.0/libgstreamer-plugins-base1.0-0_1.26.2-1+deb13u1_amd64.deb", "a20aedb3f89c162b4859cec37076f4eba54b0bcc7e5cb254a26cc4a877a88a1e", 997404L),
        Package("pool/main/g/gstreamer1.0/libgstreamer1.0-0_1.26.2-2_amd64.deb", "08b463307fbde5496a346b1bb300b465cd22bd326f6f4634f482bf441af28a9b", 1421116L),
        Package("pool/main/g/gtk+3.0/libgtk-3-0t64_3.24.49-3_amd64.deb", "4aa8828f01365f937f3bf69f812c8107ee4c8a2b208ffdd7656e8de5de81d98f", 2768812L),
        Package("pool/main/h/hidapi/libhidapi-hidraw0_0.14.0-1+b2_amd64.deb", "89c546cb55be403ce63fe668baff2515979100a30fa07e2b97de186b9c07f01e", 12664L),
        Package("pool/main/h/hunspell/libhunspell-1.7-0_1.7.2+really1.7.2-10+b4_amd64.deb", "a9427e5220e71f8c04c6c0f87826c6fef98e2bab6994a87d6d82e8a120790ecf", 236980L),
        Package("pool/main/h/highway/libhwy1t64_1.2.0-2+b2_amd64.deb", "540c4ff28255fbfa89468061b722dfc2b773b0c1ecf163a063202482dea5b119", 676044L),
        Package("pool/main/h/hyphen/libhyphen0_2.8.8-7+b2_amd64.deb", "fa9dca7b8cf1381fd52800c44a385c5725a78a88bd705f7c39b1046a5293435e", 30664L),
        Package("pool/main/w/webkit2gtk/libjavascriptcoregtk-4.1-0_2.52.6-1~deb13u1_amd64.deb", "3383ebb3e13f31225c65f41112420be58493a3de112b5993cf5c417cfedc47c8", 7511632L),
        Package("pool/main/j/jpeg-xl/libjxl0.11_0.11.2-0.1~deb13u2_amd64.deb", "cd1ca3a52cb0d8fdce1d994bcbf0663de28c64add1cfe8096639ae8fa9808824", 1133312L),
        Package("pool/main/l/lcms2/liblcms2-2_2.16-2+deb13u2_amd64.deb", "32e53c2002ccea8c60c02779bea0ba49ac132fef702988a30d64af79b6617806", 160264L),
        Package("pool/main/libm/libmanette/libmanette-0.2-0_0.2.12-1_amd64.deb", "98841a467692c46fd1870275113235691ebbfe71a3524f6b2a0fa7e9c33c6e56", 43752L),
        Package("pool/main/o/orc/liborc-0.4-0t64_0.4.41-1_amd64.deb", "5b9afe78be0dd71399c0d4d7e5f26beb6ddf207279997942d8280be1b20bd58c", 189604L),
        Package("pool/main/r/rust-rav1e/librav1e0.7_0.7.1-9+b2_amd64.deb", "d5b1373a8d6905ee4523faf1db7a626dc660e03836ec91b616e57ba386f47eb6", 946396L),
        Package("pool/main/libs/libsecret/libsecret-1-0_0.21.7-1_amd64.deb", "949c5428cd3427486ac6c4dfc6d9400ce786c504c02334acc903c873aa52d14f", 111224L),
        Package("pool/main/s/svt-av1/libsvtav1enc2_2.3.0+dfsg-1_amd64.deb", "cdaf961c3cc4fe78a19ff18c17d83d813f78a378fdcc4c86ccbb9b54e84ae599", 2489492L),
        Package("pool/main/w/webkit2gtk/libwebkit2gtk-4.1-0_2.52.6-1~deb13u1_amd64.deb", "6471d989a61a56a7801e88e420789aa6b0eaba630a9787365bba8b9576b7ba1c", 24163372L),
        Package("pool/main/libx/libxslt/libxslt1.1_1.1.35-1.2+deb13u3_amd64.deb", "c8e5a745ca3afaa079b01e0c00d80a241101d1ab840407fcd4bb423de3326160", 233128L),
        Package("pool/main/liby/libyuv/libyuv0_0.0.1904.20250204-1_amd64.deb", "1a6827fe94fa5572a032332df7313ed408e9c0ad78f3a1248391bddc6d920ef2", 173700L),
    )

    fun launcher(): Source? {
        val location = redirect(LAUNCHER_URL) ?: return null
        val version = launcherVersion(location) ?: return null
        return Source(location, version)
    }

    fun launcherVersion(location: String): String? {
        val uri = runCatching { URI(location) }.getOrNull() ?: return null
        if (uri.scheme != "https") return null
        val raw = Regex("/Builds/EGLv2/([^/]+)/Linux/").find(uri.rawPath ?: return null)?.groupValues?.get(1) ?: return null
        val version = runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
        return version.takeIf { VERSION.matches(it) && it != "." && it != ".." }
    }

    fun runtime(): Source? {
        val version = text(RUNTIME_BASE + "latest-public-stable/VERSION.txt")?.trim()?.takeIf { VERSION.matches(it) && it.startsWith("4.") } ?: return null
        val sums = text("$RUNTIME_BASE$version/SHA256SUMS") ?: return null
        val sha = sha256For(sums, RUNTIME_FILE) ?: return null
        return Source("$RUNTIME_BASE$version/" + RUNTIME_FILE.replace(",", "%2C"), version, sha)
    }

    fun gles(): Source = Source(GLES_URL, "1.7.0-1+b2", GLES_SHA256)

    fun sha256For(sums: String, file: String): String? = sums.lineSequence()
        .mapNotNull { SUM.find(it.trim()) }
        .firstOrNull { it.groupValues[2].trim() == file }
        ?.groupValues?.get(1)?.lowercase()

    private fun redirect(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = conn.responseCode
            val location = conn.getHeaderField("Location")
            if (code in 300..399 && location != null) URI(url).resolve(location).toString() else null
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun text(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (conn.responseCode != 200) null else conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private val PART_MIB = longArrayOf(5, 8, 10, 15, 16, 25, 32, 50, 64, 100, 128, 256, 512)

    fun etagMatches(file: File, etag: String): Boolean? {
        val m = Regex("^(?:W/)?\"?([0-9a-fA-F]{32})(?:-(\\d+))?\"?$").matchEntire(etag.trim()) ?: return null
        if (etag.trim().startsWith("W/")) return null
        val want = m.groupValues[1].lowercase()
        val count = m.groupValues[2].toLongOrNull() ?: return hex(digest(file, "MD5", 0L, file.length())) == want
        val size = file.length()
        val candidates = PART_MIB.map { it shl 20 }.filter { (size + it - 1) / it == count }
        if (candidates.isEmpty()) return null
        return candidates.any { multipart(file, it) == want }
    }

    private fun multipart(file: File, partSize: Long): String {
        val outer = MessageDigest.getInstance("MD5")
        var start = 0L
        val size = file.length()
        while (start < size) {
            val end = minOf(size, start + partSize)
            outer.update(digest(file, "MD5", start, end))
            start = end
        }
        return hex(outer.digest())
    }

    fun sha256(file: File): String = hex(digest(file, "SHA-256", 0L, file.length()))

    private fun digest(file: File, algorithm: String, start: Long, end: Long): ByteArray {
        val md = MessageDigest.getInstance(algorithm)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(1 shl 20)
            var left = end - start
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                md.update(buf, 0, n)
                left -= n
            }
        }
        return md.digest()
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private val DEB_MAGIC = "!<arch>\ndebian-binary".toByteArray(Charsets.US_ASCII)

    fun isDeb(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(DEB_MAGIC.size)
            raf.readFully(head)
            head.contentEquals(DEB_MAGIC)
        }
    }.getOrDefault(false)
}

internal class EpicClientInstaller(private val app: Context) : DownloadQueue.DownloadJob {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection? = null

    private class Part(val urls: List<String>, val file: File, val sha256: String?, val launcher: Boolean, var size: Long = -1L) {
        var mirror = 0
        var mismatch: String? = null
        val url: String get() = urls[mirror]
    }

    override fun run(handle: DownloadQueue.JobHandle): String? {
        handle.stage(DownloadStage.MANIFEST, app.getString(R.string.epic_client_checking))
        if (!LinuxRuntime.isInstalled(app)) throw IllegalStateException(app.getString(R.string.epic_client_no_runtime))
        val launcher = EpicClientSources.launcher() ?: throw IllegalStateException(app.getString(R.string.epic_client_unreachable))
        val runtime = EpicClientSources.runtime() ?: throw IllegalStateException(app.getString(R.string.epic_client_unreachable))
        if (cancelled.get()) return null
        val state = EpicClient.stateDir(app)
        val before = EpicClient.read(app)
        val needApp = !(before.version == launcher.version && File(state, "app/${launcher.version}/.droiddeck-ready").isFile)
        val needRuntime = !(before.runtimeVersion == runtime.version && File(state, "runtime/${runtime.version}/.droiddeck-ready").isFile)
        val dir = EpicClient.downloadsDir(app).apply { mkdirs() }
        val gles = EpicClientSources.gles()
        val appPart = if (needApp) Part(listOf(launcher.url), File(dir, "${launcher.version}-${EpicClientSources.LAUNCHER_FILE}"), null, true) else null
        val desktopParts = if (needApp) EpicClientSources.DESKTOP.map { Part(it.urls, File(dir, it.file), it.sha256, false, it.size) } else emptyList()
        val runtimePart = if (needRuntime) Part(listOf(runtime.url), File(dir, "steamrt4-${runtime.version}.tar.gz"), runtime.sha256, false) else null
        val glesPart = if (needRuntime) Part(listOf(gles.url), File(dir, EpicClientSources.GLES_FILE), gles.sha256, false) else null
        val parts = listOfNotNull(appPart) + desktopParts + listOfNotNull(runtimePart, glesPart)
        val keep = parts.flatMap { listOf(it.file.name, it.file.name + PARTIAL, it.file.name + TAG) }.toSet()
        dir.listFiles()?.forEach { if (it.name !in keep) it.deleteRecursively() }
        if (parts.isNotEmpty()) {
            parts.forEach { if (it.size <= 0) it.size = length(it.url) }
            val total = parts.sumOf { it.size.coerceAtLeast(0L) }
            val have = parts.sumOf { p -> if (p.file.isFile) p.file.length() else File(p.file.path + PARTIAL).length() }
            val needed = (total - have).coerceAtLeast(0L) + (if (needApp) APP_ROOM else 0L) + (if (needRuntime) RUNTIME_ROOM else 0L)
            val free = StoreInstallRoot.availableBytes(state)
            if (StoreInstallRoot.insufficientSpace(needed, free)) {
                throw IllegalStateException(app.getString(R.string.stores_storage_insufficient, formatBytes(needed), formatBytes(free)))
            }
            var base = 0L
            for (part in parts) {
                fetch(part, handle, base, total)
                if (cancelled.get()) return null
                base += part.size.coerceAtLeast(part.file.length())
            }
        }
        if (cancelled.get()) return null
        handle.stage(DownloadStage.INSTALL, app.getString(R.string.epic_client_unpacking))
        val argv = mutableListOf(EpicClient.PROGRAM, "install", "--app-version", launcher.version, "--runtime-version", runtime.version)
        fun guest(file: File) = EpicClient.guestPath(app, file) ?: throw IllegalStateException(app.getString(R.string.epic_client_unpack_failed, file.name))
        appPart?.let { argv += listOf("--app", guest(it.file)) }
        desktopParts.forEach { argv += listOf("--desktop", guest(it.file)) }
        if (runtimePart != null && glesPart != null) argv += listOf("--runtime", guest(runtimePart.file), "--gles", guest(glesPart.file))
        var answer: JSONObject? = null
        val code = GuestCommand.run(app, argv, fakeRoot = true, logName = "epic-client") { line ->
            if (line.startsWith("{")) runCatching { JSONObject(line) }.getOrNull()?.let { answer = it }
        }
        val result = answer
        if (code != 0 || result?.optBoolean("installed") != true) {
            val reason = result?.optString("reason")?.ifEmpty { null } ?: "exit $code"
            throw IllegalStateException(app.getString(R.string.epic_client_unpack_failed, reason))
        }
        parts.forEach { it.file.delete() }
        handle.log("Epic Games Launcher ${launcher.version} installed (runtime ${runtime.version})")
        EpicClient.installed(app)
        return state.absolutePath
    }

    override fun cancel(deleteFiles: Boolean) {
        cancelled.set(true)
        runCatching { connection?.disconnect() }
        if (deleteFiles) EpicClient.downloadsDir(app).deleteRecursively()
    }

    private fun length(url: String): Long {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 20_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", EpicClientSources.USER_AGENT)
                setRequestProperty("Accept-Encoding", "identity")
            }
            if (conn.responseCode == 200) conn.contentLengthLong else -1L
        } catch (e: Exception) {
            -1L
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun fetch(part: Part, handle: DownloadQueue.JobHandle, base: Long, total: Long) {
        if (part.file.isFile && (part.size <= 0 || part.file.length() == part.size)) {
            handle.progress(base + part.file.length(), total)
            return
        }
        part.file.delete()
        val partial = File(part.file.path + PARTIAL)
        val tag = File(part.file.path + TAG)
        var attempt = 0
        while (true) {
            if (cancelled.get()) return
            try {
                transfer(part, partial, tag, handle, base, total)
                if (cancelled.get()) return
                verify(part, partial, runCatching { tag.readText().trim() }.getOrDefault(""))
                if (!partial.renameTo(part.file)) throw IOException("could not keep ${part.file.name}")
                tag.delete()
                return
            } catch (e: IOException) {
                if (cancelled.get()) return
                attempt++
                handle.log("Epic Games Launcher: ${part.file.name} attempt $attempt: ${e.message ?: e.javaClass.simpleName}")
                if (attempt >= ATTEMPTS) throw IllegalStateException(app.getString(R.string.epic_client_download_failed, e.message ?: e.javaClass.simpleName))
                val until = System.currentTimeMillis() + (2_000L shl (attempt - 1)).coerceAtMost(30_000L)
                while (System.currentTimeMillis() < until && !cancelled.get()) Thread.sleep(100)
            }
        }
    }

    private fun transfer(part: Part, partial: File, tag: File, handle: DownloadQueue.JobHandle, base: Long, total: Long) {
        val have = if (partial.isFile) partial.length() else 0L
        if (part.size > 0 && have > part.size) { partial.delete(); tag.delete(); throw IOException("partial file larger than the download") }
        if (part.size > 0 && have == part.size) return
        val etag = runCatching { tag.readText().trim() }.getOrDefault("")
        val conn = (URL(part.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", EpicClientSources.USER_AGENT)
            setRequestProperty("Accept-Encoding", "identity")
            if (have > 0) {
                setRequestProperty("Range", "bytes=$have-")
                if (etag.isNotEmpty()) setRequestProperty("If-Range", etag)
            }
        }
        connection = conn
        try {
            val code = conn.responseCode
            val append = when (code) {
                206 -> {
                    val start = Regex("bytes (\\d+)-").find(conn.getHeaderField("Content-Range") ?: "")?.groupValues?.get(1)?.toLongOrNull()
                    if (start != have) throw IOException("the server resumed at the wrong place")
                    true
                }
                200 -> false
                416 -> { partial.delete(); tag.delete(); throw IOException("HTTP 416") }
                404, 410 -> {
                    if (part.mirror + 1 < part.urls.size) { part.mirror++; tag.delete() }
                    throw IOException("HTTP $code")
                }
                else -> throw IOException("HTTP $code")
            }
            val served = conn.getHeaderField("ETag")?.takeIf { it.isNotBlank() }
            if (served != null) tag.writeText(served) else if (!append) tag.delete()
            val length = conn.contentLengthLong
            if (length > 0) {
                val full = if (append) have + length else length
                if (part.size > 0 && full != part.size) throw IOException("the download changed size")
                part.size = full
            }
            var done = if (append) have else 0L
            conn.inputStream.use { input ->
                FileOutputStream(partial, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (!cancelled.get()) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        handle.progress(base + done, total)
                    }
                    out.fd.sync()
                }
            }
            if (!cancelled.get() && part.size > 0 && done != part.size) throw IOException("the download ended early")
        } finally {
            connection = null
            runCatching { conn.disconnect() }
        }
    }

    private fun verify(part: Part, file: File, etag: String) {
        if (part.size > 0 && file.length() != part.size) { file.delete(); throw IOException("wrong size") }
        part.sha256?.let { want ->
            if (EpicClientSources.sha256(file) != want) { file.delete(); throw IOException("checksum mismatch") }
        }
        if (part.launcher) {
            if (!EpicClientSources.isDeb(file)) { file.delete(); throw IOException("not a Debian package") }
            if (EpicClientSources.etagMatches(file, etag) == false) {
                val seen = EpicClientSources.sha256(file)
                if (part.mismatch != seen) {
                    part.mismatch = seen
                    file.delete()
                    throw IOException("checksum mismatch")
                }
            }
        }
    }

    private companion object {
        const val PARTIAL = ".part"
        const val TAG = ".etag"
        const val ATTEMPTS = 6
        const val APP_ROOM = 700L shl 20
        const val RUNTIME_ROOM = 560L shl 20
    }
}
