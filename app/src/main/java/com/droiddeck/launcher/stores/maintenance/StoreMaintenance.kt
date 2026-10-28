package com.droiddeck.launcher.stores.maintenance

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoreInstalls
import com.droiddeck.launcher.stores.StoreAccounts
import com.droiddeck.launcher.stores.StoresState
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

enum class MaintenanceAction { UPDATE, REPAIR, REFRESH, OPEN_CLIENT }

enum class MaintenanceCheckState { UNKNOWN, CHECKING, CURRENT, AVAILABLE, FAILED }

data class MaintenanceCheck(
    val state: MaintenanceCheckState = MaintenanceCheckState.UNKNOWN,
    val version: String = "",
    val reason: String = "",
)

enum class RecoveryState { NONE, RESUMABLE, NEEDS_ATTENTION }

data class MaintenanceRecovery(val state: RecoveryState = RecoveryState.NONE, val message: String = "")

data class MaintenanceProposal(
    val folder: File,
    val before: StoreGameSidecar,
    val targetLabel: String,
    val operation: MaintenanceAction,
    val downloadBytes: Long = UNKNOWN_BYTES,
    val diskBytes: Long = UNKNOWN_BYTES,
    val description: String = "",
    val target: JSONObject,
) {
    companion object {
        const val UNKNOWN_BYTES = -1L
    }
}

interface StoreMaintenanceAdapter {
    val store: Store
    fun capabilities(game: InstalledStoreGame): Set<MaintenanceAction>
    fun check(context: Context, game: InstalledStoreGame): MaintenanceCheck
    fun prepare(context: Context, game: InstalledStoreGame, action: MaintenanceAction): MaintenanceProposal
    fun enqueue(context: Context, game: InstalledStoreGame, proposal: MaintenanceProposal)
    fun resume(context: Context, game: InstalledStoreGame, operation: JSONObject)
    fun recovery(context: Context, game: InstalledStoreGame): MaintenanceRecovery = MaintenanceRecovery()
    fun openClient(context: Context, game: InstalledStoreGame) = Unit
    fun openClientLabel(game: InstalledStoreGame): String = "Open client"
}

object StoreMaintenance {
    const val OPERATION_KEY = "maintenanceOperation"
    const val FOLDER_KEY = "maintenanceFolder"
    private const val JOURNAL = "operation.json"
    private const val CHECK_INTERVAL_MS = 30 * 60_000L
    private val adapters = ConcurrentHashMap<Store, StoreMaintenanceAdapter>()
    private val workers = Executors.newFixedThreadPool(2)
    private val checkedAt = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap<String, Long>()
    private val revisions = ConcurrentHashMap<Store, Long>()
    private val accounts = ConcurrentHashMap<Store, String>()
    val checks = mutableStateMapOf<String, MaintenanceCheck>()

    fun register(adapter: StoreMaintenanceAdapter) {
        adapters[adapter.store] = adapter
    }

    fun adapter(store: Store): StoreMaintenanceAdapter? = adapters[store]

    fun adapter(game: InstalledStoreGame): StoreMaintenanceAdapter? = adapter(game.sidecar.store)

    fun check(game: InstalledStoreGame): MaintenanceCheck = checks[game.key] ?: MaintenanceCheck()

    fun checkInstalled(context: Context, games: List<InstalledStoreGame>) {
        games.forEach { game -> if (game.sidecar.isInstalled && adapters.containsKey(game.sidecar.store)) check(context, game) }
    }

    fun check(context: Context, game: InstalledStoreGame, force: Boolean = false) {
        val adapter = adapters[game.sidecar.store] ?: return
        val account = StoreAccounts.displayName(context, game.sidecar.store).orEmpty()
        val previous = accounts.put(game.sidecar.store, account)
        if (previous != null && previous != account) invalidate(game.sidecar.store)
        val key = "${game.sidecar.store.id}:${game.folder.canonicalPath}"
        if (!force && System.currentTimeMillis() - (checkedAt[key] ?: 0L) < CHECK_INTERVAL_MS) return
        val owner = revisions[game.sidecar.store] ?: 0L
        if (inFlight.putIfAbsent(key, owner) != null) return
        StoresState.post { if ((revisions[game.sidecar.store] ?: 0L) == owner) checks[game.key] = MaintenanceCheck(MaintenanceCheckState.CHECKING) }
        workers.execute {
            try {
                val result = runCatching { adapter.check(context.applicationContext, game) }
                    .getOrElse { MaintenanceCheck(MaintenanceCheckState.FAILED, reason = it.message ?: "${game.sidecar.store.label} update check failed") }
                if ((revisions[game.sidecar.store] ?: 0L) == owner && (result.state == MaintenanceCheckState.CURRENT || result.state == MaintenanceCheckState.AVAILABLE))
                    checkedAt[key] = System.currentTimeMillis()
                StoresState.post { if ((revisions[game.sidecar.store] ?: 0L) == owner) checks[game.key] = result }
            } finally { inFlight.remove(key, owner) }
        }
    }

    fun invalidate(store: Store) {
        revisions.compute(store) { _, revision -> (revision ?: 0L) + 1L }
        checkedAt.keys.removeIf { it.startsWith("${store.id}:") }
        inFlight.keys.removeIf { it.startsWith("${store.id}:") }
        StoresState.post { checks.keys.removeIf { it.startsWith("${store.id}:") } }
    }

    fun prepare(context: Context, game: InstalledStoreGame, action: MaintenanceAction): MaintenanceProposal {
        require(action in capabilities(game)) { "${game.sidecar.store.label} does not support ${action.name.lowercase()}" }
        require(action != MaintenanceAction.OPEN_CLIENT)
        return adapters[game.sidecar.store]?.prepare(context.applicationContext, game, action)
            ?: error("${game.sidecar.store.label} maintenance is unavailable")
    }

    fun capabilities(game: InstalledStoreGame): Set<MaintenanceAction> =
        adapters[game.sidecar.store]?.capabilities(game).orEmpty()

    fun enqueue(context: Context, game: InstalledStoreGame, proposal: MaintenanceProposal) {
        require(game.folder.canonicalFile == proposal.folder.canonicalFile)
        require(proposal.operation != MaintenanceAction.OPEN_CLIENT && proposal.operation in capabilities(game))
        val adapter = adapters[game.sidecar.store] ?: error("${game.sidecar.store.label} maintenance is unavailable")
        writeOperation(proposal, game)
        adapter.enqueue(context.applicationContext, game, proposal)
    }

    fun resume(context: Context, game: InstalledStoreGame) {
        requireInstalledFolder(context, game.folder)
        val adapter = adapters[game.sidecar.store] ?: error("${game.sidecar.store.label} maintenance is unavailable")
        val operation = readOperation(game.folder) ?: error("${game.sidecar.store.label} operation journal is missing")
        require(operation.optString("folder") == game.folder.canonicalPath && operation.optString("store") == game.sidecar.store.id && operation.optString("id") == game.sidecar.id)
        adapter.resume(context.applicationContext, game, operation)
    }

    fun openClient(context: Context, game: InstalledStoreGame) {
        require(MaintenanceAction.OPEN_CLIENT in capabilities(game))
        adapters[game.sidecar.store]?.openClient(context.applicationContext, game)
    }

    fun recovery(context: Context, game: InstalledStoreGame): MaintenanceRecovery {
        val adapterRecovery = try { adapters[game.sidecar.store]?.recovery(context.applicationContext, game) }
        catch (error: Exception) { return MaintenanceRecovery(RecoveryState.NEEDS_ATTENTION, error.message ?: "The maintenance journal could not be read") }
        if (adapterRecovery?.state != null && adapterRecovery.state != RecoveryState.NONE) return adapterRecovery
        val operation = try { readOperation(game.folder) }
        catch (error: Exception) { return MaintenanceRecovery(RecoveryState.NEEDS_ATTENTION, error.message ?: "The maintenance journal could not be read") }
        if (operation != null) {
            val state = if (operation.optString("phase") in setOf("queued", "download")) RecoveryState.RESUMABLE else RecoveryState.NEEDS_ATTENTION
            return MaintenanceRecovery(state, operation.optString("phase"))
        }
        if (!game.sidecar.isInstalled) return MaintenanceRecovery(RecoveryState.NEEDS_ATTENTION, "The installation did not finish")
        return adapterRecovery ?: MaintenanceRecovery()
    }

    fun operationFile(folder: File) = File(File(folder, ".droiddeck-maintenance"), JOURNAL)

    fun readOperation(folder: File): JSONObject? {
        val directory = File(folder, ".droiddeck-maintenance")
        val file = File(directory, JOURNAL)
        require(!Files.isSymbolicLink(directory.toPath()) && !Files.isSymbolicLink(file.toPath())) { "Maintenance journal is a symbolic link" }
        return file.takeIf { it.isFile }?.let {
            JSONObject(it.readText()).also { operation ->
                require(operation.optInt("schema") == 1) { "Unsupported store maintenance journal" }
                require(operation.optString("folder") == folder.canonicalPath) { "Maintenance journal folder does not match" }
            }
        }
    }

    fun clearOperation(folder: File) {
        val directory = File(folder, ".droiddeck-maintenance")
        val file = File(directory, JOURNAL)
        require(!Files.isSymbolicLink(directory.toPath()) && !Files.isSymbolicLink(file.toPath())) { "Maintenance journal is a symbolic link" }
        if (!file.exists()) return
        require(file.isFile) { "Maintenance journal is not a regular file" }
        val operation = readOperation(folder) ?: return
        require(operation.optString("phase") in setOf("queued", "download")) { "An applying maintenance operation must be recovered" }
        require(!Files.isSymbolicLink(directory.toPath()) && !Files.isSymbolicLink(file.toPath())) { "Maintenance journal is a symbolic link" }
        check(file.delete()) { "Could not clear the queued maintenance operation" }
    }

    fun <T> withApplyLease(game: InstalledStoreGame, block: () -> T): T {
        return beginApplyLease(game).use { block() }
    }

    fun beginApplyLease(game: InstalledStoreGame): AutoCloseable {
        val folder = game.folder.canonicalFile
        require(folder == game.folder.absoluteFile.canonicalFile)
        val operation = readOperation(folder) ?: error("${game.sidecar.store.label} operation journal is missing")
        require(operation.optString("folder") == folder.path && operation.optString("store") == game.sidecar.store.id && operation.optString("id") == game.sidecar.id)
        val directory = File(folder, ".droiddeck-maintenance")
        require(!Files.isSymbolicLink(directory.toPath())) { "Maintenance metadata directory is a symbolic link" }
        directory.mkdirs()
        val leaseFile = File(directory, "lease")
        require(!Files.isSymbolicLink(leaseFile.toPath())) { "Maintenance lease is a symbolic link" }
        val lease = RandomAccessFile(leaseFile, "rw")
        val lock = runCatching { lease.channel.tryLock() }.getOrNull() ?: run {
            lease.close()
            error("Close the game before maintenance")
        }
        try {
            val before = StoreGameSidecar.parse(operation.getJSONObject("before").toString())
                ?: error("The original ${game.sidecar.store.label} settings are missing")
            require(before.store == game.sidecar.store && before.id == game.sidecar.id)
            writeJson(operationFile(folder), operation.put("phase", "applying"))
            before.copy(state = StoreGameSidecar.STATE_UPDATING).write(folder)
        } catch (error: Throwable) {
            lock.close()
            lease.close()
            throw error
        }
        return AutoCloseable { lock.close(); lease.close() }
    }

    fun complete(
        context: Context,
        game: InstalledStoreGame,
        installVersion: String = game.sidecar.installVersion,
        updatedExtra: Map<String, String>? = null,
    ) {
        val folder = game.folder.canonicalFile
        val operation = readOperation(folder) ?: error("${game.sidecar.store.label} operation journal is missing")
        require(operation.optString("folder") == folder.path && operation.optString("store") == game.sidecar.store.id && operation.optString("id") == game.sidecar.id)
        require(operation.optString("phase") == "applying") { "The ${game.sidecar.store.label} operation was not applied" }
        val before = StoreGameSidecar.parse(operation.getJSONObject("before").toString())
            ?: error("The original ${game.sidecar.store.label} settings are missing")
        StoreInstalls.complete(context.applicationContext, folder,
            before.copy(installVersion = installVersion, extra = updatedExtra ?: before.extra, state = StoreGameSidecar.STATE_INSTALLED))
        check(operationFile(folder).delete()) { "Could not finish the ${game.sidecar.store.label} recovery record" }
        checkedAt.remove("${game.sidecar.store.id}:${folder.path}")
        StoresState.post { checks.remove(game.key) }
    }

    private fun writeOperation(proposal: MaintenanceProposal, game: InstalledStoreGame) {
        val folder = proposal.folder.canonicalFile
        require(proposal.before.store == game.sidecar.store && proposal.before.id == game.sidecar.id)
        val directory = File(folder, ".droiddeck-maintenance")
        require(!Files.isSymbolicLink(directory.toPath())) { "Maintenance metadata directory is a symbolic link" }
        directory.mkdirs()
        val file = File(directory, JOURNAL)
        require(!Files.isSymbolicLink(file.toPath())) { "Maintenance journal is a symbolic link" }
        require(!file.exists()) { "Resume or recover the existing ${game.sidecar.store.label} operation first" }
        val value = JSONObject().put("schema", 1).put("store", game.sidecar.store.id).put("id", game.sidecar.id)
            .put("folder", folder.path).put("operation", proposal.operation.name).put("phase", "queued")
            .put("before", proposal.before.toJson()).put("targetLabel", proposal.targetLabel)
            .put("downloadBytes", proposal.downloadBytes).put("diskBytes", proposal.diskBytes)
            .put("description", proposal.description).put("target", proposal.target)
        writeJson(file, value)
    }

    private fun writeJson(file: File, value: JSONObject) {
        require(!Files.isSymbolicLink(file.toPath())) { "Maintenance journal is a symbolic link" }
        file.parentFile?.mkdirs()
        val temp = File.createTempFile(".maintenance-", ".tmp", file.parentFile)
        try {
            FileOutputStream(temp).use { it.write(value.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
            try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temp.delete() }
    }

    fun restore(context: Context, item: CatalogItem) {
        if (item.store == Store.GOG && item.extra.containsKey("gogOperation")) {
            com.droiddeck.launcher.stores.gog.GogMaintenance.restore(context, item)
            return
        }
        if (adapters[item.store] == null) error("${item.store.label} maintenance is unavailable")
        val folder = item.extra["maintenanceFolder"] ?: item.extra["gogFolder"]
            ?: error("Missing ${item.store.label} recovery folder")
        requireInstalledFolder(context, File(folder))
        val installed = InstalledStoreGame(
            StoreGameSidecar.read(File(folder)) ?: error("${item.store.label} installation record is missing"),
            File(folder),
        )
        require(installed.sidecar.store == item.store && installed.sidecar.id == item.id)
        resume(context, installed)
    }

    private fun requireInstalledFolder(context: Context, folder: File) {
        val canonical = folder.canonicalFile
        require(StoreInstallRoot.gameFolders(context.applicationContext).any { it.canonicalFile == canonical }) {
            "Maintenance folder is outside the installed game roots"
        }
    }

    fun launchExcluded(game: InstalledStoreGame): Boolean {
        if (!game.sidecar.isInstalled) return true
        val gogJournal = if (game.sidecar.store == Store.GOG) File(game.folder, ".droiddeck-gog/operation.json") else null
        if (gogJournal?.isFile == true) {
            val phase = runCatching { JSONObject(gogJournal.readText()).optString("phase") }.getOrDefault("")
            if (phase != "download") return true
        }
        val queued = com.droiddeck.launcher.stores.download.DownloadQueue.isActive(game.key)
        val file = operationFile(game.folder)
        val journal = file.exists() && !runCatching { readOperation(game.folder)?.optString("phase") in setOf("queued", "download") }.getOrDefault(false)
        return queued || journal
    }
}
