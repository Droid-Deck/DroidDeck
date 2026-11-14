package com.droiddeck.launcher.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.droiddeck.launcher.FrontEndActivity
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.download.StoreDownloadService

/** Checks the catalog in the background while a PR test build is followed, and says when that PR has a new build. */
class UpdateWatch : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread({
            runCatching { check(applicationContext) }
            jobFinished(params, false)
        }, "update-watch").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 41_230
        private const val NOTIFICATION_ID = 41_231
        private const val CHANNEL_ID = "test-builds"
        private const val PREFS = "app_updates"
        private const val KEY_SEEN = "watch_seen"
        private const val INTERVAL_MS = 60 * 60 * 1000L

        fun sync(context: Context, follow: AppUpdates.Follow, current: AppUpdates.Release? = null) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (follow.channel != AppUpdates.Channel.TEST) {
                scheduler.cancel(JOB_ID)
                return
            }
            current?.let { seen(context, it) }
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateWatch::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(INTERVAL_MS)
                .build()
            runCatching { scheduler.schedule(job) }
        }

        fun seen(context: Context, release: AppUpdates.Release) {
            prefs(context).edit().putString(KEY_SEEN, key(release)).apply()
        }

        private fun key(r: AppUpdates.Release) = "${r.pr}:${r.commit}:${r.versionCode}:${r.apk?.sha256.orEmpty()}"


        internal fun check(context: Context) {
            val catalog = AppUpdates.refresh(context)
            val follow = AppUpdates.follow(context, catalog)
            if (follow.channel != AppUpdates.Channel.TEST) return
            val release = AppUpdates.release(catalog, follow) ?: return
            if (prefs(context).getString(KEY_SEEN, null) == key(release)) return
            val me = AppUpdates.installed()
            val offer = AppUpdates.offer(catalog, follow, me)
            if (!me.updatable || AppUpdates.installBlock(release, me) != null ||
                (offer != AppUpdates.Offer.UPDATE && offer != AppUpdates.Offer.SWITCH)
            ) return
            if (post(context, release)) seen(context, release)
        }

        private fun post(context: Context, release: AppUpdates.Release): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.upd_watch_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
            if (!manager.areNotificationsEnabled() ||
                manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
            ) return false
            val open = PendingIntent.getActivity(
                context, NOTIFICATION_ID,
                Intent(context, FrontEndActivity::class.java).putExtra(StoreDownloadService.EXTRA_NAV, "updates")
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val text = context.getString(R.string.upd_watch_text, release.title.ifBlank { release.commit.take(7) })
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_session)
                .setContentTitle(context.getString(R.string.upd_watch_title, release.pr))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            return runCatching { manager.notify(NOTIFICATION_ID, notification) }.isSuccess
        }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
