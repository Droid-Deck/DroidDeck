package com.droiddeck.launcher.lan

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

object SteamLinkCoop {
    const val PACKAGE = "com.valvesoftware.steamlink"
    private const val STORE_URL = "https://play.google.com/store/apps/details?id=$PACKAGE"

    fun clientIntent(context: Context): Intent? = context.packageManager.getLaunchIntentForPackage(PACKAGE)
        ?: context.packageManager.getLeanbackLaunchIntentForPackage(PACKAGE)

    fun openClientOrStore(context: Context): Boolean {
        val choices = listOfNotNull(
            clientIntent(context),
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE")),
            Intent(Intent.ACTION_VIEW, Uri.parse(STORE_URL)),
        )
        for (intent in choices) {
            try {
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }
}
