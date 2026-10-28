package com.droiddeck.launcher

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Shortcuts other apps asked to put on the home screen. The system keeps the pinned set per launcher. */
object HomePins {
    class Pinned(val info: ShortcutInfo, val icon: Bitmap?, val appLabel: String, val userSerial: Long) {
        val key get() = "pin:${info.`package`}/${info.id}" + if (userSerial != 0L) "#$userSerial" else ""
        val label get() = info.shortLabel?.toString()?.trim().orEmpty().ifEmpty { appLabel }
        val work get() = userSerial != 0L
    }

    var pinned by mutableStateOf<List<Pinned>>(emptyList())
        private set
    private val main = Handler(Looper.getMainLooper())
    private var listing = 0

    fun refresh(context: Context) {
        val app = context.applicationContext
        val seq = ++listing
        Thread({
            val list = runCatching { load(app) }.getOrDefault(emptyList())
            main.post { if (seq == listing) pinned = list }
        }, "home-pins").start()
    }

    private fun load(context: Context): List<Pinned> {
        if (!HomeApp.canUseShortcuts(context)) return emptyList()
        val launcher = context.getSystemService(LauncherApps::class.java)
        val users = context.getSystemService(UserManager::class.java)
        val pm = context.packageManager
        val own = Process.myUserHandle()
        val query = LauncherApps.ShortcutQuery().setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        return users.userProfiles.flatMap { user ->
            val serial = if (user == own) 0L else users.getSerialNumberForUser(user)
            runCatching { launcher.getShortcuts(query, user) }.getOrNull().orEmpty()
                .filter { it.isEnabled && it.isPinned }
                .map { info ->
                    val appLabel = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(info.`package`, 0)).toString() }
                        .getOrDefault(info.`package`)
                    Pinned(info, HomeApp.shortcutIcon(context, info), appLabel, serial)
                }
        }
    }

    fun accept(context: Context, request: LauncherApps.PinItemRequest): Boolean {
        val ok = runCatching { request.isValid && request.accept() }.getOrDefault(false)
        if (ok) refresh(context)
        return ok
    }

    fun unpin(context: Context, pinned: Pinned) {
        val info = pinned.info
        val launcher = context.getSystemService(LauncherApps::class.java)
        val query = LauncherApps.ShortcutQuery().setPackage(info.`package`).setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        val unpinned = runCatching {
            val current = checkNotNull(launcher.getShortcuts(query, info.userHandle))
            val keep = current.map { it.id } - info.id
            launcher.pinShortcuts(info.`package`, keep, info.userHandle)
        }.isSuccess
        if (!unpinned) return
        listing++
        this.pinned = this.pinned.filterNot { it.key == pinned.key }
    }
}
