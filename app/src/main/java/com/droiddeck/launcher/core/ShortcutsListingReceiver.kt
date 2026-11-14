package com.droiddeck.launcher.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ShortcutsListingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) SteamIntegration.syncShortcutsListing(context)
    }
}
