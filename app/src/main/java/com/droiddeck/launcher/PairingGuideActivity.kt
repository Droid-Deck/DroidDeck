package com.droiddeck.launcher

import android.app.ActivityOptions
import android.app.AppOpsManager
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.core.AppLanguage
import com.droiddeck.launcher.core.GlobalSettings
import com.droiddeck.launcher.core.PhantomProcessLimit
import com.droiddeck.launcher.core.WirelessAdbPairingService
import com.droiddeck.launcher.core.WirelessAdbPairingService.Stage
import com.droiddeck.launcher.ui.DroidDeckTheme
import com.droiddeck.launcher.ui.LocalPalette
import com.droiddeck.launcher.ui.PrimaryButton
import com.droiddeck.launcher.ui.SecondaryButton
import kotlinx.coroutines.delay

class PairingGuideActivity : ComponentActivity() {
    enum class Step { BUILD_NUMBER, CHILD_PROCESS, WIRELESS_DEBUGGING, PAIR, CODE, WORKING, DONE, FAILED }

    private var settingsDisplay: Int? = null
    private var toggleOnly = false
    private var inPip by mutableStateOf(false)
    private var launchPending = false

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsDisplay = intent.getIntExtra(EXTRA_DISPLAY, -1).takeIf { it >= 0 }
        toggleOnly = intent.getBooleanExtra(EXTRA_TOGGLE_ONLY, false)
        setContent { DroidDeckTheme { Guide() } }
        launchPending = savedInstanceState == null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        settingsDisplay = intent.getIntExtra(EXTRA_DISPLAY, -1).takeIf { it >= 0 }
        toggleOnly = intent.getBooleanExtra(EXTRA_TOGGLE_ONLY, false)
        launchPending = true
    }

    override fun onResume() {
        super.onResume()
        if (!launchPending) return
        if (inPip || settingsDisplay != null) {
            launchSettings()
            return
        }
        enterPip()
        if (!inPip) launchSettings()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode && launchPending) launchSettings()
    }

    private fun launchSettings() {
        launchPending = false
        openSettings(stepFor(SettingsState.read(this), WirelessAdbPairingService.stage.value, toggleOnly))
    }

    private fun enterPip() {
        inPip = runCatching {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(4, 3)).build())
        }.getOrDefault(false)
    }

    private fun openSettings(step: Step) {
        val intent = if (step == Step.BUILD_NUMBER) {
            Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).putExtra(HIGHLIGHT_KEY, "build_number")
        } else {
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                if (step != Step.CHILD_PROCESS) putExtra(HIGHLIGHT_KEY, "toggle_adb_wireless")
            }
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val display = settingsDisplay
        runCatching {
            if (display == null) startActivity(intent)
            else startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle())
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun returnToApp() {
        startActivity(Intent(this, FrontEndActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }

    @Composable
    private fun Guide() {
        val stage by WirelessAdbPairingService.stage.collectAsState()
        val settingsState by produceState(SettingsState.read(this)) {
            while (true) {
                value = SettingsState.read(this@PairingGuideActivity)
                delay(500)
            }
        }
        val step = stepFor(settingsState, stage, toggleOnly)
        var previous by remember { mutableStateOf(step) }
        LaunchedEffect(step) {
            if (previous == Step.BUILD_NUMBER && (step == Step.WIRELESS_DEBUGGING || step == Step.CHILD_PROCESS)) openSettings(step)
            previous = step
            when (step) {
                Step.DONE -> { delay(1_500); returnToApp() }
                Step.FAILED -> { delay(3_000); returnToApp() }
                else -> Unit
            }
        }
        LaunchedEffect(stage) { if (stage == Stage.Idle && !toggleOnly) finish() }
        BackHandler(enabled = !inPip && settingsDisplay == null) { enterPip() }

        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            if (inPip) CompactStep(step, stage)
            else FullGuide(step, stage)
        }
    }

    @Composable
    private fun CompactStep(step: Step, stage: Stage) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(
                instruction(step, stage), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            StepVisual(step, compact = true)
            StepDots(step)
        }
    }

    @Composable
    private fun FullGuide(step: Step, stage: Stage) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.gate_title), style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text(instruction(step, stage), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            StepVisual(step, compact = false)
            StepDots(step)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (step == Step.DONE || step == Step.FAILED) {
                    PrimaryButton(stringResource(R.string.common_back), onClick = ::returnToApp)
                } else {
                    PrimaryButton(stringResource(R.string.gate_open_dev), onClick = {
                        if (settingsDisplay == null) enterPip()
                        openSettings(step)
                    })
                    SecondaryButton(stringResource(R.string.common_cancel), onClick = {
                        if (!toggleOnly) WirelessAdbPairingService.cancel(this@PairingGuideActivity)
                        returnToApp()
                    })
                }
            }
        }
    }

    @Composable
    private fun instruction(step: Step, stage: Stage): String = when (step) {
        Step.BUILD_NUMBER -> stringResource(R.string.pairguide_build_number)
        Step.CHILD_PROCESS -> stringResource(R.string.pairguide_child_process)
        Step.WIRELESS_DEBUGGING -> stringResource(R.string.pairguide_wireless)
        Step.PAIR -> stringResource(R.string.pairguide_pair)
        Step.CODE -> (stage as? Stage.CodeNeeded)?.error ?: stringResource(R.string.gate_step3)
        Step.WORKING -> (stage as? Stage.Working)?.step ?: stringResource(R.string.adbpair_title_working)
        Step.DONE -> stringResource(R.string.adbpair_title_done)
        Step.FAILED -> (stage as? Stage.Failed)?.error ?: stringResource(R.string.adbpair_title_failed)
    }

    @Composable
    private fun StepVisual(step: Step, compact: Boolean) {
        val colors = MaterialTheme.colorScheme
        val accent = LocalPalette.current.signal
        val pulse = rememberInfiniteTransition(label = "guidePulse")
        val beat by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "beat")
        val label = when (step) {
            Step.BUILD_NUMBER -> stringResource(R.string.pairguide_row_build_number)
            Step.CHILD_PROCESS -> stringResource(R.string.pairguide_row_child_process)
            Step.WIRELESS_DEBUGGING -> stringResource(R.string.pairguide_row_wireless)
            Step.PAIR -> stringResource(R.string.pairguide_row_pair)
            Step.CODE -> stringResource(R.string.adbpair_enter_code)
            Step.WORKING, Step.DONE, Step.FAILED -> return
        }
        val rowHeight = if (compact) 34.dp else 56.dp
        Row(
            Modifier.fillMaxWidth().height(rowHeight).background(colors.surfaceVariant, RoundedCornerShape(10.dp))
                .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyLarge,
                color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            when (step) {
                Step.WIRELESS_DEBUGGING, Step.CHILD_PROCESS -> Toggle(on = beat > 0.5f, accent = accent, compact = compact)
                else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (step == Step.BUILD_NUMBER) Text("×7", style = MaterialTheme.typography.labelLarge, color = accent)
                    Box(contentAlignment = Alignment.Center) {
                        Box(Modifier.size(if (compact) 18.dp else 26.dp).scale(0.6f + beat * 0.8f).alpha(1f - beat)
                            .background(accent, CircleShape))
                        Box(Modifier.size(if (compact) 8.dp else 12.dp).background(accent, CircleShape))
                    }
                }
            }
        }
    }

    @Composable
    private fun Toggle(on: Boolean, accent: Color, compact: Boolean) {
        val width = if (compact) 26.dp else 40.dp
        val knob = if (compact) 12.dp else 18.dp
        Box(
            Modifier.width(width).height(knob + 4.dp)
                .background(if (on) accent.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline, CircleShape).padding(2.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) { Box(Modifier.size(knob).background(Color.White, CircleShape)) }
    }

    @Composable
    private fun StepDots(step: Step) {
        val accent = LocalPalette.current.signal
        val shown = if (toggleOnly) listOf(Step.BUILD_NUMBER, Step.CHILD_PROCESS)
        else listOf(Step.BUILD_NUMBER, Step.WIRELESS_DEBUGGING, Step.PAIR, Step.CODE)
        val at = shown.indexOf(step).takeIf { it >= 0 } ?: shown.size
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            shown.forEachIndexed { index, _ ->
                Box(Modifier.size(if (index == at) 8.dp else 6.dp)
                    .background(if (index <= at) accent else MaterialTheme.colorScheme.outline, CircleShape))
            }
            Spacer(Modifier.width(2.dp))
        }
    }

    private data class SettingsState(
        val developerOptions: Boolean,
        val wirelessDebugging: Boolean,
        val childProcessBlocked: Boolean,
    ) {
        companion object {
            fun read(context: Context) = SettingsState(
                GlobalSettings.developerOptionsOn(context),
                GlobalSettings.wirelessDebuggingOn(context),
                PhantomProcessLimit.blocksSteam(PhantomProcessLimit.read(context)),
            )
        }
    }

    companion object {
        private const val EXTRA_DISPLAY = "settingsDisplay"
        private const val EXTRA_TOGGLE_ONLY = "toggleOnly"
        private const val HIGHLIGHT_KEY = ":settings:fragment_args_key"

        private fun stepFor(settings: SettingsState, stage: Stage, toggleOnly: Boolean): Step = if (toggleOnly) when {
            !settings.developerOptions -> Step.BUILD_NUMBER
            settings.childProcessBlocked -> Step.CHILD_PROCESS
            else -> Step.DONE
        } else when (stage) {
            is Stage.Working -> Step.WORKING
            Stage.Done -> Step.DONE
            is Stage.Failed -> Step.FAILED
            is Stage.CodeNeeded -> Step.CODE
            else -> when {
                !settings.developerOptions -> Step.BUILD_NUMBER
                !settings.wirelessDebugging -> Step.WIRELESS_DEBUGGING
                else -> Step.PAIR
            }
        }

        fun canFloat(context: Context): Boolean {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
            val ops = context.getSystemService(AppOpsManager::class.java) ?: return true
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), context.packageName)
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }

        fun start(context: Context, settingsDisplay: Int?, toggleOnly: Boolean = false): Boolean {
            if (settingsDisplay == null && !canFloat(context)) return false
            context.startActivity(Intent(context, PairingGuideActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_TOGGLE_ONLY, toggleOnly)
                .apply { if (settingsDisplay != null) putExtra(EXTRA_DISPLAY, settingsDisplay) })
            return true
        }
    }
}
