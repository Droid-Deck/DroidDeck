package com.droiddeck.launcher.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.droiddeck.launcher.frontend.Soundtrack
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

object SoundtrackController {
    var playing by mutableStateOf(false)
        private set
    var currentId by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf("")
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeat by mutableStateOf(Player.REPEAT_MODE_OFF)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    var buffering by mutableStateOf(false)
        private set
    var volume by mutableStateOf(1f)
        private set

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)
    }

    private val disconnect = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            if (this@SoundtrackController.controller === controller) {
                this@SoundtrackController.controller = null
                future = null
                reset()
            }
        }
    }

    private fun reset() {
        playing = false
        currentId = null
        title = ""
        durationMs = 0L
        buffering = false
        volume = 1f
    }

    private fun sync(player: Player) {
        playing = player.isPlaying
        buffering = player.playbackState == Player.STATE_BUFFERING
        currentId = player.currentMediaItem?.mediaId?.takeIf { player.mediaItemCount > 0 }
        title = player.mediaMetadata.title?.toString().orEmpty()
        shuffle = player.shuffleModeEnabled
        repeat = player.repeatMode
        durationMs = player.duration.takeIf { it > 0 } ?: 0L
        volume = player.volume
    }

    private fun run(context: Context, block: (MediaController) -> Unit) {
        val live = controller
        if (live != null) { block(live); return }
        pending += block
        if (future != null) return
        val app = context.applicationContext
        val token = SessionToken(app, ComponentName(app, SoundtrackService::class.java))
        val built = MediaController.Builder(app, token).setListener(disconnect).buildAsync()
        future = built
        built.addListener({
            val ready = runCatching { built.get() }.getOrNull()
            if (ready == null) {
                future = null
                pending.clear()
                return@addListener
            }
            controller = ready
            ready.addListener(listener)
            sync(ready)
            val queued = pending.toList()
            pending.clear()
            queued.forEach { it(ready) }
        }, ContextCompat.getMainExecutor(app))
    }

    fun connect(context: Context) = run(context) { }

    fun position(): Long = controller?.currentPosition ?: 0L

    fun belongs(root: File?): Boolean {
        val id = currentId ?: return false
        return root != null && id.startsWith(root.path + "/")
    }

    private fun items(album: Soundtrack.Album, albumTitle: String): List<MediaItem> {
        val cover = album.cover?.let { Uri.fromFile(it) }
        return album.tracks.map { t ->
            MediaItem.Builder()
                .setMediaId(t.file.path)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(t.title)
                        .setArtist(t.artist.ifBlank { album.artist }.ifBlank { null })
                        .setAlbumTitle(albumTitle)
                        .setTrackNumber(t.number)
                        .setDiscNumber(t.disc.takeIf { it > 0 })
                        .setArtworkUri(cover)
                        .setIsPlayable(true)
                        .build(),
                )
                .build()
        }
    }

    fun play(context: Context, album: Soundtrack.Album, albumTitle: String, startId: String?, shuffled: Boolean = false) {
        val list = items(album, albumTitle)
        if (list.isEmpty()) return
        run(context) { c ->
            val start = list.indexOfFirst { it.mediaId == startId }.coerceAtLeast(0)
            c.setMediaItems(list, start, 0L)
            c.shuffleModeEnabled = shuffled
            c.prepare()
            c.play()
        }
    }

    fun toggle(context: Context, album: Soundtrack.Album, albumTitle: String, root: File?) {
        run(context) { c ->
            if (belongs(root) && c.mediaItemCount > 0) {
                if (c.isPlaying) c.pause() else c.play()
            } else play(context, album, albumTitle, album.tracks.firstOrNull()?.id)
        }
    }

    fun pause(context: Context) {
        val live = controller ?: return
        live.pause()
    }

    fun next(context: Context) = run(context) { it.seekToNext() }
    fun previous(context: Context) = run(context) { c -> if (c.currentPosition > 3000L) c.seekTo(0) else c.seekToPrevious() }
    fun seekTo(context: Context, ms: Long) = run(context) { it.seekTo(ms) }
    fun setShuffle(context: Context, on: Boolean) = run(context) { it.shuffleModeEnabled = on }
    fun cycleRepeat(context: Context) = run(context) {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    fun setVolume(context: Context, volume: Float) = run(context) { it.volume = volume.coerceIn(0f, 1f) }

    fun stopIf(context: Context, root: File?) {
        if (!belongs(root)) return
        run(context) { c ->
            c.stop()
            c.clearMediaItems()
        }
    }
}
