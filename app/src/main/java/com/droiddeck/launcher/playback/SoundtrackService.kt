package com.droiddeck.launcher.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Soundtrack
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

class SoundtrackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var exo: ExoPlayer? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var eqOn = false
    private var eqBands = IntArray(0)
    private var eqBass = 0
    private var sleepAtTrackEnd = false
    private val handler = Handler(Looper.getMainLooper())
    private val sleepRunnable = Runnable { session?.player?.pause() }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        exo = player
        val sessionIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        player.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = refreshButtons()
            override fun onRepeatModeChanged(repeatMode: Int) = refreshButtons()
            override fun onAudioSessionIdChanged(audioSessionId: Int) = applyEffects()
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) sleepAtTrackEnd = false
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val ended = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                if (sleepAtTrackEnd && ended) {
                    sleepAtTrackEnd = false
                    player.pause()
                }
            }
        })
        session = MediaSession.Builder(this, player).setCallback(Resolver()).apply { sessionIntent?.let { setSessionActivity(it) } }.build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider(this).apply { setSmallIcon(R.drawable.ic_soundtrack) })
    }

    private fun buttons(player: Player): ImmutableList<CommandButton> = ImmutableList.of(
        CommandButton.Builder()
            .setDisplayName(getString(R.string.soundtracks_shuffle))
            .setIconResId(if (player.shuffleModeEnabled) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle_off)
            .setSessionCommand(SHUFFLE)
            .build(),
        CommandButton.Builder()
            .setDisplayName(
                getString(
                    when (player.repeatMode) {
                        Player.REPEAT_MODE_ALL -> R.string.soundtracks_repeat_all
                        Player.REPEAT_MODE_ONE -> R.string.soundtracks_repeat_one
                        else -> R.string.soundtracks_repeat_off
                    },
                ),
            )
            .setIconResId(
                when (player.repeatMode) {
                    Player.REPEAT_MODE_ALL -> R.drawable.ic_repeat_all
                    Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one
                    else -> R.drawable.ic_repeat_off
                },
            )
            .setSessionCommand(REPEAT)
            .build(),
    )

    private fun releaseEffects() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        equalizer = null
        bassBoost = null
    }

    private fun applyEffects() {
        releaseEffects()
        val id = exo?.audioSessionId ?: return
        if (!eqOn || id == C.AUDIO_SESSION_ID_UNSET) return
        runCatching {
            val eq = Equalizer(0, id)
            val range = eq.bandLevelRange
            for (band in 0 until minOf(eq.numberOfBands.toInt(), eqBands.size)) {
                eq.setBandLevel(band.toShort(), eqBands[band].coerceIn(range[0].toInt(), range[1].toInt()).toShort())
            }
            eq.enabled = true
            equalizer = eq
        }
        runCatching {
            val boost = BassBoost(0, id)
            if (boost.strengthSupported) boost.setStrength(eqBass.coerceIn(0, 1000).toShort())
            boost.enabled = eqBass > 0
            bassBoost = boost
        }
    }

    private fun setSleep(minutes: Int) {
        handler.removeCallbacks(sleepRunnable)
        sleepAtTrackEnd = false
        if (minutes > 0) handler.postDelayed(sleepRunnable, minutes * 60_000L)
        else if (minutes < 0) sleepAtTrackEnd = true
    }

    private fun refreshButtons() {
        val live = session ?: return
        live.setCustomLayout(buttons(live.player))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(sleepRunnable)
        releaseEffects()
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        const val HINT_OWNER = "droiddeck.owner"
        private val SHUFFLE = SessionCommand("droiddeck.shuffle", Bundle.EMPTY)
        private val REPEAT = SessionCommand("droiddeck.repeat", Bundle.EMPTY)
        val SKIP_SILENCE = SessionCommand("droiddeck.skipsilence", Bundle.EMPTY)
        val EQUALIZER = SessionCommand("droiddeck.equalizer", Bundle.EMPTY)
        val SLEEP = SessionCommand("droiddeck.sleep", Bundle.EMPTY)
        const val ARG_ON = "on"
        const val ARG_BANDS = "bands"
        const val ARG_BASS = "bass"
        const val ARG_MINUTES = "minutes"
    }

    private inner class Resolver : MediaSession.Callback {
        private fun isOwner(controller: MediaSession.ControllerInfo) =
            controller.uid == Process.myUid() && controller.connectionHints.getBoolean(HINT_OWNER, false)

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            if (controller.uid == Process.myUid() || controller.isTrusted) {
                MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(
                        MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(SHUFFLE).add(REPEAT).add(SKIP_SILENCE).add(EQUALIZER).add(SLEEP).build(),
                    )
                    .setCustomLayout(buttons(session.player))
                    .build()
            } else {
                MediaSession.ConnectionResult.reject()
            }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> {
            val player = session.player
            when (customCommand.customAction) {
                SHUFFLE.customAction -> player.shuffleModeEnabled = !player.shuffleModeEnabled
                REPEAT.customAction -> player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                SKIP_SILENCE.customAction, EQUALIZER.customAction, SLEEP.customAction -> {
                    if (!isOwner(controller)) return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED))
                    when (customCommand.customAction) {
                        SKIP_SILENCE.customAction -> exo?.skipSilenceEnabled = args.getBoolean(ARG_ON)
                        EQUALIZER.customAction -> {
                            eqOn = args.getBoolean(ARG_ON)
                            eqBands = args.getIntArray(ARG_BANDS) ?: IntArray(0)
                            eqBass = args.getInt(ARG_BASS)
                            applyEffects()
                        }
                        else -> setSleep(args.getInt(ARG_MINUTES))
                    }
                }
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            if (!isOwner(controller)) return Futures.immediateFailedFuture(SecurityException("Only DroidDeck can queue soundtrack files"))
            val resolved = mediaItems.mapNotNull { item ->
                val file = File(item.mediaId)
                if (Soundtrack.isAudio(file)) item.buildUpon().setUri(Uri.fromFile(file)).build() else null
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }
}
