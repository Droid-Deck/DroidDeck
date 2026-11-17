package com.droiddeck.launcher.stores

import com.droiddeck.launcher.session.SessionPrefs
import android.content.Context
import android.util.Log
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.frontend.LibraryCache
import com.droiddeck.launcher.frontend.Library
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object StoreInstalls {
    private const val TAG = "StoreInstalls"

    fun begin(folder: File, store: Store, id: String, title: String, cover: String?, hero: String?) {
        val before = StoreGameSidecar.read(folder)
        if (before?.isInstalled == true || before?.state == StoreGameSidecar.STATE_UPDATING) return
        StoreGameSidecar(store, id, title, exe = "", cover = cover, hero = hero, state = StoreGameSidecar.STATE_INSTALLING).write(folder)
    }

    @JvmStatic
    fun markUpdating(folder: File) {
        val before = StoreGameSidecar.read(folder) ?: return
        if (!before.isInstalled) return
        val stage = File.createTempFile(".droiddeck-update-", ".stage", folder)
        try {
            java.io.FileOutputStream(stage).use {
                it.write(before.copy(state = StoreGameSidecar.STATE_UPDATING, installVersion = "").toJson().toString(2).toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            val record = StoreGameSidecar.file(folder).toPath()
            try {
                Files.move(stage.toPath(), record, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(stage.toPath(), record, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            stage.delete()
        }
    }

    fun complete(context: Context, folder: File, sidecar: StoreGameSidecar, onStep: ((Int, Int) -> Unit)? = null) {
        val app = context.applicationContext
        onStep?.invoke(0, STEPS)
        val before = StoreGameSidecar.read(folder)
        val finished = StoreLaunch.writeLauncher(folder, sidecar.copy(state = StoreGameSidecar.STATE_INSTALLED, epic = before?.epic ?: sidecar.epic, cloud = before?.cloud ?: sidecar.cloud, achievements = before?.achievements ?: sidecar.achievements, logo = sidecar.logo ?: before?.logo))
        finished.write(folder)
        if (StoreGameSidecar.read(folder)?.isInstalled != true) throw java.io.IOException("the install record could not be written in ${folder.name}")
        onStep?.invoke(1, STEPS)
        try { StoreArt.fetchInto(folder, finished) } catch (e: Exception) { Log.w(TAG, "art for ${folder.name}: ${e.message}") }
        onStep?.invoke(2, STEPS)
        register(app)
        AddedGames.single(app, folder.path)?.let { com.droiddeck.launcher.frontend.AutoComponents.refreshLater(app, Library.addedGame(app, it)) }
        onStep?.invoke(STEPS, STEPS)
    }

    private const val STEPS = 3

    fun register(context: Context) {
        val app = context.applicationContext
        try {
            val games = AddedGames.scan(app)
            AddedGames.writeListing(app, games)
            val arch = SessionPrefs.activeSteamClientArch(app)
            LibraryCache.save(app, Library.launchableGames(app, added = games, arch = arch), arch == "x86_64")
            SteamLiveShortcuts.sync(app, games)
        } catch (e: Exception) {
            Log.w(TAG, "registration: ${e.message}")
        }
        com.droiddeck.launcher.stores.epic.EpicClient.libraryChanged(app)
    }

    fun uninstall(context: Context, game: InstalledStoreGame, progress: (Float) -> Unit = {}, removed: () -> Unit = {}) {
        val app = context.applicationContext
        val appId = SessionPrefs.addedGameStoredAppId(app, game.folder.path)
        val files = game.folder.walkBottomUp().onEnter { !Files.isSymbolicLink(it.toPath()) }.toList()
        val step = (files.size / 100).coerceAtLeast(1)
        files.forEachIndexed { i, f ->
            f.delete()
            if (i % step == 0) progress((i + 1).toFloat() / files.size)
        }
        deleteTree(game.folder)
        progress(1f)
        deleteTree(StoreInstallRoot.scratchDir(app, game.sidecar.store, game.sidecar.id))
        removed()
        register(app)
        if (appId != null) SteamLiveShortcuts.remove(app, appId)
        StoresState.logLine("uninstalled \"${game.sidecar.title}\"")
    }

    private val discarding = HashMap<String, Int>()

    fun isDiscarding(folder: File): Boolean = synchronized(discarding) { folder.absolutePath in discarding }

    internal fun beginDiscard(folder: File) { synchronized(discarding) { discarding.merge(folder.absolutePath, 1, Int::plus) } }

    internal fun endDiscard(folder: File) {
        synchronized(discarding) {
            discarding.computeIfPresent(folder.absolutePath) { _, n -> (n - 1).takeIf { it > 0 } }
            (discarding as Object).notifyAll()
        }
    }

    fun awaitDiscard(folder: File) {
        synchronized(discarding) { while (folder.absolutePath in discarding) (discarding as Object).wait() }
    }

    private val started = ThreadLocal<MutableList<Thread>?>()

    fun collectingDiscards(into: MutableList<Thread> = ArrayList(), block: () -> Unit): List<Thread> {
        val previous = started.get()
        started.set(into)
        try {
            block()
        } finally {
            started.set(previous)
            previous?.addAll(into)
        }
        return into
    }

    fun discard(context: Context, folder: File, caches: List<File>) {
        val app = context.applicationContext
        beginDiscard(folder)
        val worker = Thread({
            try {
                discardNow(app, folder, caches)
            } finally {
                endDiscard(folder)
            }
        }, "store-cancel-clean")
        started.get()?.add(worker)
        worker.start()
    }

    @androidx.annotation.VisibleForTesting
    internal var beforeDiscard: (File) -> Unit = {}

    private fun discardNow(app: Context, folder: File, caches: List<File>) {
        beforeDiscard(folder)
        val sidecar = StoreGameSidecar.read(folder)
        val keep = sidecar?.isInstalled == true || sidecar?.state == StoreGameSidecar.STATE_UPDATING
        caches.forEach { deleteTree(it) }
        if (!keep) deleteTree(folder)
        StoresState.logLine(if (keep) "removed update caches for ${folder.name}; verified file swaps are retained" else "removed ${folder.name}")
        StoresState.notifyLibraryChanged(app)
    }

    fun deleteTree(dir: File) {
        if (Files.isSymbolicLink(dir.toPath())) {
            dir.delete()
            return
        }
        dir.listFiles()?.forEach { if (it.isDirectory) deleteTree(it) else it.delete() }
        dir.delete()
    }
}
