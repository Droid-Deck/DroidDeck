package com.droiddeck.launcher.session

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.mutableStateListOf
import androidx.core.app.NotificationManagerCompat

data class BottomNotification(val key: String, val app: String, val title: String, val text: String, val postedAt: Long)

object BottomNotifications {
    val items = mutableStateListOf<BottomNotification>()

    fun granted(context: Context): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    internal fun keep(own: String, pkg: String, ongoing: Boolean, groupSummary: Boolean, title: String, text: String): Boolean =
        pkg != own && !ongoing && !groupSummary && (title.isNotBlank() || text.isNotBlank())

    internal fun add(list: MutableList<BottomNotification>, item: BottomNotification) {
        list.removeAll { it.key == item.key }
        list.add(0, item)
        while (list.size > MAX_ITEMS) list.removeAt(list.lastIndex)
    }

    fun clear() = items.clear()

    internal fun post(context: Context, sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val extras = n.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras?.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (!keep(context.packageName, sbn.packageName, sbn.isOngoing, (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0, title, text)) return
        val app = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        add(items, BottomNotification(sbn.key, app, title, text, sbn.postTime))
    }

    internal fun remove(key: String) {
        items.removeAll { it.key == key }
    }

    private const val MAX_ITEMS = 30
}

class BottomNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        BottomNotifications.clear()
        runCatching { activeNotifications }.getOrNull()?.sortedBy { it.postTime }?.forEach { BottomNotifications.post(this, it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = BottomNotifications.post(this, sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) = BottomNotifications.remove(sbn.key)

    override fun onListenerDisconnected() = BottomNotifications.clear()
}
