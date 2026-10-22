package com.droiddeck.launcher.frontend

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import android.widget.Toast
import com.droiddeck.launcher.LauncherIcon
import com.droiddeck.launcher.MainActivity
import com.droiddeck.launcher.R
import java.io.File

object GameShortcuts {
    fun launchIntent(context: Context, gameId: String) = Intent(Intent.ACTION_VIEW,
        Uri.parse(GameLaunchLink.uri(gameId)), context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun info(context: Context, game: Library.SteamGame): ShortcutInfo {
        val name = game.name.ifBlank { context.getString(R.string.app_name) }
        val icon = iconBitmap(game)?.let(Icon::createWithBitmap) ?: Icon.createWithResource(context, R.mipmap.ic_launcher)
        return ShortcutInfo.Builder(context, "game:${game.gameIdString}")
            .setActivity(LauncherIcon.activity(context))
            .setShortLabel(name.take(40)).setLongLabel(name).setIcon(icon)
            .setIntent(launchIntent(context, game.gameIdString)).build()
    }

    internal fun iconBitmap(game: Library.SteamGame): Bitmap? {
        val icon = game.icon?.let(::decode)
        if (icon != null && minOf(icon.width, icon.height) >= SHARP_ICON_PX) return icon
        return game.art?.let(::decode)?.let(::square) ?: icon
    }

    private fun decode(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (inSampleSize * 2) >= ICON_PX) inSampleSize *= 2
        }
        BitmapFactory.decodeFile(file.path, options)
    }.getOrNull()

    private fun square(bitmap: Bitmap): Bitmap {
        val side = minOf(bitmap.width, bitmap.height)
        return Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
    }

    fun pin(activity: Activity, game: Library.SteamGame) {
        val manager = activity.getSystemService(ShortcutManager::class.java)
        val requested = runCatching { manager.isRequestPinShortcutSupported &&
            manager.requestPinShortcut(info(activity, game), null) }.getOrDefault(false)
        if (!requested) Toast.makeText(activity, R.string.game_shortcut_unsupported, Toast.LENGTH_LONG).show()
    }

    fun result(activity: Activity, game: Library.SteamGame): Intent =
        activity.getSystemService(ShortcutManager::class.java).createShortcutResultIntent(info(activity, game))

    private const val ICON_PX = 256
    private const val SHARP_ICON_PX = 128
}
