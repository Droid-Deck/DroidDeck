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
    const val LAUNCHER_URL = "https://launcher-public-service-prod06.ol.epicgames.com/launcher/api/installer/download/EpicGamesLauncher-x86_64.AppImage"
    const val LAUNCHER_FILE = "EpicGamesLauncher-x86_64.AppImage"
    const val RUNTIME_BASE = "https://repo.steampowered.com/steamrt4/images/"
    const val RUNTIME_FILE = "com.valvesoftware.SteamRuntime.Platform-amd64,i386-steamrt4-runtime.tar.gz"
    const val GLES_URL = "https://deb.debian.org/debian/pool/main/libg/libglvnd/libgles2_1.7.0-1+b2_amd64.deb"
    const val GLES_FILE = "libgles2_1.7.0-1+b2_amd64.deb"
    const val GLES_SHA256 = "e12c18aa77939d122ee1dea520ba0440b97dc19200b7396e03dff03a3ece74c1"
    const val USER_AGENT = "DroidDeck"
    private val VERSION = Regex("[A-Za-z0-9._+-]{1,64}")
    private val SUM = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$")

    class Source(val url: String, val version: String, val sha256: String? = null)

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

    fun isAppImage(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(11)
            raf.readFully(head)
            head[0] == 0x7f.toByte() && head[1] == 'E'.code.toByte() && head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte() &&
                head[8] == 'A'.code.toByte() && head[9] == 'I'.code.toByte() && head[10] == 2.toByte()
        }
    }.getOrDefault(false)
}

internal class EpicClientInstaller(private val app: Context) : DownloadQueue.DownloadJob {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection? = null

    private class Part(val url: String, val file: File, val sha256: String?, val appImage: Boolean) {
        var size = -1L
        var mismatch: String? = null
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
        val parts = buildList {
            if (needApp) add(Part(launcher.url, File(dir, "${launcher.version}-${EpicClientSources.LAUNCHER_FILE}"), null, true))
            if (needRuntime) {
                add(Part(runtime.url, File(dir, "steamrt4-${runtime.version}.tar.gz"), runtime.sha256, false))
                add(Part(gles.url, File(dir, EpicClientSources.GLES_FILE), gles.sha256, false))
            }
        }
        val keep = parts.flatMap { listOf(it.file.name, it.file.name + PARTIAL, it.file.name + TAG) }.toSet()
        dir.listFiles()?.forEach { if (it.name !in keep) it.deleteRecursively() }
        if (parts.isNotEmpty()) {
            parts.forEach { it.size = length(it.url) }
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
        if (needApp) argv += listOf("--app", guest(parts[0].file))
        if (needRuntime) argv += listOf("--runtime", guest(parts[parts.size - 2].file), "--gles", guest(parts[parts.size - 1].file))
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
        if (part.appImage) {
            if (!EpicClientSources.isAppImage(file)) { file.delete(); throw IOException("not an AppImage") }
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
        const val APP_ROOM = 600L shl 20
        const val RUNTIME_ROOM = 560L shl 20
    }
}
