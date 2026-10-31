package com.droiddeck.launcher.stores.gamejolt

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreArchiveUnpacker
import com.droiddeck.launcher.stores.StoreExe
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoreInstalls
import com.droiddeck.launcher.stores.StoreNet
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadStage
import com.droiddeck.launcher.stores.itch.ItchArchives
import com.droiddeck.launcher.stores.itch.ItchStaging
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

internal class GameJoltInstaller(
    private val app: Context,
    private val item: CatalogItem,
    private val folder: File,
) : DownloadQueue.DownloadJob {
    private val cancelled = AtomicBoolean(false)
    private var work: File = folder
    private var updating = false
    private var previous: StoreGameSidecar? = null
    private val commit = Any()
    private val unpacker = StoreArchiveUnpacker(app, cancelled, "Game Jolt", R.string.stores_gamejolt_archive, R.string.stores_gamejolt_extract_failed, "gamejolt-unrar")

    override fun run(handle: DownloadQueue.JobHandle): String? {
        val cookies = GameJoltAuth.cookies(app)
        if (cookies.isBlank()) throw IllegalStateException(app.getString(R.string.stores_sync_sign_in))
        if (!item.isFree) throw IllegalStateException(app.getString(R.string.stores_gamejolt_not_owned))
        val gameId = item.extra["gameId"].orEmpty().ifEmpty { item.id }
        val before = StoreGameSidecar.read(folder)
        previous = before
        updating = before != null && (before.isInstalled || before.state == StoreGameSidecar.STATE_UPDATING)
        val resuming = before?.let { it.state == StoreGameSidecar.STATE_INSTALLING && it.id == item.id } == true
        StoreInstalls.begin(folder, Store.GAMEJOLT, item.id, item.title, item.imageUrl, item.imageUrl)
        handle.stage(DownloadStage.MANIFEST)
        val builds = GameJoltBuilds.load(app, gameId) ?: throw IllegalStateException(app.getString(R.string.stores_gamejolt_no_game))
        val chosen = item.extra["buildId"].orEmpty()
        val build = (if (chosen.isBlank()) GameJoltBuilds.preferred(builds) else GameJoltBuilds.byId(builds, chosen))?.takeIf { it.installable }
            ?: throw IllegalStateException(app.getString(R.string.stores_gamejolt_no_build))
        handle.log("Game Jolt: installing build ${build.id} (${build.filename}, ${build.size} bytes, platforms ${build.platforms.joinToString(",")}, launch ${build.executable.ifBlank { "none" }})")
        val scratch = StoreInstallRoot.scratchDir(app, Store.GAMEJOLT, item.id)
        scratch.mkdirs()
        val filename = build.filename.ifBlank { "download.bin" }
        val archive = File(scratch, filename)
        val exe = try {
            handle.stage(DownloadStage.DOWNLOAD)
            download(build, archive, handle)
            if (cancelled.get()) return null
            handle.stage(DownloadStage.INSTALL)
            install(build, archive, filename, handle, resuming)
        } catch (t: Throwable) {
            if (!cancelled.get()) dropCaches(scratch)
            throw t
        }
        if (exe == null) {
            if (cancelled.get()) return null
            dropCaches(scratch)
            throw IllegalStateException(app.getString(R.string.stores_gamejolt_no_exe))
        }
        archive.delete()
        StoreInstalls.deleteTree(scratch)
        val sidecar = StoreGameSidecar(
            store = Store.GAMEJOLT,
            id = item.id,
            title = item.title,
            exe = exe,
            args = previous?.args.orEmpty(),
            env = previous?.env.orEmpty(),
            installVersion = build.stamp,
            installedAt = System.currentTimeMillis(),
            cover = item.imageUrl,
            hero = item.imageUrl,
            extra = mapOf("gameId" to gameId, "buildId" to build.id, "packageId" to build.packageId),
            cloud = false,
        )
        StoreInstalls.complete(app, folder, sidecar) { done, steps -> handle.stageProgress(done.toLong(), steps.toLong()) }
        return folder.path
    }

    override fun cancel(deleteFiles: Boolean) {
        cancelled.set(true)
        if (deleteFiles) dropCaches(StoreInstallRoot.scratchDir(app, Store.GAMEJOLT, item.id))
    }

    private fun dropCaches(scratch: File) {
        if (!updating) {
            StoreInstalls.discard(app, folder, listOf(scratch))
            return
        }
        synchronized(commit) {
            StoreInstalls.deleteTree(scratch)
            ItchStaging.delete(File(folder, ItchStaging.DIR))
        }
    }

    private fun download(build: GameJoltBuild, target: File, handle: DownloadQueue.JobHandle) {
        val origin = File(target.path + ".from")
        val stamp = "${build.id} ${build.stamp} ${build.size}"
        if (!target.isFile || runCatching { origin.readText() }.getOrNull() != stamp) {
            target.delete()
            origin.writeText(stamp)
        }
        val expected = build.size
        if (expected > 0 && target.length() == expected) {
            handle.progress(expected, expected)
            return
        }
        for (attempt in 0..1) {
            val have = target.length()
            val url = GameJoltApi.downloadUrl(build.id, GameJoltAuth.cookies(app))
                ?: throw IllegalStateException(app.getString(R.string.stores_gamejolt_download_failed, 0))
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", StoreNet.BROWSER_UA)
                    setRequestProperty("Accept", "*/*")
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                }
                val code = conn.responseCode
                if (code == 416 && attempt == 0) {
                    target.delete()
                    continue
                }
                if (code !in 200..299) throw IllegalStateException(app.getString(R.string.stores_gamejolt_download_failed, code))
                val append = code == 206 && have > 0
                if (have > 0) handle.log(if (append) "Game Jolt: resuming at $have bytes" else "Game Jolt: server ignored the resume, starting over")
                var done = if (append) have else 0L
                val total = conn.contentLengthLong.takeIf { it > 0 }?.let { if (append) it + have else it } ?: expected
                handle.progress(done, total)
                val buffer = ByteArray(128 * 1024)
                conn.inputStream.use { input ->
                    FileOutputStream(target, append).use { out ->
                        while (true) {
                            if (cancelled.get()) return
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            done += read
                            handle.progress(done, total)
                        }
                    }
                }
                if (total > 0 && done < total) throw IllegalStateException(app.getString(R.string.stores_gamejolt_download_failed, code))
                return
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
    }

    private fun clearPartialInstall() {
        folder.listFiles()?.forEach {
            if (it.name == StoreGameSidecar.FILE_NAME) return@forEach
            if (it.isDirectory) StoreInstalls.deleteTree(it) else it.delete()
        }
    }

    private fun install(build: GameJoltBuild, archive: File, filename: String, handle: DownloadQueue.JobHandle, resuming: Boolean): String? {
        work = if (updating) File(folder, ItchStaging.DIR) else folder
        if (updating) ItchStaging.delete(work)
        work.mkdirs()
        unpacker.work = work
        if (resuming) clearPartialInstall()
        val exe = try {
            val kind = ItchArchives.kind(archive, filename)
            if (kind == "exe") archive.copyTo(File(work, filename), overwrite = true) else unpacker.unpack(archive, filename, handle)
            var picked = pick(build, filename)
            var passes = 0
            while (picked.isBlank() && passes < 2 && !cancelled.get()) {
                val inner = ItchArchives.inner(work) ?: break
                handle.log("Game Jolt: no exe yet, unpacking ${inner.name}")
                unpacker.unpack(inner, inner.name, handle)
                if (cancelled.get()) break
                inner.delete()
                picked = pick(build, inner.name.substringBeforeLast('.').takeIf { it.endsWith(".exe", ignoreCase = true) })
                passes++
            }
            picked
        } catch (t: Throwable) {
            if (updating) ItchStaging.delete(work)
            throw t
        }
        if (exe.isBlank()) {
            handle.log("Game Jolt: no exe found in ${folder.name}")
            if (updating) ItchStaging.delete(work)
            return null
        }
        if (updating) synchronized(commit) {
            if (cancelled.get()) {
                ItchStaging.delete(work)
                return null
            }
            try {
                StoreInstalls.markUpdating(folder)
            } catch (t: Throwable) {
                ItchStaging.delete(work)
                throw t
            }
            ItchStaging.merge(work, folder)
            ItchStaging.delete(work)
        }
        return exe
    }

    private fun pick(build: GameJoltBuild, hint: String?): String {
        if (build.executable.isNotBlank()) {
            val declared = File(work, build.executable.trimStart('/'))
            val inside = runCatching { declared.canonicalPath.startsWith(work.canonicalPath + File.separator) }.getOrDefault(false)
            if (inside && declared.isFile) return declared.relativeTo(work).path
        }
        return StoreExe.pick(work, hint?.ifBlank { null } ?: build.filename.substringBeforeLast('.').ifBlank { null }, item.title)
    }
}
