package com.droiddeck.launcher.stores.download

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.StoresState
import java.util.concurrent.atomic.AtomicBoolean

object DownloadQueue {
    private const val TAG = "StoreDownloads"

    interface DownloadJob {
        fun run(handle: JobHandle): String?
        fun cancel(deleteFiles: Boolean)
    }

    class JobHandle internal constructor(val key: String, internal val cancelled: AtomicBoolean) {
        val isCancelled: Boolean get() = cancelled.get()
        fun stage(stage: DownloadStage, detail: String = "") = enter(key, stage, stripSpeed(detail))
        fun stageProgress(done: Long, total: Long, items: Int = 0, itemsTotal: Int = 0, bytes: Boolean = false) = stageCount(key, done, total, items, itemsTotal, bytes)
        fun diskSize(bytes: Long) = update(key) { if (bytes > 0) it.copy(diskBytes = bytes) else it }
        fun progress(bytesDone: Long, bytesTotal: Long, detail: String? = null, @Suppress("UNUSED_PARAMETER") speedBps: Long = -1L) = measure(key, bytesDone, bytesTotal, detail?.let(::stripSpeed))
        fun log(line: String) = StoresState.logLine(line)
    }

    private fun stripSpeed(s: String): String = s.replace(Regex("\\s+\\S+ [KMG]B/s\\s*$"), "").trimEnd()

    private fun enter(key: String, stage: DownloadStage, detail: String) {
        synchronized(lock) {
            val item = items[key] ?: return
            val moved = item.entry.stage != stage || item.entry.state != DownloadState.RUNNING
            transitionLocked(item, stage)
            item.entry = item.entry.copy(detail = detail.ifEmpty { item.entry.detail }, state = DownloadState.RUNNING)
            publishLocked(progressOnly = !moved)
        }
    }

    private fun transitionLocked(item: Item, stage: DownloadStage) {
        val e = item.entry
        if (e.stage == stage) return
        item.speedAt = 0L; item.speedBytes = 0L; item.speedEwma = 0.0
        item.entry = e.copy(
            stage = stage, stagesPassed = e.stagesPassed or (1 shl e.stage.ordinal), speedBps = 0, etaSeconds = -1,
            stageDone = 0, stageTotal = 0, stageItems = 0, stageItemsTotal = 0,
        )
    }

    private const val SPEED_WINDOW_MS = 3000.0

    private fun stageCount(key: String, done: Long, total: Long, files: Int, filesTotal: Int, bytes: Boolean) {
        synchronized(lock) {
            val item = items[key] ?: return
            val e = item.entry
            var speed = e.speedBps
            if (bytes) speed = sample(item, done)
            val eta = if (bytes && speed > 0 && total > done) (total - done) / speed else -1L
            item.entry = e.copy(stageDone = done, stageTotal = total, stageItems = files, stageItemsTotal = filesTotal, speedBps = if (bytes) speed else 0, etaSeconds = eta)
            publishLocked(progressOnly = true)
        }
    }

    private fun sample(item: Item, done: Long): Long {
        val now = System.currentTimeMillis()
        if (item.speedAt == 0L) { item.speedAt = now; item.speedBytes = done; return item.speedEwma.toLong() }
        val dt = now - item.speedAt
        if (dt >= 250) {
            val inst = (done - item.speedBytes).coerceAtLeast(0) * 1000.0 / dt
            val alpha = 1.0 - Math.exp(-dt / SPEED_WINDOW_MS)
            item.speedEwma = if (item.speedEwma <= 0.0) inst else item.speedEwma + (inst - item.speedEwma) * alpha
            item.speedAt = now; item.speedBytes = done
        }
        return item.speedEwma.toLong()
    }

    private fun measure(key: String, bytesDone: Long, bytesTotal: Long, detail: String?) {
        synchronized(lock) {
            val item = items[key] ?: return
            transitionLocked(item, DownloadStage.DOWNLOAD)
            val e = item.entry
            val done = if (bytesDone >= 0) bytesDone else e.bytesDone
            val total = if (bytesTotal > 0) bytesTotal else e.bytesTotal
            if (bytesDone >= 0) sample(item, done)
            val speed = item.speedEwma.toLong()
            val eta = if (speed > 0 && total > done) (total - done) / speed else -1L
            val moved = e.stage != DownloadStage.DOWNLOAD || e.state != DownloadState.RUNNING
            item.entry = e.copy(state = DownloadState.RUNNING, stage = DownloadStage.DOWNLOAD, bytesDone = done, bytesTotal = total, detail = detail ?: e.detail, speedBps = speed, etaSeconds = eta)
            publishLocked(progressOnly = !moved)
        }
    }

    private class Item(var entry: DownloadEntry, val factory: (() -> DownloadJob)? = null, val retry: (() -> Unit)? = null, var job: DownloadJob? = null, val cancelled: AtomicBoolean = AtomicBoolean(false), var pauseRequested: Boolean = false) {
        var speedAt = 0L
        var speedBytes = 0L
        var speedEwma = 0.0
    }

    private val lock = Any()
    private val items = LinkedHashMap<String, Item>()
    private val heldPaused = HashSet<String>()

    fun holdPaused(key: String) = synchronized(lock) { heldPaused.add(key) }
    private var running = 0

    fun parallel(context: Context): Int = SessionPrefs.gameStoresParallel(context)

    @JvmStatic
    fun workerFactory(name: String): java.util.concurrent.ThreadFactory {
        val n = java.util.concurrent.atomic.AtomicInteger()
        return java.util.concurrent.ThreadFactory { r ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, "$name-${n.incrementAndGet()}")
        }
    }

    fun setParallel(context: Context, count: Int) {
        SessionPrefs.setGameStoresParallel(context, count)
        advance(context.applicationContext)
    }

    fun entry(key: String): DownloadEntry? = synchronized(lock) { items[key]?.entry }

    fun isActive(key: String): Boolean = entry(key)?.isActive == true

    fun enqueue(context: Context, entry: DownloadEntry, factory: () -> DownloadJob) {
        val app = context.applicationContext
        synchronized(lock) {
            items[entry.key]?.let { if (it.entry.isActive) { Log.i(TAG, "${entry.key}: already queued"); return } }
            items.remove(entry.key)
            val state = if (heldPaused.remove(entry.key)) DownloadState.PAUSED else DownloadState.QUEUED
            items[entry.key] = Item(entry.copy(state = state, stage = DownloadStage.MANIFEST, startedAt = System.currentTimeMillis()), factory)
            renumber()
        }
        StoresState.logLine("${entry.store.label}: queued \"${entry.name}\"" + if (entry.bytesTotal > 0) " (${com.droiddeck.launcher.stores.formatBytes(entry.bytesTotal)})" else "")
        publish(app)
        advance(app)
    }

    /** Immediately records a failed download (e.g. from pre-flight storage check). */
    fun recordFailed(context: Context, entry: DownloadEntry, error: String, retry: (() -> Unit)? = null) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (items[entry.key]?.entry?.isActive == true) return
            items.remove(entry.key)
            heldPaused.remove(entry.key)
            items[entry.key] = Item(
                entry.copy(
                    state = DownloadState.FAILED,
                    error = error,
                    speedBps = 0,
                    etaSeconds = -1,
                    startedAt = now,
                    finishedAt = now
                ),
                retry = retry
            )
            renumber()
            publishLocked()
        }
        StoresState.logLine("${entry.store.label}: \"${entry.name}\" failed: $error")
        StoreDownloadService.ended(app, entry.copy(state = DownloadState.FAILED, error = error), installed = false, gamesKey = null)
    }

    fun pause(key: String) {
        val item = synchronized(lock) { items[key] } ?: return
        synchronized(lock) {
            if (item.entry.state == DownloadState.QUEUED) { item.entry = item.entry.copy(state = DownloadState.PAUSED, queuePosition = 0); renumber(); publishLocked(); return }
            if (item.entry.state != DownloadState.RUNNING) return
            item.pauseRequested = true
        }
        item.cancelled.set(true)
        item.job?.cancel(deleteFiles = false)
        StoresState.logLine("paused \"${item.entry.name}\"")
    }

    fun resume(context: Context, key: String) {
        val app = context.applicationContext
        val retry: (() -> Unit)?
        synchronized(lock) {
            val item = items[key] ?: return
            if (item.entry.state != DownloadState.PAUSED && item.entry.state != DownloadState.FAILED) return
            retry = item.retry
            if (retry == null && item.factory == null) return
            items.remove(key)
            if (retry == null) {
                items[key] = Item(item.entry.copy(state = DownloadState.QUEUED, speedBps = 0, etaSeconds = -1, error = null, startedAt = System.currentTimeMillis()), item.factory)
            }
            renumber()
            publishLocked()
        }
        // A preflight retry rechecks storage and enqueues the real backend, never a running placeholder.
        if (retry != null) { retry(); return }
        StoresState.logLine("resumed \"${entry(key)?.name}\"")
        advance(app)
    }

    fun retry(context: Context, key: String) = resume(context, key)

    fun cancel(context: Context, key: String) {
        val app = context.applicationContext
        val item: Item
        val wasRunning: Boolean
        synchronized(lock) {
            item = items[key] ?: return
            wasRunning = item.entry.state == DownloadState.RUNNING
            if (!wasRunning) {
                item.entry = item.entry.copy(state = DownloadState.CANCELLED, queuePosition = 0, finishedAt = System.currentTimeMillis())
                renumber()
                publishLocked()
            }
        }
        if (wasRunning) {
            item.cancelled.set(true)
            item.job?.cancel(deleteFiles = true)
        } else {
            Thread({ runCatching { item.factory?.invoke()?.cancel(deleteFiles = true) } }, "store-dl-cancel").start()
        }
        StoresState.logLine("cancelled \"${item.entry.name}\"")
        StoreDownloadService.finish(app, key)
        advance(app)
        Thread({ Thread.sleep(3000); synchronized(lock) { if (items[key]?.entry?.state == DownloadState.CANCELLED) { items.remove(key); publishLocked() } } }, "store-dl-cancelled").start()
    }

    fun clear(context: Context, key: String) {
        val item = synchronized(lock) { items[key] } ?: return
        if (item.entry.state == DownloadState.RUNNING || item.entry.state == DownloadState.QUEUED) return
        if (item.entry.state == DownloadState.FAILED || item.entry.state == DownloadState.PAUSED) {
            Thread({ runCatching { item.factory?.invoke()?.cancel(deleteFiles = true) } }, "store-dl-clear").start()
        }
        synchronized(lock) { items.remove(key); publishLocked() }
        StoreDownloadService.finish(context.applicationContext, key)
    }

    fun dismissFinished() {
        synchronized(lock) {
            items.entries.removeAll { !it.value.entry.isActive }
            publishLocked()
        }
    }

    fun dismiss(key: String) {
        synchronized(lock) {
            val item = items[key] ?: return
            if (item.entry.isActive) return
            items.remove(key)
            publishLocked()
        }
    }

    private fun renumber() {
        var pos = 1
        for (item in items.values) if (item.entry.state == DownloadState.QUEUED) item.entry = item.entry.copy(queuePosition = pos++)
    }

    private fun update(key: String, transform: (DownloadEntry) -> DownloadEntry) {
        synchronized(lock) {
            val item = items[key] ?: return
            item.entry = transform(item.entry)
            publishLocked()
        }
    }

    private fun publish(context: Context) { synchronized(lock) { publishLocked() }; StoreDownloadService.sync(context) }

    private const val PROGRESS_PUBLISH_MS = 250L
    private var lastPublishAt = 0L
    private var publishPending = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun publishLocked(progressOnly: Boolean = false) {
        val now = android.os.SystemClock.uptimeMillis()
        if (progressOnly) {
            val wait = lastPublishAt + PROGRESS_PUBLISH_MS - now
            if (wait > 0) {
                if (!publishPending) {
                    publishPending = true
                    main.postDelayed({ synchronized(lock) { publishPending = false; publishLocked() } }, wait)
                }
                return
            }
        }
        lastPublishAt = now
        if (!progressOnly) SavedQueue.save(items.values.map { it.entry })
        val list = items.values.map { it.entry }.sortedByDescending { it.startedAt }
        StoresState.post { StoresState.publishDownloads(list) }
    }

    private fun advance(context: Context) {
        while (true) {
            val next: Item
            synchronized(lock) {
                if (running >= parallel(context)) return
                next = items.values.firstOrNull { it.entry.state == DownloadState.QUEUED } ?: return
                running++
                next.entry = next.entry.copy(state = DownloadState.RUNNING, stage = DownloadStage.MANIFEST, queuePosition = 0)
                renumber()
                publishLocked()
            }
            StoreDownloadService.start(context)
            Thread({ runItem(context, next) }, "store-dl-${next.entry.key}").start()
        }
    }

    private fun runItem(context: Context, item: Item) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        val handle = JobHandle(item.entry.key, item.cancelled)
        var result: String? = null
        var error: String? = null
        try {
            val job = checkNotNull(item.factory).invoke()
            synchronized(lock) { item.job = job }
            StoresState.logLine("${item.entry.store.label}: fetching manifest for \"${item.entry.name}\"")
            result = job.run(handle)
            if (result == null && !item.cancelled.get()) error = "install failed"
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
            Log.w(TAG, "${item.entry.key} failed", t)
        }
        synchronized(lock) {
            running--
            val now = System.currentTimeMillis()
            item.entry = when {
                item.cancelled.get() && item.pauseRequested -> item.entry.copy(state = DownloadState.PAUSED, speedBps = 0, etaSeconds = -1)
                item.cancelled.get() -> item.entry.copy(state = DownloadState.CANCELLED, finishedAt = now, speedBps = 0, etaSeconds = -1)
                result != null -> item.entry.copy(state = DownloadState.INSTALLED, stage = DownloadStage.DONE, installPath = result, bytesDone = item.entry.bytesTotal, speedBps = 0, etaSeconds = -1, finishedAt = now)
                else -> item.entry.copy(state = DownloadState.FAILED, error = error, speedBps = 0, etaSeconds = -1, finishedAt = now)
            }
            item.job = null
            publishLocked()
        }
        when (item.entry.state) {
            DownloadState.INSTALLED -> {
                StoresState.logLine("installed \"${item.entry.name}\" → ${result}")
                StoresState.notifyLibraryChanged(context)
                val games = runCatching {
                    com.droiddeck.launcher.frontend.LibraryCache.load(context).firstOrNull { it.gameFiles?.absolutePath == result }?.let { "app:${it.appId}" }
                }.getOrNull()
                StoreDownloadService.ended(context, item.entry, installed = true, gamesKey = games)
            }
            DownloadState.FAILED -> {
                StoresState.logLine("${item.entry.store.label}: \"${item.entry.name}\" failed: $error")
                StoreDownloadService.ended(context, item.entry, installed = false, gamesKey = null)
            }
            DownloadState.CANCELLED -> StoresState.notifyLibraryChanged(context)
            else -> {}
        }
        StoreDownloadService.finish(context, item.entry.key)
        advance(context)
    }
}
