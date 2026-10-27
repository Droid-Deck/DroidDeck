package com.droiddeck.launcher

import android.content.pm.LauncherApps
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.droiddeck.launcher.core.AppUiPrefs
import com.droiddeck.launcher.ui.DroidDeckTheme
import com.droiddeck.launcher.ui.PinShortcutPrompt

class PinShortcutActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        com.droiddeck.launcher.core.AppLanguage.applyTo(this, newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launcher = getSystemService(LauncherApps::class.java)
        val request = runCatching { launcher.getPinItemRequest(intent) }.getOrNull()
        val info = request?.shortcutInfo
        if (request == null || !request.isValid || request.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT || info == null) {
            finish()
            return
        }
        val appLabel = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(info.`package`, 0)).toString() }
            .getOrDefault(info.`package`)
        val icon = HomeApp.shortcutIcon(this, info)
        setContent {
            DroidDeckTheme(appScale = AppUiPrefs.scale(this)) {
                PinShortcutPrompt(
                    label = info.shortLabel?.toString().orEmpty().ifEmpty { appLabel },
                    appLabel = appLabel,
                    icon = icon,
                    onAdd = {
                        HomePins.accept(this, request)
                        finish()
                    },
                    onCancel = { finish() },
                )
            }
        }
    }
}
