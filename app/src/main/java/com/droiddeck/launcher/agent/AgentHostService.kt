package com.droiddeck.launcher.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.droiddeck.launcher.FrontEndActivity
import com.droiddeck.launcher.R

/** Keeps the app process alive while the agent works, and says what it is doing, with a way to stop it. */
class AgentHostService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (AgentHost.lab?.running == true) AgentHost.cancelLabBuild(this)
            AgentHost.stop(this)
            return START_NOT_STICKY
        }
        awaiting = (awaiting - 1).coerceAtLeast(0)
        startForeground(NOTIFICATION_ID, build(this))
        if (!started && awaiting == 0) stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "agent"
        private const val NOTIFICATION_ID = 4107
        private const val ACTION_STOP = "com.droiddeck.launcher.agent.STOP"
        private var lastText: String? = null
        @Volatile private var started = false
        /**
         * startForegroundService calls not yet answered by onStartCommand. Stopping the service
         * before it calls startForeground crashes the app, so it then stops itself instead.
         */
        private var awaiting = 0
        /** Setup steps that keep the app alive while they run, besides the agent's turn. */
        private var setupSteps = 0

        fun start(context: Context) {
            lastText = null
            started = true
            runCatching { context.startForegroundService(Intent(context, AgentHostService::class.java)) }
                .onSuccess { awaiting++ }
        }

        fun stop(context: Context) {
            if (setupSteps > 0) return
            started = false
            if (awaiting == 0) context.stopService(Intent(context, AgentHostService::class.java))
        }

        fun setupStarted(context: Context) {
            setupSteps++
            start(context)
        }

        fun setupEnded(context: Context) {
            setupSteps = (setupSteps - 1).coerceAtLeast(0)
            if (AgentHost.state != AgentHost.State.WORKING) stop(context)
        }

        /** Refreshes the notification with the current step; unchanged text is not re-posted. */
        fun update(context: Context) {
            if (!started) return
            val text = describe(context)
            if (text == lastText) return
            lastText = text
            context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, build(context))
        }

        private fun describe(context: Context): String {
            val lab = AgentHost.lab?.takeIf { it.running }
            if (lab != null) return context.getString(R.string.agent_lab_progress, lab.permille / 10, minutes(lab.etaSeconds.toLong().coerceAtLeast(0)))
            if (AgentHost.state != AgentHost.State.WORKING) return context.getString(R.string.agent_setting_up)
            val tool = AgentHost.running()
            val elapsed = minutes((SystemClock.elapsedRealtime() - AgentHost.turnStarted) / 1000)
            return if (tool != null) "${tool.tool} · ${tool.detail}".take(120) + " · $elapsed" else context.getString(R.string.agent_thinking_for, elapsed)
        }

        private fun minutes(seconds: Long) = if (seconds < 60) "${seconds.coerceAtLeast(0)}s" else "${seconds / 60}m ${seconds % 60}s"

        private fun build(context: Context): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.agent_channel_name),
                NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
            val open = PendingIntent.getActivity(context, 0,
                Intent(context, FrontEndActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(com.droiddeck.launcher.stores.download.StoreDownloadService.EXTRA_NAV, "agent"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(context, 1, Intent(context, AgentHostService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val lab = AgentHost.lab?.takeIf { it.running }
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_session)
                .setContentTitle(context.getString(when {
                    lab?.prepare == true -> R.string.agent_lab_preparing
                    lab != null -> R.string.agent_lab_building
                    else -> R.string.agent_working
                }))
                .setContentText(describe(context))
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, context.getString(R.string.agent_stop), stop).build())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .apply { if (lab != null) setProgress(1000, lab.permille, lab.stage != "building") }
                .setCategory(Notification.CATEGORY_PROGRESS)
                .build()
        }
    }
}
