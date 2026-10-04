package com.droiddeck.launcher.frontend

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import android.widget.Toast
import com.droiddeck.launcher.MainActivity
import com.droiddeck.launcher.R

object GameShortcuts {
    fun launchIntent(context: Context, gameId: String) = Intent(Intent.ACTION_VIEW,
        Uri.parse(GameLaunchLink.uri(gameId)), context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun info(context: Context, game: Library.SteamGame): ShortcutInfo {
        val name = game.name.ifBlank { context.getString(R.string.app_name) }
        val icon = listOfNotNull(game.icon, game.art).firstNotNullOfOrNull { file -> runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 256) inSampleSize *= 2
            }
            BitmapFactory.decodeFile(file.path, options)?.let(Icon::createWithBitmap)
        }.getOrNull() } ?: Icon.createWithResource(context, R.mipmap.ic_launcher)
        return ShortcutInfo.Builder(context, "game:${game.gameIdString}")
            .setActivity(android.content.ComponentName(context, MainActivity::class.java))
            .setShortLabel(name.take(40)).setLongLabel(name).setIcon(icon)
            .setIntent(launchIntent(context, game.gameIdString)).build()
    }

    fun pin(activity: Activity, game: Library.SteamGame) {
        val manager = activity.getSystemService(ShortcutManager::class.java)
        val requested = runCatching { manager.isRequestPinShortcutSupported &&
            manager.requestPinShortcut(info(activity, game), null) }.getOrDefault(false)
        if (!requested) Toast.makeText(activity, R.string.game_shortcut_unsupported, Toast.LENGTH_LONG).show()
    }

    /** Standard shortcut picker result, understood by launchers that import Android shortcuts. */
    fun result(activity: Activity, game: Library.SteamGame): Intent =
        activity.getSystemService(ShortcutManager::class.java).createShortcutResultIntent(info(activity, game))
}
