package com.droiddeck.launcher.ui

import android.content.Context
import android.text.BidiFormatter
import android.text.TextDirectionHeuristics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.OpenXrRuntime
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.XrControllerModels
import java.text.NumberFormat

internal fun xrModelStatusText(context: Context, status: XrControllerModels.Status): String {
    if (status.session != SessionState.sessionId || status.names.isEmpty() || !OpenXrRuntime.isAvailable(context)) return ""
    val names = BidiFormatter.getInstance(context.resources.configuration.locales[0]).unicodeWrap(
        status.names.joinToString(" / "), TextDirectionHeuristics.LTR)
    val title = context.getString(R.string.load_xr_controller_models, names)
    val phase = context.getString(when (status.phase) {
        XrControllerModels.Phase.DOWNLOADING -> R.string.load_xr_models_downloading
        XrControllerModels.Phase.CHECKING -> R.string.load_xr_models_checking
        XrControllerModels.Phase.INSTALLING -> R.string.load_xr_models_installing
        XrControllerModels.Phase.READY -> R.string.load_xr_models_ready
        XrControllerModels.Phase.NATIVE -> R.string.load_xr_models_native
        XrControllerModels.Phase.FALLBACK -> R.string.load_xr_models_failed_fallback
        XrControllerModels.Phase.FAILED -> R.string.cardboard_steamvr_failed
    })
    val progress = status.progress?.takeIf { status.phase == XrControllerModels.Phase.DOWNLOADING }?.let {
        NumberFormat.getPercentInstance(context.resources.configuration.locales[0]).format(it.coerceIn(0f, 1f))
    }
    return listOfNotNull(title, phase, progress).joinToString(" · ")
}

@Composable
internal fun XrModelStatus(status: XrControllerModels.Status? = null, modifier: Modifier = Modifier, centered: Boolean = false) {
    val current = status ?: XrControllerModels.status.collectAsState().value
    val text = xrModelStatusText(LocalContext.current, current)
    if (text.isNotEmpty()) Text(text, modifier, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start)
}
