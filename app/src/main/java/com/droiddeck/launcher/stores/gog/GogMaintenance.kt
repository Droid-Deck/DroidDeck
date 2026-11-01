package com.droiddeck.launcher.stores.gog

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import com.droiddeck.launcher.agent.AgentGuest
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.runtime.GuestCommand
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.stores.*
import com.droiddeck.launcher.stores.download.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object GogMaintenance {
    enum class State { UNKNOWN, CHECKING, CURRENT, AVAILABLE, FAILED }
    data class Check(val state: State, val version: String = "", val reason: String = "")
    data class Proposal(val folder: File, val before: GogInstallRecord, val target: GogInstallRecord,
        val operation: GogOperation, val downloadBytes: Long, val diskBytes: Long, val patch: GogOfflineDownload? = null, val deltas: JSONArray = JSONArray(), val fullFiles: Boolean = false)

    val checks = mutableStateMapOf<String, Check>()
    private val workers = Executors.newFixedThreadPool(2)
    private val checkedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var account = ""
    private val revision = java.util.concurrent.atomic.AtomicLong()
    private const val HELPER = "/usr/local/bin/droiddeck-gog-maintenance"
    const val OPERATION = "gogOperation"
    const val FOLDER = "gogFolder"

    fun reset() {
        revision.incrementAndGet()
        account = ""; checkedAt.clear()
        StoresState.post { checks.clear() }
    }

    fun checkInstalled(context: Context, games: List<InstalledStoreGame>) {
        val user = StoreAccounts.displayName(context, Store.GOG).orEmpty()
        if (user != account) { reset(); account = user }
        if (user.isBlank()) return
        games.filter { it.sidecar.store == Store.GOG && it.sidecar.isInstalled }.forEach { check(context, it) }
    }

    fun check(context: Context, game: InstalledStoreGame, force: Boolean = false) {
        val key = game.folder.absolutePath
        if (!force && System.currentTimeMillis() - (checkedAt[key] ?: 0) < 30 * 60_000) return
        if (!inFlight.add(key)) return
        val owner = revision.get()
        StoresState.post { checks[game.sidecar.id] = Check(State.CHECKING) }
        workers.execute {
            val result = runCatching {
                var old = GogInstallRecord.migrate(game.folder, game.sidecar)
                if (!old.verified || GogFiles.journal(game.folder).exists()) Check(State.UNKNOWN, reason = "Finish or recover the GOG maintenance operation first")
                else {
                    val token = GogContent.token(context)
                    if (old.source == "depot") {
                        val builds = GogContent.builds(old.id, token)
                        if (old.files.length() == 0 && old.build.isNotBlank()) GogBuild.select(builds, pinned = old.build)?.let { old = old.copy(branch = it.branch, generation = it.generation) }
                        val latest = GogBuild.select(builds, branch = old.branch)
                        when { latest == null -> Check(State.UNKNOWN, reason = "No build is available on the installed branch")
                            old.build.isBlank() -> Check(State.UNKNOWN, latest.version, "The installed build is unknown")
                            latest.id == old.build -> Check(State.CURRENT, old.version)
                            else -> Check(State.AVAILABLE, latest.version) }
                    } else {
                        val downloads = GogContent.product(old.id, token).getJSONObject("downloads")
                        val installer = offlineInstaller(downloads, old.language)
                        if (old.version.isBlank()) Check(State.UNKNOWN, installer.version, "The installed version is unknown")
                        else Check(if (old.version == installer.version) State.CURRENT else State.AVAILABLE, installer.version)
                    }
                }
            }.getOrElse { Check(State.FAILED, reason = it.message ?: "GOG update check failed") }
            if (revision.get() == owner && (result.state == State.CURRENT || result.state == State.AVAILABLE)) checkedAt[key] = System.currentTimeMillis()
            inFlight.remove(key)
            StoresState.post { if (revision.get() == owner) checks[game.sidecar.id] = result }
        }
    }

    fun offlinePatches(context: Context, game: InstalledStoreGame): List<GogOfflineDownload> =
        GogOfflineDownload.parse(GogContent.product(game.sidecar.id, GogContent.token(context)).getJSONObject("downloads"), "patches")

    internal fun offlineInstaller(downloads: JSONObject, language: String): GogOfflineDownload {
        val installers = GogOfflineDownload.parse(downloads, "installers")
        return installers.firstOrNull { it.language.equals(language, true) }
            ?: installers.firstOrNull { !it.language.equals("Neutral", true) && StoreLanguage.depotAccepted(arrayOf(it.language), language) }
            ?: installers.firstOrNull { it.language.equals("Neutral", true) }
            ?: error("No Windows offline installer is available for the installed language ($language)")
    }

    fun prepare(context: Context, game: InstalledStoreGame, operation: GogOperation, patch: GogOfflineDownload? = null, resume: Boolean = false): Proposal {
        val folder = game.folder.canonicalFile
        var old = GogInstallRecord.migrate(folder, game.sidecar)
        val journal = GogFiles.read(GogFiles.journal(folder))
        require(journal == null || journal.optString("folder") == folder.path) { "GOG recovery folder does not match" }
        if (journal != null && journal.optString("phase") in setOf("patch-starting", "patch-applying", "patch-uncertain") && operation != GogOperation.REFRESH) error("The patch was interrupted. Inspect the installer result or use a full offline refresh; it will not be rerun automatically.")
        require(journal == null || operation == GogOperation.REFRESH || journal.optString("operation") == operation.name) { "Resume the existing GOG operation or select a full offline refresh" }
        val token = GogContent.token(context)
        if (operation == GogOperation.PATCH) {
            if (old.source == "depot" && old.manifest.length() == 0 && old.build.isNotBlank()) {
                val exact = GogBuild.select(GogContent.builds(old.id, token), pinned = old.build)
                    ?: error("The installed GOG setup manifest is unavailable; use a full offline refresh")
                old = GogContent.snapshot(old, exact, token)
            }
            check(patch != null) { "Select a GOG patch" }
            if (resume) require(journal?.optString("downloadVersion") == patch.version) { "The selected patch changed; select it again in Offline patches" }
            val selected = GogContent.resolveDownload(patch, token)
            return Proposal(folder, old, old.copy(source = "offline", version = selected.version, verified = false), operation, selected.size,
                selected.size + folder.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.name != ".droiddeck-gog" }.filter { it.isFile }.sumOf { it.length() }, selected)
        }
        if (operation == GogOperation.REFRESH || old.source != "depot") {
            require(operation != GogOperation.REPAIR) { "This offline installation has no depot manifest. Use a full offline refresh to restore its files." }
            val download = GogContent.resolveDownload(offlineInstaller(GogContent.product(old.id, token).getJSONObject("downloads"), old.language), token)
            if (resume) require(journal?.optString("downloadId") == download.id && journal.optString("downloadVersion") == download.version) { "The previous offline installer is no longer available; select a new full offline refresh" }
            if (old.products.any { it != old.id }) {
                val pinned = journal?.optJSONObject("target")?.optString("build").orEmpty()
                val build = GogBuild.select(GogContent.builds(old.id, token), pinned = pinned, branch = old.branch)
                    ?: error("No depot build is available to preserve installed DLC during this refresh. Use Update instead.")
                val target = GogContent.snapshot(old, build, token)
                GogFiles.validate(target.files, folder)
                val native = GogNative.isAvailable()
                val remaining = GogEstimate.calculate(folder, old, target, JSONArray(), reuseChunks = native)
                return Proposal(folder, old, target, GogOperation.REFRESH, Math.addExact(download.size, remaining.download),
                    Math.addExact(Math.multiplyExact(download.size, 3), remaining.required), download, fullFiles = !native)
            }
            val target = old.copy(source = "offline", build = "", generation = 0, version = download.version, manifest = JSONObject(), files = JSONArray(), verified = true)
            return Proposal(folder, old, target, GogOperation.REFRESH, download.size, download.size * 3, download)
        }
        require(old.verified || operation != GogOperation.REPAIR) { "The patched installation must be reconciled before repair" }
        val pinned = journal?.optJSONObject("target")?.let(GogInstallRecord::parse)
        val builds = GogContent.builds(old.id, token)
        if (old.files.length() == 0 && old.build.isNotBlank()) GogBuild.select(builds, pinned = old.build)?.let { old = old.copy(branch = it.branch, generation = it.generation) }
        val build = GogBuild.select(builds,
            pinned = pinned?.build ?: if (operation == GogOperation.REPAIR) old.build.ifBlank { error("The installed GOG build is unknown; update it first") } else "",
            branch = pinned?.branch ?: old.branch) ?: error("The requested GOG build is no longer available")
        val target = GogContent.snapshot(old, build, token)
        GogFiles.validate(target.files, folder)
        var before = journal?.optJSONObject("before")?.let(GogInstallRecord::parse) ?: old
        if (before.files.length() == 0 && before.build.isNotBlank()) {
            val historical = GogBuild.select(builds, pinned = before.build)
            if (historical != null) before = runCatching { GogContent.snapshot(before, historical, token) }.getOrDefault(before)
        }
        val deltas = GogContent.patches(before, target, token)
        val native = GogNative.isAvailable()
        val size = GogEstimate.calculate(folder, before, target, deltas, reuseChunks = native)
        return Proposal(folder, before, target, operation, size.download, size.required, deltas = deltas, fullFiles = !native)
    }

    fun resume(context: Context, game: InstalledStoreGame): Proposal {
        val journal = GogFiles.read(GogFiles.journal(game.folder)) ?: error("GOG operation journal is missing")
        val operation = GogOperation.valueOf(journal.getString("operation"))
        val patch = if (operation == GogOperation.PATCH) offlinePatches(context, game)
            .firstOrNull { it.id == journal.optString("downloadId") } ?: error("The selected GOG patch is no longer available") else null
        return prepare(context, game, operation, patch, resume = true)
    }

    fun recoverPatch(context: Context, game: InstalledStoreGame) {
        val folder = game.folder.canonicalFile
        check(!DownloadQueue.isActive(game.key)) { "Wait for the GOG operation to finish" }
        val journal = GogFiles.read(GogFiles.journal(folder)) ?: error("GOG patch journal is missing")
        require(journal.optString("folder") == folder.path && journal.optString("operation") == GogOperation.PATCH.name)
        require(journal.optString("phase") in setOf("patch-starting", "patch-applying", "patch-uncertain"))
        RandomAccessFile(GogFiles.inside(folder, ".droiddeck-gog/lease"), "rw").use { lease ->
            val lock = runCatching { lease.channel.tryLock() }.getOrNull() ?: error("Close the patch installer before verifying its result")
            lock.use {
                ensureIdle(context, folder)
                val before = GogInstallRecord.parse(journal.getJSONObject("before"))
                val buildId = GogInstallRecord.authenticBuild(folder, before.id).ifBlank { before.build }
                check(buildId.isNotBlank()) { "The resulting build is unknown. Use a full offline refresh." }
                val token = GogContent.token(context)
                val build = GogBuild.select(GogContent.builds(before.id, token), pinned = buildId)
                    ?: error("The resulting build manifest is unavailable. Use a full offline refresh.")
                val target = GogContent.snapshot(before, build, token)
                GogFiles.validate(target.files, folder)
                check(target.files.objects().all { it.optBoolean("directory") || GogFiles.matches(folder, it) }) {
                    "The files do not match the reported build. Use a full offline refresh to recover."
                }
                val sidecar = StoreGameSidecar.parse(journal.getJSONObject("sidecar").toString()) ?: error("The original GOG settings are missing")
                target.write(folder)
                StoreInstalls.complete(context, folder, sidecar.copy(installVersion = target.build, state = StoreGameSidecar.STATE_INSTALLED))
                check(GogFiles.journal(folder).delete()) { "Could not finish the GOG recovery record" }
            }
        }
        checkedAt.remove(folder.path)
        DownloadQueue.dismiss(game.key)
        StoresState.notifyLibraryChanged(context)
        StoresState.post { checks.remove(game.sidecar.id) }
    }

    fun enqueue(context: Context, game: InstalledStoreGame, proposal: Proposal) {
        val app = context.applicationContext
        require(game.folder.canonicalFile == proposal.folder)
        check(!DownloadQueue.isActive("gog:${game.sidecar.id}")) { "GOG maintenance is already queued" }
        check(!StoreInstallRoot.insufficientSpace(proposal.diskBytes, StoreInstallRoot.availableBytes(proposal.folder))) { "Not enough free space for GOG maintenance" }
        val journal = JSONObject().put("schema", 1).put("folder", proposal.folder.path).put("operation", proposal.operation.name)
            .put("before", proposal.before.json()).put("target", proposal.target.json()).put("sidecar", game.sidecar.toJson()).put("phase", "download")
        proposal.patch?.let { journal.put("downloadId", it.id).put("downloadVersion", it.version) }
        GogFiles.write(GogFiles.journal(proposal.folder), journal)
        val item = CatalogItem(Store.GOG, game.sidecar.id, game.sidecar.title, game.sidecar.hero, tallImageUrl = game.sidecar.cover,
            extra = mapOf(OPERATION to proposal.operation.name, FOLDER to proposal.folder.path))
        SavedQueue.remember(app, item, null)
        enqueueJob(app, item, proposal)
    }

    private fun enqueueJob(context: Context, item: CatalogItem, proposal: Proposal) {
        DownloadQueue.enqueue(context, DownloadEntry(Store.GOG, item.id, item.title, cover = item.tallImageUrl,
            bytesTotal = proposal.downloadBytes, location = StoreInstallRoot.labelFor(context, proposal.folder))) { Job(context, proposal) }
    }

    fun restore(context: Context, item: CatalogItem) {
        val folder = File(item.extra[FOLDER] ?: error("Missing GOG recovery folder")).canonicalFile
        require(StoreInstallRoot.gameFolders(context).any { it.canonicalFile == folder }) { "GOG recovery folder is unavailable" }
        val sidecar = StoreGameSidecar.read(folder) ?: error("GOG installation record is missing")
        require(sidecar.store == Store.GOG && sidecar.id == item.id)
        val journal = GogFiles.read(GogFiles.journal(folder)) ?: error("GOG operation journal is missing")
        val operation = GogOperation.valueOf(journal.getString("operation"))
        if (journal.optString("phase") in setOf("patch-starting", "patch-applying", "patch-uncertain")) {
            DownloadQueue.recordFailed(context, DownloadEntry(Store.GOG, item.id, item.title), "The patch result needs recovery; open Offline patches")
            return
        }
        val game = InstalledStoreGame(sidecar, folder)
        SavedQueue.remember(context, item, null)
        enqueueJob(context, item, resume(context, game))
    }

    private class Job(val app: Context, val proposal: Proposal) : DownloadQueue.DownloadJob {
        val cancelled = AtomicBoolean(false)
        @Volatile var applyingPatch = false
        @Volatile private var running = false
        @Volatile private var deleteCaches = false
        override fun cancel(deleteFiles: Boolean) {
            if (applyingPatch) return
            deleteCaches = deleteCaches || deleteFiles
            cancelled.set(true)
            if (!running && deleteCaches) cleanCaches()
        }

        override fun run(handle: DownloadQueue.JobHandle): String? {
            running = true
            try { return perform(handle) }
            finally {
                running = false; applyingPatch = false; handle.interruptible(true)
                if (deleteCaches) cleanCaches()
            }
        }

        private fun cleanCaches() {
            for (name in listOf("stage", "downloads", "extracted")) {
                val root = GogFiles.inside(proposal.folder, ".droiddeck-gog/$name")
                if (root.exists()) java.nio.file.Files.walk(root.toPath()).use { paths ->
                    paths.sorted(java.util.Comparator.reverseOrder()).forEach { java.nio.file.Files.deleteIfExists(it) }
                }
            }
        }

        private fun perform(handle: DownloadQueue.JobHandle): String? {
            val folder = proposal.folder
            val journal = GogFiles.read(GogFiles.journal(folder)) ?: error("GOG operation journal is missing")
            val sidecar = StoreGameSidecar.parse(journal.getJSONObject("sidecar").toString()) ?: error("GOG previous installation is missing")
            if (proposal.operation == GogOperation.PATCH) {
                val result = GogOffline.apply(app, proposal, handle, cancelled, onApplying = { applyingPatch = true; handle.interruptible(false) })
                applyingPatch = false; handle.interruptible(true)
                if (!result || cancelled.get()) return null
            } else {
                RandomAccessFile(GogFiles.inside(folder, ".droiddeck-gog/lease"), "rw").use { lease ->
                    val lock = runCatching { lease.channel.tryLock() }.getOrNull() ?: error("Close the GOG game before updating it")
                    lock.use {
                        ensureIdle(app, folder)
                        sidecar.copy(state = StoreGameSidecar.STATE_UPDATING).write(folder)
                        GogFiles.write(GogFiles.journal(folder), journal.put("phase", "applying"))
                        if (proposal.operation == GogOperation.REFRESH) {
                            GogOffline.refresh(app, proposal, handle, cancelled)
                            if (!cancelled.get() && proposal.target.source == "depot") transfer(app, proposal, handle, cancelled)
                        } else transfer(app, proposal, handle, cancelled)
                        if (cancelled.get()) return null
                    }
                }
            }
            if (cancelled.get()) return null
            val target = GogInstallRecord.read(folder)?.takeIf { proposal.operation == GogOperation.PATCH || proposal.operation == GogOperation.REFRESH && proposal.target.source != "depot" } ?: proposal.target
            check(target.verified) { "The patch completed, but its resulting version could not be verified. Inspect the game or use a full offline refresh." }
            target.write(folder)
            StoreInstalls.complete(app, folder, sidecar.copy(installVersion = target.build.ifBlank { target.version }, state = StoreGameSidecar.STATE_INSTALLED))
            check(GogFiles.journal(folder).delete()) { "Could not finish the GOG recovery record" }
            runCatching { cleanCaches() }.onFailure { handle.log("gog: completed; could not remove expendable cache: ${it.message}") }
            checkedAt.remove(folder.path)
            StoresState.post { checks.remove(sidecar.id) }
            return folder.path
        }
    }

    private fun ensureIdle(context: Context, folder: File) {
        val game = AddedGames.single(context, folder.path) ?: return
        check(SessionState.gameId != (game.appId and 0xffffffffL).toString()) { "Close the GOG game before maintenance" }
        if (SessionState.running) {
            val id = (game.steamAppId?.toLong() ?: game.appId) and 0xffffffffL
            val guest = AddedGames.guestPath(context, folder) ?: error("The GOG folder is unavailable to Linux")
            if (SessionState.suspended || AgentGuest.hello(context) == null) {
                val root = AddedGames.guestPath(context, SessionPrefs.activeSteamRoot(context)) ?: error("The Steam folder is unavailable to Linux")
                val rc = GuestCommand.run(context, listOf("/usr/bin/env", "STEAM_COMPAT_CLIENT_INSTALL_PATH=$root", HELPER, "idle", guest, id.toString(), game.guestExe),
                    extraBinds = StoreInstallRoot.externalBindSpecs(context), logName = "gog-idle", onLine = StoresState::logLine)
                check(rc == 0) { "Close the GOG game before maintenance" }
                return
            }
            val request = JSONObject().put("kind", "exec").put("argv", JSONArray(listOf(HELPER, "idle", guest, id.toString(), game.guestExe))).put("timeout", 15)
            val result = AgentGuest.call(context, request, 20_000, trusted = true)
            check(result.optBoolean("ok") && result.optInt("exitCode", -1) == 0) { "Close the GOG game before maintenance" }
        }
    }

    private fun transfer(context: Context, proposal: Proposal, handle: DownloadQueue.JobHandle?, stop: AtomicBoolean, callback: GogDownloadManager.Callback? = null) {
        val token = GogContent.token(context)
        var patches = proposal.deltas
        var previousWire = 0L
        for (attempt in 0..4) {
            if (stop.get()) return
            val links = GogContent.links(proposal.target, GogContent.token(context))
            val patchLinks = if (patches.length() > 0) runCatching { GogContent.links(proposal.target, token, patches = true) }.getOrElse { patches = JSONArray(); JSONObject() } else JSONObject()
            val plan = JSONObject().put("files", proposal.target.files).put("oldFiles", proposal.before.files)
                .put("patches", patches).put("links", links).put("patchLinks", patchLinks).put("downloadBytes", proposal.downloadBytes)
            val optimized = GogNative.isAvailable()
            val remaining = GogEstimate.calculate(proposal.folder, proposal.before, proposal.target, patches, reuseChunks = optimized).required
            check(!StoreInstallRoot.insufficientSpace(remaining, StoreInstallRoot.availableBytes(proposal.folder))) { "Not enough free space for remaining GOG maintenance" }
            handle?.diskSize(remaining)
            if (!optimized) {
                try { GogFallback.transfer(proposal.folder, plan, handle, stop, callback); return }
                catch (e: Exception) {
                    if (stop.get()) return
                    if (attempt == 4 || Regex("HTTP (401|403|404|500)").find(e.message.orEmpty()) == null) throw e
                    handle?.log("gog: refreshing file-download links; verified replacements are retained")
                    continue
                }
            }
            val latch = CountDownLatch(1)
            var runWire = 0L
            var success = false; var expired = false; var failure = "GOG download failed"
            val listener = object : GogNative.Listener {
                override fun onProgress(bytesDone: Long, bytesTotal: Long, filesDone: Int, filesTotal: Int, file: String, fileBytes: Long, verified: Boolean) {
                    handle?.stage(if (verified) DownloadStage.VERIFY else DownloadStage.INSTALL)
                    handle?.stageProgress(filesDone.toLong(), filesTotal.toLong(), filesDone, filesTotal)
                    callback?.onStage(if (verified) "verify" else "install", filesDone.toLong(), filesTotal.toLong(), filesDone, filesTotal)
                }
                override fun onBytes(bytesDone: Long, bytesTotal: Long) {
                    runWire = bytesDone
                    handle?.progress(previousWire + bytesDone, (previousWire + bytesTotal).coerceAtLeast(previousWire + bytesDone))
                    callback?.onBytes(previousWire + bytesDone, (previousWire + bytesTotal).coerceAtLeast(previousWire + bytesDone), 0)
                }
                override fun onLog(line: String) { handle?.log(line); callback?.onLog(line) }
                override fun onMaintenance(networkBytes: Long, reusedBytes: Long, appliedBytes: Long) { handle?.maintenance(reusedBytes, appliedBytes) }
                override fun onComplete(ok: Boolean, cancelled: Boolean, linkExpiry: Boolean, error: String, bytesWritten: Long, filesDone: Int) {
                    success = ok; expired = linkExpiry; failure = error; latch.countDown()
                }
            }
            val native = GogNative.start(2, arrayOf(plan.toString()), emptyArray(), proposal.folder.path, emptyArray(), "", 1, 1, false, "gog maintenance", listener)
            check(native != 0L) { "GOG native engine could not start" }
            try { while (!latch.await(200, TimeUnit.MILLISECONDS)) if (stop.get()) GogNative.cancel(native) }
            finally { GogNative.release(native) }
            if (stop.get()) return
            if (success) {
                for (file in proposal.target.files.objects()) if (!file.optBoolean("directory") && file.optString("md5").isBlank() && file.optString("sha256").isBlank())
                    file.put("sha256", GogFiles.hash(GogFiles.inside(proposal.folder, file.getString("path")), "SHA-256"))
                return
            }
            if (!expired || attempt == 4) error(failure)
            previousWire += runWire
            handle?.log("gog: refreshing expired download links; validated files and chunks are retained")
        }
    }

    @JvmStatic
    fun installContent(context: Context, game: GogGame, folder: File, builds: JSONObject, language: String,
        callback: GogDownloadManager.Callback, cancelled: AtomicBoolean): GogDownloadManager.Result? {
        val build = GogBuild.select(builds) ?: return null
        folder.mkdirs()
        val old = GogInstallRecord(game.gameId, "depot", language = language)
        val target = GogContent.snapshot(old, build, GogContent.token(context))
        GogFiles.validate(target.files, folder)
        val bytes = target.files.objects().sumOf { it.optJSONArray("chunks")?.objects()?.sumOf { c -> c.optLong("compressedSize") }?.takeIf { n -> n > 0 } ?: it.optLong("size") }
        val disk = target.files.objects().sumOf { it.optLong("size") }
        check(!StoreInstallRoot.insufficientSpace(disk, StoreInstallRoot.availableBytes(folder))) { "Not enough free space for GOG installation" }
        callback.onSizes(bytes, disk)
        transfer(context, Proposal(folder, old, target, GogOperation.UPDATE, bytes, disk), null, cancelled, callback)
        if (cancelled.get()) return null
        target.write(folder)
        val result = GogDownloadManager.Result(GogDownloadManager.pickExe(folder, null, game.title), build.id, disk)
        result.language = target.language
        result.dependencies = target.manifest.optJSONArray("dependencies")?.strings().orEmpty()
        result.dependenciesRead = true
        return result
    }

    @JvmStatic
    fun captureDepot(id: String, folder: File, build: String, buildsJson: String, manifest: JSONObject, depots: JSONArray, language: String) {
        val selected = GogBuild.select(JSONObject(buildsJson), pinned = build)
        val files = JSONArray()
        for (depot in depots.strings()) for (item in JSONObject(depot).getJSONObject("depot").getJSONArray("items").objects()) {
            val chunks = item.optJSONArray("chunks") ?: continue
            files.put(JSONObject().put("path", item.getString("path").replace('\\', '/')).put("size", chunks.objects().sumOf { it.optLong("size") })
                .put("md5", item.optString("md5")).put("chunks", chunks).put("product", id))
        }
        GogFiles.validate(files, folder)
        GogInstallRecord(id, "depot", 2, build, selected?.version.orEmpty(), selected?.branch.orEmpty(), language,
            manifest = GogContent.sanitize(manifest), files = files).write(folder)
    }

    @JvmStatic
    fun captureOffline(id: String, folder: File, downloads: JSONObject) {
        val selected = GogOfflineDownload.parse(downloads, "installers").firstOrNull() ?: return
        GogInstallRecord(id, "offline", version = selected.version, language = selected.language).write(folder)
    }
}
