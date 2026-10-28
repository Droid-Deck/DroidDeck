package com.droiddeck.launcher

import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.graphics.drawable.toBitmap
import com.droiddeck.launcher.core.AppUiPrefs
import com.droiddeck.launcher.ui.DroidDeckTheme
import com.droiddeck.launcher.ui.PinRequestPrompt

class PinRequestActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        com.droiddeck.launcher.core.AppLanguage.applyTo(this, newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = runCatching { getSystemService(LauncherApps::class.java).getPinItemRequest(intent) }.getOrNull()
        if (request == null || !request.isValid) {
            finish()
            return
        }
        val prompt = when (request.requestType) {
            LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT -> request.shortcutInfo?.let { info ->
                val appLabel = appLabel(info.`package`)
                Triple(info.shortLabel?.toString().orEmpty().ifEmpty { appLabel }, appLabel, HomeApp.shortcutIcon(this, info))
            }
            LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET -> request.getAppWidgetProviderInfo(this)?.let { info ->
                val appLabel = appLabel(info.provider.packageName)
                val dpi = resources.displayMetrics.densityDpi
                val icon = runCatching { (info.loadPreviewImage(this, dpi) ?: info.loadIcon(this, dpi))?.let(::scaled) }.getOrNull()
                Triple(info.loadLabel(packageManager)?.toString().orEmpty().ifEmpty { appLabel }, appLabel, icon)
            }
            else -> null
        }
        if (prompt == null) {
            finish()
            return
        }
        val (label, appLabel, icon) = prompt
        val widget = request.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET
        setContent {
            DroidDeckTheme(appScale = AppUiPrefs.scale(this)) {
                PinRequestPrompt(
                    label = label, appLabel = appLabel, icon = icon, wide = widget,
                    onAdd = {
                        if (!widget) {
                            HomePins.accept(this, request)
                            finish()
                        } else if (!HomeWidgets.addPinned(this, request)) finish()
                    },
                    onCancel = { finish() },
                )
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (HomeWidgets.onActivityResult(this, requestCode, resultCode)) {
            finish()
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun appLabel(packageName: String): String =
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString() }
            .getOrDefault(packageName)

    private fun scaled(d: android.graphics.drawable.Drawable): Bitmap {
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: 192
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: 192
        val k = minOf(1f, 480f / maxOf(w, h))
        return d.toBitmap((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1))
    }
}
