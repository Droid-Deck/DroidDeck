package com.droiddeck.launcher.frontend

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.widget.Toast
import com.droiddeck.launcher.FrontEndActivity
import com.droiddeck.launcher.LauncherIcon
import com.droiddeck.launcher.R

object SoundtrackShortcuts {
    private val linkPattern = Regex("droiddeck://soundtrack/(-?[0-9]{1,11})")

    fun uri(appId: Int): String = "droiddeck://soundtrack/$appId"

    fun parse(link: String?): Int? = link?.let { linkPattern.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }

    fun read(intent: Intent): Int? = if (intent.action == Intent.ACTION_VIEW) parse(intent.dataString) else null

    private fun info(context: Context, game: Library.SteamGame): ShortcutInfo {
        val name = game.name.ifBlank { context.getString(R.string.app_name) }
        val icon = GameShortcuts.iconBitmap(game)?.let(Icon::createWithBitmap) ?: Icon.createWithResource(context, R.mipmap.ic_launcher)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri(game.appId)), context, FrontEndActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return ShortcutInfo.Builder(context, "soundtrack:${game.appId}")
            .setActivity(LauncherIcon.activity(context))
            .setShortLabel(name.take(40)).setLongLabel(name).setIcon(icon).setIntent(intent).build()
    }

    fun pin(activity: Activity, game: Library.SteamGame) {
        val manager = activity.getSystemService(ShortcutManager::class.java)
        val requested = runCatching {
            manager.isRequestPinShortcutSupported && manager.requestPinShortcut(info(activity, game), null)
        }.getOrDefault(false)
        if (!requested) Toast.makeText(activity, R.string.game_shortcut_unsupported, Toast.LENGTH_LONG).show()
    }
}
