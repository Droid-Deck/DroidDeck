package com.droiddeck.launcher.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Process
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Soundtrack
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

class SoundtrackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        val sessionIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        session = MediaSession.Builder(this, player).setCallback(Resolver).apply { sessionIntent?.let { setSessionActivity(it) } }.build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider(this).apply { setSmallIcon(R.drawable.ic_soundtrack) })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) stopSelf()
    }

    override fun onDestroy() {
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        const val HINT_OWNER = "droiddeck.owner"
    }

    private object Resolver : MediaSession.Callback {
        private fun isOwner(controller: MediaSession.ControllerInfo) =
            controller.uid == Process.myUid() && controller.connectionHints.getBoolean(HINT_OWNER, false)

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            if (controller.uid == Process.myUid() || controller.isTrusted) {
                MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
            } else {
                MediaSession.ConnectionResult.reject()
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
