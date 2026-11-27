package com.droiddeck.launcher

import android.os.Bundle
import android.widget.Toast
import com.droiddeck.launcher.session.SteamVrSession
import com.droiddeck.launcher.session.SessionTaskLabels

class OpenXrActivity : QuestXrActivity() {
    override val keepDesktopPanel = true
    private var owner = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        owner = SteamVrSession.attach { _, failed ->
            if (failed) Toast.makeText(this, R.string.cardboard_steamvr_failed, Toast.LENGTH_LONG).show()
            finish()
        }
        super.onCreate(savedInstanceState)
        SessionTaskLabels.steamVr(this)
    }

    override fun onResume() {
        super.onResume()
        SessionTaskLabels.steamVr(this)
    }

    override fun finish() = finishAndRemoveTask()

    override fun startSteamVr() = SteamVrSession.start(this, owner)

    override fun onXrStatus(): Int = SteamVrSession.status.ordinal

    override fun onDestroy() {
        SteamVrSession.detach(owner, isChangingConfigurations)
        super.onDestroy()
    }
}
