package com.droiddeck.launcher.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.WirelessAdbFix

private const val SPEECH_SERVICES = "com.google.android.tts"

/** Whether the mic can turn speech into text: a speech service is installed, and it and DroidDeck may both use the microphone. */
internal fun voiceReady(context: Context): Boolean {
    val service = AgentVoice.service(context) ?: return false
    return appHears(context) && AgentVoice.serviceHears(context, service)
}

/** Runs [command] over the saved Wireless debugging pairing off the main thread, then [done] on it with whether it worked. */
private fun adb(context: Context, command: String, done: (Boolean) -> Unit) {
    val app = context.applicationContext
    Thread({
        val ok = runCatching { WirelessAdbFix.shell(app, command) }.isSuccess
        android.os.Handler(android.os.Looper.getMainLooper()).post { done(ok) }
    }, "agent-voice-setup").start()
}

private fun appHears(context: Context) = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** Opens [intent] over DroidDeck when it can, so Back comes straight back to the setup. */
private fun open(context: Context, intent: Intent): Boolean {
    var base = context
    while (base !is Activity && base is ContextWrapper) base = base.baseContext
    return runCatching {
        if (base is Activity) base.startActivity(intent) else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}

private fun openAppInfo(context: Context, pkg: String) {
    open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)))
}

private fun openStore(context: Context) {
    if (!open(context, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SPEECH_SERVICES")))) {
        open(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$SPEECH_SERVICES")))
    }
}

/** What talking to the agent still needs, each step one button away, checked again whenever DroidDeck comes back. */
@Composable
internal fun AgentVoiceSetup(onReady: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var checks by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) checks++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val service = remember(checks) { AgentVoice.service(context) }
    val appMic = remember(checks) { appHears(context) }
    val serviceMic = remember(checks, service) { service != null && AgentVoice.serviceHears(context, service) }
    val paired = remember(checks) { WirelessAdbFix.hasSavedPairing(context) }
    var working by remember { mutableStateOf(false) }
    // A saved pairing does not mean Wireless debugging is on now, so a refusal falls back to doing it by hand.
    val viaAdb: (String, () -> Unit) -> Unit = { command, byHand ->
        working = true
        adb(context, command) { ok ->
            working = false
            checks++
            if (!ok) byHand()
        }
    }
    LaunchedEffect(service, appMic, serviceMic) { if (service != null && appMic && serviceMic) onReady() }
    val ask = if (LocalActivityResultRegistryOwner.current != null) rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Android stops asking after the user refuses twice; the switch is then only in App info.
        if (granted) checks++ else openAppInfo(context, context.packageName)
    } else null
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(Shape14).background(colors.surface).border(1.dp, pal.line, Shape14).testTag("agent-voice-setup"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp)) {
            Text(
                stringResource(R.string.agent_voice_setup), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
                modifier = Modifier.weight(1f),
            )
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClose)) {
                Icon(Icons.Outlined.Close, stringResource(R.string.agent_voice_setup_close), tint = pal.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
        CheckRow(
            if (service != null) CheckState.OK else CheckState.WARN, stringResource(R.string.agent_voice_setup_service),
            if (service == null) stringResource(R.string.agent_voice_setup_service_detail) else null,
            action = if (service == null) ({ SecondaryButton(stringResource(R.string.setup_install), compact = true) { openStore(context) } }) else null,
        )
        CheckRow(
            if (appMic) CheckState.OK else CheckState.WARN, stringResource(R.string.agent_voice_setup_app_mic), null,
            action = if (!appMic) ({
                SecondaryButton(stringResource(R.string.libraries_access_allow), compact = true) {
                    if (ask != null) ask.launch(Manifest.permission.RECORD_AUDIO) else openAppInfo(context, context.packageName)
                }
            }) else null,
        )
        CheckRow(
            if (serviceMic) CheckState.OK else CheckState.WARN, stringResource(R.string.agent_voice_setup_service_mic),
            if (service != null && !serviceMic && !paired) stringResource(R.string.agent_voice_setup_service_mic_detail) else null, divider = false,
            action = if (service != null && !serviceMic) ({
                SecondaryButton(stringResource(R.string.libraries_access_allow), enabled = !working, compact = true) {
                    if (paired) viaAdb("pm grant $service ${Manifest.permission.RECORD_AUDIO}") { openAppInfo(context, service) } else openAppInfo(context, service)
                }
            }) else null,
        )
    }
}
