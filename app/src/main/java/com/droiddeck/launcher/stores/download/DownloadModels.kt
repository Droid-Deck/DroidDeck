package com.droiddeck.launcher.stores.download

import com.droiddeck.launcher.stores.Store

/**
 * The store-agnostic shape every store's download is projected into, so one queue and one
 * Downloads page serve all three. Ported from Bannerlator's download registry, trimmed to what
 * this app shows: a stage, one byte pair, speed and ETA, and the handles that drive it.
 */

/** Where a download is. The names are what the Downloads page prints over its stage strip. */
enum class DownloadStage { MANIFEST, DOWNLOAD, VERIFY, INSTALL, DONE }

/**
 * QUEUED / RUNNING / PAUSED are the active states (in memory only). INSTALLED is the terminal
 * success; FAILED and CANCELLED stay listed for the session so the page can offer a retry or a
 * dismissal.
 */
enum class DownloadState { QUEUED, RUNNING, PAUSED, INSTALLED, FAILED, CANCELLED }

data class DownloadEntry(
    val store: Store,
    val id: String,
    val name: String,
    val cover: String? = null,
    val state: DownloadState = DownloadState.QUEUED,
    val stage: DownloadStage = DownloadStage.MANIFEST,
    /** Bytes landed and the total, as the engine reports them (install size). */
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    /** Smoothed speed in bytes/s (0 = unknown) and seconds left (-1 = unknown). */
    val speedBps: Long = 0L,
    val etaSeconds: Long = -1L,
    /** The engine's current line ("Downloading: data.pak"), shown under the bar. */
    val detail: String = "",
    /** Where it was installed (INSTALLED) or why it failed (FAILED). */
    val installPath: String? = null,
    val error: String? = null,
    /** 1-based place in the queue while QUEUED, else 0. */
    val queuePosition: Int = 0,
    /** When the entry reached a terminal state, for ordering and ageing out. */
    val finishedAt: Long = 0L,
    val startedAt: Long = 0L,
    /** Where it is going: the install target's name ("Internal storage", the card's name). */
    val location: String = "",
    /**
     * The active stage's own progress outside Download (which runs on [bytesDone]/[bytesTotal]):
     * an amount ([stageDone] of [stageTotal], bytes or items) and, when it counts files, [stageItems]
     * of [stageItemsTotal]. Reset when the stage changes.
     */
    val stageDone: Long = 0L,
    val stageTotal: Long = 0L,
    val stageItems: Int = 0,
    val stageItemsTotal: Int = 0,
    /** What the game takes on disk, when the store has said (the download itself is [bytesTotal], often compressed). */
    val diskBytes: Long = 0L,
    /** Stages already passed, one bit per [DownloadStage] ordinal; stages can come back (Epic checks files before it fetches). */
    val stagesPassed: Int = 0,
) {
    fun passed(s: DownloadStage): Boolean = state == DownloadState.INSTALLED || (stagesPassed and (1 shl s.ordinal)) != 0

    val key: String get() = "${store.id}:$id"
    val isActive: Boolean get() = state == DownloadState.QUEUED || state == DownloadState.RUNNING || state == DownloadState.PAUSED
    /** The active stage's progress, 0..1, or -1 while it has nothing to count yet. */
    val stageFraction: Float get() = when {
        state == DownloadState.INSTALLED || stage == DownloadStage.DONE -> 1f
        stage == DownloadStage.DOWNLOAD -> if (bytesTotal > 0L) (bytesDone.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f) else -1f
        stageTotal > 0L -> (stageDone.toDouble() / stageTotal).toFloat().coerceIn(0f, 1f)
        stageItemsTotal > 0 -> (stageItems.toFloat() / stageItemsTotal).coerceIn(0f, 1f)
        else -> -1f
    }
    /** 0..1 for a single bar (a card, the game page): the active stage's progress. */
    val fraction: Float get() = stageFraction.coerceAtLeast(0f)
    val percent: Int get() = (fraction * 100).toInt()
}
