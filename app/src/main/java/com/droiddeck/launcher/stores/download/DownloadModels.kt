package com.droiddeck.launcher.stores.download

import com.droiddeck.launcher.stores.Store


enum class DownloadStage { MANIFEST, DOWNLOAD, VERIFY, INSTALL, DONE }

enum class DownloadState { QUEUED, RUNNING, PAUSED, INSTALLED, FAILED, CANCELLED }

data class DownloadEntry(
    val store: Store,
    val id: String,
    val name: String,
    val cover: String? = null,
    val state: DownloadState = DownloadState.QUEUED,
    val stage: DownloadStage = DownloadStage.MANIFEST,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val speedBps: Long = 0L,
    val etaSeconds: Long = -1L,
    val detail: String = "",
    val installPath: String? = null,
    val error: String? = null,
    val queuePosition: Int = 0,
    val finishedAt: Long = 0L,
    val startedAt: Long = 0L,
    val location: String = "",
    val stageDone: Long = 0L,
    val stageTotal: Long = 0L,
    val stageItems: Int = 0,
    val stageItemsTotal: Int = 0,
    val diskBytes: Long = 0L,
    val stagesPassed: Int = 0,
) {
    fun passed(s: DownloadStage): Boolean = state == DownloadState.INSTALLED || (stagesPassed and (1 shl s.ordinal)) != 0

    val key: String get() = "${store.id}:$id"
    val isActive: Boolean get() = state == DownloadState.QUEUED || state == DownloadState.RUNNING || state == DownloadState.PAUSED
    val stageFraction: Float get() = when {
        state == DownloadState.INSTALLED || stage == DownloadStage.DONE -> 1f
        stage == DownloadStage.DOWNLOAD -> if (bytesTotal > 0L) (bytesDone.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f) else -1f
        stageTotal > 0L -> (stageDone.toDouble() / stageTotal).toFloat().coerceIn(0f, 1f)
        stageItemsTotal > 0 -> (stageItems.toFloat() / stageItemsTotal).coerceIn(0f, 1f)
        else -> -1f
    }
    val fraction: Float get() = stageFraction.coerceAtLeast(0f)
    val percent: Int get() = (fraction * 100).toInt()
}
