package com.droiddeck.launcher.stores.download

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.StoresState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One queue for every store's downloads, [parallel] of them running at a time (1..3, Setup and
 * the Downloads page), the rest waiting as data until a slot frees. A store hands in a
 * [DownloadJob] - its whole install: manifest, files, verify, post-install - and reports back
 * through the job's [JobHandle]; the queue owns the states, the registry row the UI draws and the
 * foreground service that keeps the process alive while anything runs.
 *
 * Pause for these engines is "stop now, keep the files": every store's install skips files that
 * are already complete and verified, so resuming is simply running the job again. Cancel removes
 * what was downloaded (the job's own cleanup) and the row.
 *
 * Thread-safe: one [lock] guards the table; jobs run on their own threads, outside it.
 */
object DownloadQueue {
    private const val TAG = "StoreDownloads"

    /** The work for one download. [run] blocks until it is over; [cancel] asks a running job to stop. */
    interface DownloadJob {
        /** Runs the install; returns the install folder's path on success, throws or returns null on failure. */
        fun run(handle: JobHandle): String?
        /** Stops a running job; [deleteFiles] false keeps what was fetched for a later resume. */
        fun cancel(deleteFiles: Boolean)
    }

    /** What a running job reports with. */
    class JobHandle internal constructor(val key: String, internal val cancelled: AtomicBoolean) {
        val isCancelled: Boolean get() = cancelled.get()
        /** Enters [stage] (a repeat of the current one only updates the line); a new stage starts its own count from zero. */
        fun stage(stage: DownloadStage, detail: String = "") = enter(key, stage, stripSpeed(detail))
        /**
         * The active stage's own count outside Download: an amount and, when it counts files, the
         * files. [bytes] = the amount is bytes written, so the stage gets its own rate and ETA.
         */
        fun stageProgress(done: Long, total: Long, items: Int = 0, itemsTotal: Int = 0, bytes: Boolean = false) = stageCount(key, done, total, items, itemsTotal, bytes)
        /** The game's size on disk, once the store has said. */
        fun diskSize(bytes: Long) = update(key) { if (bytes > 0) it.copy(diskBytes = bytes) else it }
        /**
         * Bytes so far (negative = unchanged), the total (<= 0 = unchanged), a line. [speedBps] is the
         * engine's own figure: a burst rate at the network side, which jumps while the write side
         * paces the bar, so it is not what the page shows - the queue measures the install rate
         * from the byte deltas itself ([measure]); the engine's figure stays in its log lines.
         */
        fun progress(bytesDone: Long, bytesTotal: Long, detail: String? = null, @Suppress("UNUSED_PARAMETER") speedBps: Long = -1L) = measure(key, bytesDone, bytesTotal, detail?.let(::stripSpeed))
        fun log(line: String) = StoresState.logLine(line)
    }

    /** "Downloading: data.pak  12.3 MB/s" → "Downloading: data.pak": the engine's burst rate is not shown twice. */
    private fun stripSpeed(s: String): String = s.replace(Regex("\\s+\\S+ [KMG]B/s\\s*$"), "").trimEnd()

    private fun enter(key: String, stage: DownloadStage, detail: String) {
        synchronized(lock) {
            val item = items[key] ?: return
            val moved = item.entry.stage != stage || item.entry.state != DownloadState.RUNNING
            transitionLocked(item, stage)
            item.entry = item.entry.copy(detail = detail.ifEmpty { item.entry.detail }, state = DownloadState.RUNNING)
            // A new stage shows at once; a new line within the same stage (one per file) waits its turn.
            publishLocked(progressOnly = !moved)
        }
    }

    /**
     * Moves an item to [stage] when it is elsewhere: the old stage is marked passed, the new one
     * counts from zero, and the measured speed is cleared - the next stage writes or checks, it
     * does not fetch, and a stale rate there would read as a download. Caller holds [lock].
     */
    private fun transitionLocked(item: Item, stage: DownloadStage) {
        val e = item.entry
        if (e.stage == stage) return
        item.speedAt = 0L; item.speedBytes = 0L; item.speedEwma = 0.0
        item.entry = e.copy(
            stage = stage, stagesPassed = e.stagesPassed or (1 shl e.stage.ordinal), speedBps = 0, etaSeconds = -1,
            stageDone = 0, stageTotal = 0, stageItems = 0, stageItemsTotal = 0,
        )
    }

    /** How far back the shown speed looks: a few seconds, so the figure settles instead of following each file. */
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

    /** One sample of the rate behind [done] (bytes so far in this stage), smoothed over [SPEED_WINDOW_MS]. Caller holds [lock]. */
    private fun sample(item: Item, done: Long): Long {
        val now = System.currentTimeMillis()
        if (item.speedAt == 0L) { item.speedAt = now; item.speedBytes = done; return item.speedEwma.toLong() }
        val dt = now - item.speedAt
        // Sampled no faster than every quarter second, as an exponential average over
        // SPEED_WINDOW_MS: one big file landing moves it, it does not define it.
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

    private class Item(var entry: DownloadEntry, val factory: () -> DownloadJob, var job: DownloadJob? = null, val cancelled: AtomicBoolean = AtomicBoolean(false), var pauseRequested: Boolean = false) {
        /** The shown speed's state: the last sample's clock and bytes, and the running average (bytes/s). */
        var speedAt = 0L
        var speedBytes = 0L
        var speedEwma = 0.0
    }

    private val lock = Any()
    private val items = LinkedHashMap<String, Item>()
    private var running = 0

    fun parallel(context: Context): Int = SessionPrefs.gameStoresParallel(context)

    /** Threads for a store's download pool: background priority, so the UI and its render thread come first. */
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

    /**
     * Queues a download, or starts it when a slot is free. A key already active is left alone; a
     * finished or failed row with the same key is replaced.
     */
    fun enqueue(context: Context, entry: DownloadEntry, factory: () -> DownloadJob) {
        val app = context.applicationContext
        synchronized(lock) {
            items[entry.key]?.let { if (it.entry.isActive) { Log.i(TAG, "${entry.key}: already queued"); return } }
            items.remove(entry.key)
            items[entry.key] = Item(entry.copy(state = DownloadState.QUEUED, stage = DownloadStage.MANIFEST, startedAt = System.currentTimeMillis()), factory)
            renumber()
        }
        StoresState.logLine("${entry.store.label}: queued \"${entry.name}\"" + if (entry.bytesTotal > 0) " (${com.droiddeck.launcher.stores.formatBytes(entry.bytesTotal)})" else "")
        publish(app)
        advance(app)
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
        synchronized(lock) {
            val item = items[key] ?: return
            if (item.entry.state != DownloadState.PAUSED && item.entry.state != DownloadState.FAILED) return
            items.remove(key)
            items[key] = Item(item.entry.copy(state = DownloadState.QUEUED, speedBps = 0, etaSeconds = -1, error = null, startedAt = System.currentTimeMillis()), item.factory)
            renumber()
            publishLocked()
        }
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
            // Never started: nothing is on disk, but the job knows where it would have gone.
            Thread({ runCatching { item.factory().cancel(deleteFiles = true) } }, "store-dl-cancel").start()
        }
        StoresState.logLine("cancelled \"${item.entry.name}\"")
        StoreDownloadService.finish(app, key)
        advance(app)
        // The row says Cancelled for a moment, then goes; the game reads Install again.
        Thread({ Thread.sleep(3000); synchronized(lock) { if (items[key]?.entry?.state == DownloadState.CANCELLED) { items.remove(key); publishLocked() } } }, "store-dl-cancelled").start()
    }

    /**
     * Clear on a failed or paused row: deletes what it kept exactly as Cancel does (the job's own
     * cleanup), then drops the row. An installed or cancelled row is only dropped.
     */
    fun clear(context: Context, key: String) {
        val item = synchronized(lock) { items[key] } ?: return
        if (item.entry.state == DownloadState.RUNNING || item.entry.state == DownloadState.QUEUED) return
        if (item.entry.state == DownloadState.FAILED || item.entry.state == DownloadState.PAUSED) {
            Thread({ runCatching { item.factory().cancel(deleteFiles = true) } }, "store-dl-clear").start()
        }
        synchronized(lock) { items.remove(key); publishLocked() }
        StoreDownloadService.finish(context.applicationContext, key)
    }

    /** Drops a finished, failed or cancelled row from the list. */
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

    /** At most this often a progress-only change reaches the UI; a state or stage change goes at once. */
    private const val PROGRESS_PUBLISH_MS = 250L
    private var lastPublishAt = 0L
    private var publishPending = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Newest first: what was started last sits on top, as the preview lists them. Caller holds
     * [lock]. Engines report per chunk or per file - hundreds of times a second at full speed - so
     * [progressOnly] changes are coalesced to [PROGRESS_PUBLISH_MS]; the UI then recomposes a few
     * times a second, not on every callback.
     */
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
        val list = items.values.map { it.entry }.sortedByDescending { it.startedAt }
        StoresState.post { StoresState.publishDownloads(list) }
    }

    /** Starts queued items while slots are free. */
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
        // Downloads run beside the UI, never ahead of it: background priority for this thread and
        // (through [workerFactory]) every pool a store's manager starts.
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        val handle = JobHandle(item.entry.key, item.cancelled)
        var result: String? = null
        var error: String? = null
        try {
            val job = item.factory()
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
                // The folder is on disk and registered: the Installed tab and the Games list follow.
                StoresState.notifyLibraryChanged(context)
            }
            DownloadState.FAILED -> StoresState.logLine("${item.entry.store.label}: \"${item.entry.name}\" failed: $error")
            DownloadState.CANCELLED -> StoresState.notifyLibraryChanged(context)
            else -> {}
        }
        StoreDownloadService.finish(context, item.entry.key)
        advance(context)
    }
}
