package com.droiddeck.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.droiddeck.launcher.session.SessionService

class HomeActivity : Activity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        com.droiddeck.launcher.core.AppLanguage.applyTo(this, newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = com.droiddeck.launcher.session.SessionPrefs.orientation(this)
        routeHome()
    }

    private fun routeHome() {
        val destination = if (HomeApp.isActiveSteamSession()) {
            Intent(this, SessionActivity::class.java)
                .setAction(SessionService.ACTION_HOME_GUIDE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } else {
            Intent(this, FrontEndActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(destination)
        finish()
    }
}
