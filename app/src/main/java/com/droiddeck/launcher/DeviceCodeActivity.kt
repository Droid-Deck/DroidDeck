package com.droiddeck.launcher

import android.app.ActivityOptions
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.core.AppLanguage
import com.droiddeck.launcher.ui.DroidDeckTheme
import com.droiddeck.launcher.ui.FitText
import com.droiddeck.launcher.ui.LocalPalette
import com.droiddeck.launcher.ui.PrimaryButton
import com.droiddeck.launcher.ui.SecondaryButton
import java.lang.ref.WeakReference

/** Floats a sign-in code over the browser page that asks for it. */
class DeviceCodeActivity : ComponentActivity() {
    private var code by mutableStateOf("")
    private var label by mutableStateOf("")
    private var message by mutableStateOf<String?>(null)
    private var paste = false
    private var agent = true
    private var url = ""
    private var display: Int? = null
    private var inPip by mutableStateOf(false)
    private var openPending = false

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        read(intent)
        openPending = savedInstanceState == null
        setContent { DroidDeckTheme { Float() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        read(intent)
        openPending = true
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (!openPending) return
        if (isInPictureInPictureMode) {
            openPage()
            return
        }
        enterPip()
        if (!inPip) {
            openPage()
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && paste && !inPip) tryPaste()
    }

    /** Android hands the clipboard only to the app in front, which this float is once expanded. */
    private fun tryPaste() {
        val text = runCatching { getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
        if (text.isNullOrBlank()) return
        if (onPaste?.invoke(this, text) == true) returnToApp()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode && openPending) openPage()
    }

    private fun read(intent: Intent) {
        code = intent.getStringExtra(EXTRA_CODE).orEmpty()
        url = intent.getStringExtra(EXTRA_URL).orEmpty()
        label = intent.getStringExtra(EXTRA_LABEL) ?: getString(R.string.agent_step_github)
        agent = intent.getBooleanExtra(EXTRA_AGENT, true)
        message = intent.getStringExtra(EXTRA_MESSAGE)
        paste = intent.getBooleanExtra(EXTRA_PASTE, false)
        display = intent.getIntExtra(EXTRA_DISPLAY, -1).takeIf { it > 0 }
        if (agent) copy()
    }

    private fun copy() {
        runCatching { getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, code)) }
    }

    private fun enterPip() {
        inPip = runCatching {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }.getOrDefault(false)
    }

    private fun openPage() {
        openPending = false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val at = display
        val opened = runCatching {
            if (at == null) startActivity(intent)
            else startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(at).toBundle())
        }.recoverCatching { startActivity(intent) }.isSuccess
        if (agent) com.droiddeck.launcher.agent.AgentHost.deviceCodeOpened(applicationContext, opened)
        else if (!opened) finish()
    }

    @androidx.compose.runtime.Composable
    private fun Float() {
        val colors = MaterialTheme.colorScheme
        val accent = LocalPalette.current.signal
        BackHandler(enabled = !inPip) { enterPip() }
        Box(Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(if (inPip) 8.dp else 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (inPip) 2.dp else 16.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                val shown = message
                if (shown != null) Text(shown, fontSize = if (inPip) 12.sp else 22.sp, fontWeight = FontWeight.Bold, color = accent,
                    maxLines = 4, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                else FitText(code, maxSize = if (inPip) 40.sp else 56.sp, fontWeight = FontWeight.Bold, color = accent,
                    modifier = Modifier.fillMaxWidth())
                if (!paste) Text(Uri.parse(url).let { it.host + it.path }, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 2, textAlign = TextAlign.Center)
                if (!inPip) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (paste) PrimaryButton(stringResource(R.string.fm_paste), onClick = ::tryPaste)
                    else PrimaryButton(stringResource(R.string.fm_copy), onClick = ::copy)
                    SecondaryButton(stringResource(R.string.common_close), onClick = ::finish)
                }
            }
        }
    }

    companion object {
        private const val EXTRA_CODE = "code"
        private const val EXTRA_URL = "url"
        private const val EXTRA_DISPLAY = "display"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_AGENT = "agent"
        private const val EXTRA_MESSAGE = "message"
        private const val EXTRA_PASTE = "paste"

        /** Takes what the user copied from the page; true once it was accepted. */
        @Volatile var onPaste: ((Context, String) -> Boolean)? = null
        private var current: WeakReference<DeviceCodeActivity>? = null

        /**
         * False when this device cannot float a window, so the caller opens the page itself. [message]
         * replaces the code; [paste] shows a Paste button that hands the clipboard to [onPaste].
         */
        fun show(context: Context, code: String, url: String, display: Int, label: String? = null, message: String? = null, paste: Boolean = false): Boolean {
            if (!PairingGuideActivity.canFloat(context)) return false
            val intent = Intent(context, DeviceCodeActivity::class.java)
                .putExtra(EXTRA_CODE, code).putExtra(EXTRA_URL, url).putExtra(EXTRA_DISPLAY, display)
                .putExtra(EXTRA_AGENT, label == null).also { if (label != null) it.putExtra(EXTRA_LABEL, label); if (message != null) it.putExtra(EXTRA_MESSAGE, message); it.putExtra(EXTRA_PASTE, paste) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching {
                if (display > 0) context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle())
                else context.startActivity(intent)
            }.isSuccess
        }

        fun close() {
            current?.get()?.finish()
        }
    }

    private fun returnToApp() {
        runCatching {
            startActivity(Intent(this, FrontEndActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        finish()
    }
}
