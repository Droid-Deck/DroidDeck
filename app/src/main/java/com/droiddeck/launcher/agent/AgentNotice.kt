package com.droiddeck.launcher.agent

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.droiddeck.launcher.FrontEndActivity
import com.droiddeck.launcher.R
import com.droiddeck.launcher.ui.AgentPet

/** Tells the user a turn has ended when nothing on screen already does. */
internal object AgentNotice {
    private const val CHANNEL_ID = "agent_done"
    private const val NOTIFICATION_ID = 4108

    enum class Kind { DONE, FAILED, QUESTION }

    fun post(context: Context, kind: Kind, text: String) {
        if (onScreen(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.agent_done_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 2,
            Intent(context, FrontEndActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(com.droiddeck.launcher.stores.download.StoreDownloadService.EXTRA_NAV, "agent"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = context.getString(when (kind) {
            Kind.DONE -> R.string.agent_done
            Kind.FAILED -> R.string.agent_done_failed
            Kind.QUESTION -> R.string.agent_done_question
        })
        runCatching {
            manager.notify(NOTIFICATION_ID, Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_session)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .build())
        }
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /** The chat in a DroidDeck window in front, or the pet, which shows the reply in its own bubble. */
    private fun onScreen(context: Context): Boolean {
        if (AgentPet.floating) return true
        val state = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
        return AgentPet.agentOnScreen > 0 && state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}
