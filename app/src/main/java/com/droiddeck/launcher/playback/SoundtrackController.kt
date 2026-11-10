package com.droiddeck.launcher.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
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

    var sleepEndsAt by mutableLongStateOf(0L)
        private set
    var sleepTrackEnd by mutableStateOf(false)
        private set

    private var app: Context? = null
    private var activeRoot: String? = null
    private var savedId: String? = null
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
        if (!player.playWhenReady) sleepTrackEnd = false
        persistResume(player)
    }

    private fun persistResume(player: Player) {
        val context = app ?: return
        val id = currentId ?: return
        val root = activeRoot ?: SoundtrackSettings.resume(context)?.root?.takeIf { id.startsWith("$it/") }?.also { activeRoot = it } ?: return
        if (id != savedId || !player.isPlaying) {
            savedId = id
            SoundtrackSettings.saveResume(context, root, id, player.currentPosition)
        }
    }

    private fun applySettings(c: MediaController) {
        val context = app ?: return
        SoundtrackSettings.load(context)
        c.setPlaybackParameters(PlaybackParameters(SoundtrackSettings.speed, SoundtrackSettings.pitch))
        c.sendCustomCommand(SoundtrackService.SKIP_SILENCE, Bundle().apply { putBoolean(SoundtrackService.ARG_ON, SoundtrackSettings.skipSilence) })
        sendEqualizer(c)
    }

    private fun sendEqualizer(c: MediaController) {
        c.sendCustomCommand(
            SoundtrackService.EQUALIZER,
            Bundle().apply {
                putBoolean(SoundtrackService.ARG_ON, SoundtrackSettings.eqOn)
                putIntArray(SoundtrackService.ARG_BANDS, SoundtrackSettings.bands.toIntArray())
                putInt(SoundtrackService.ARG_BASS, SoundtrackSettings.bass)
            },
        )
    }

    private fun run(context: Context, block: (MediaController) -> Unit) {
        app = context.applicationContext
        val live = controller
        if (live != null) { block(live); return }
        pending += block
        if (future != null) return
        val app = context.applicationContext
        val token = SessionToken(app, ComponentName(app, SoundtrackService::class.java))
        val built = MediaController.Builder(app, token).setListener(disconnect)
            .setConnectionHints(Bundle().apply { putBoolean(SoundtrackService.HINT_OWNER, true) }).buildAsync()
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
            applySettings(ready)
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

    fun play(
        context: Context, album: Soundtrack.Album, albumTitle: String, startId: String?, shuffled: Boolean? = null,
        root: File? = null, startMs: Long = 0L,
    ) {
        val list = items(album, albumTitle)
        if (list.isEmpty()) return
        run(context) { c ->
            val start = list.indexOfFirst { it.mediaId == startId }.coerceAtLeast(0)
            root?.let { activeRoot = it.path }
            c.setMediaItems(list, start, startMs)
            shuffled?.let { c.shuffleModeEnabled = it }
            c.prepare()
            c.play()
        }
    }

    fun toggle(context: Context, album: Soundtrack.Album, albumTitle: String, root: File?) {
        run(context) { c ->
            if (belongs(root) && c.mediaItemCount > 0) {
                if (c.isPlaying) c.pause() else c.play()
            } else {
                val saved = root?.let { r -> SoundtrackSettings.resume(context)?.takeIf { it.root == r.path && album.tracks.any { t -> t.id == it.id } } }
                play(context, album, albumTitle, saved?.id ?: album.tracks.firstOrNull()?.id, root = root, startMs = saved?.positionMs ?: 0L)
            }
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

    fun setSpeed(context: Context, value: Float) {
        SoundtrackSettings.load(context)
        SoundtrackSettings.setSpeed(context, value)
        run(context) { it.setPlaybackParameters(PlaybackParameters(SoundtrackSettings.speed, SoundtrackSettings.pitch)) }
    }

    fun setPitch(context: Context, value: Float) {
        SoundtrackSettings.load(context)
        SoundtrackSettings.setPitch(context, value)
        run(context) { it.setPlaybackParameters(PlaybackParameters(SoundtrackSettings.speed, SoundtrackSettings.pitch)) }
    }

    fun setSkipSilence(context: Context, on: Boolean) {
        SoundtrackSettings.load(context)
        SoundtrackSettings.setSkipSilence(context, on)
        run(context) { it.sendCustomCommand(SoundtrackService.SKIP_SILENCE, Bundle().apply { putBoolean(SoundtrackService.ARG_ON, on) }) }
    }

    fun setEqualizer(context: Context, on: Boolean, bands: List<Int>, bass: Int) {
        SoundtrackSettings.load(context)
        SoundtrackSettings.setEqualizer(context, on, bands, bass)
        run(context) { sendEqualizer(it) }
    }

    fun setSleep(context: Context, minutes: Int) {
        sleepEndsAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        sleepTrackEnd = minutes < 0
        run(context) { it.sendCustomCommand(SoundtrackService.SLEEP, Bundle().apply { putInt(SoundtrackService.ARG_MINUTES, minutes) }) }
    }

    fun sleepRemainingMs(): Long {
        val left = sleepEndsAt - System.currentTimeMillis()
        if (sleepEndsAt > 0L && left <= 0L) sleepEndsAt = 0L
        return left.coerceAtLeast(0L)
    }

    fun stopIf(context: Context, root: File?) {
        if (!belongs(root)) return
        run(context) { c ->
            c.stop()
            c.clearMediaItems()
        }
    }
}
