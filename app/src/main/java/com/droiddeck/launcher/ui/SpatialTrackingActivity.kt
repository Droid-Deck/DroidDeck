package com.droiddeck.launcher.ui

import androidx.compose.foundation.shape.CircleShape
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.*
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.AppUiPrefs
import com.droiddeck.launcher.glasses.CalibrationDistance
import com.droiddeck.launcher.handtracking.TrackingPack
import com.droiddeck.launcher.glasses.Glasses
import com.droiddeck.launcher.glasses.SpatialCalibrationProgress
import com.droiddeck.launcher.glasses.SpatialTracking
import com.droiddeck.launcher.glasses.SpatialTrackingStatus
import com.droiddeck.launcher.glasses.StereoCalibrator
import kotlinx.coroutines.delay
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size

class SpatialTrackingActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        com.droiddeck.launcher.core.AppLanguage.applyTo(this, newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Glasses.state.value.spatialConnected) { finish(); return }
        requestedOrientation = com.droiddeck.launcher.session.SessionPrefs.orientation(this)
        setContent {
            DroidDeckTheme(appScale = AppUiPrefs.scale(this)) {
                val glasses by Glasses.state.collectAsState()
                if (!glasses.spatialConnected) {
                    LaunchedEffect(Unit) { SpatialTracking.diagnostics(false); finish() }
                    return@DroidDeckTheme
                }
                var preview by remember { mutableStateOf<Bitmap?>(null) }
                var message by remember { mutableStateOf("") }
                var progress by remember { mutableStateOf(SpatialCalibrationProgress()) }
                var gpu by remember { mutableStateOf(SpatialTracking.gpuEnabled()) }
                var lite by remember { mutableStateOf(SpatialTracking.handLiteEnabled()) }
                var halfRate by remember { mutableStateOf(SpatialTracking.handHalfRateEnabled()) }
                var adaptive by remember { mutableStateOf(SpatialTracking.handAdaptiveInputEnabled()) }
                val status by SpatialTracking.status.collectAsState()
                LaunchedEffect(Unit) {
                    while(true) {
                        SpatialTracking.refreshStatus()
                        preview = SpatialTracking.diagnosticFrame()
                        message = SpatialTracking.diagnosticMessage()
                        progress = SpatialTracking.calibrationProgress()
                        gpu = SpatialTracking.gpuEnabled()
                        lite = SpatialTracking.handLiteEnabled()
                        halfRate = SpatialTracking.handHalfRateEnabled()
                        adaptive = SpatialTracking.handAdaptiveInputEnabled()
                        delay(200)
                    }
                }
                SpatialSetupScreen(status, progress, preview, message, SpatialSetupActions(
                    recenter = { SpatialTracking.recenter(false) }, restart = Glasses::restartSpatialTracking,
                    print = ::printBoard, start = SpatialTracking::calibrationStart,
                    capture = SpatialTracking::calibrationCapture, solve = SpatialTracking::calibrationSolve,
                    validate = SpatialTracking::calibrationValidate, gpu = SpatialTracking::setGpu,
                    lite = SpatialTracking::setHandModel, halfRate = SpatialTracking::setHandHalfRate,
                    adaptive = SpatialTracking::setHandAdaptiveInput,
                    saveFrame = SpatialTracking::captureDiagnostic, done = { finish() }), gpu, lite, halfRate, adaptive)
            }
        }
    }
    override fun onResume() {
        super.onResume()
        if (Glasses.state.value.spatialConnected) SpatialTracking.diagnostics(true) else finish()
    }
    override fun onPause() { SpatialTracking.diagnostics(false);super.onPause() }
    private fun printBoard() {
        if(!TrackingPack.loadNative(this)) return
        val c=StereoCalibrator(this);val mat=Mat()
        c.board.generateImage(Size(1500.0,2100.0),mat,0,1)
        val bitmap=Bitmap.createBitmap(1500,2100,Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat,bitmap);mat.release();c.close()
        val manager=getSystemService(PRINT_SERVICE) as PrintManager
        manager.print(getString(R.string.spatial_print_job),object:PrintDocumentAdapter() {
            override fun onLayout(old: PrintAttributes?, new: PrintAttributes, signal: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
                callback.onLayoutFinished(PrintDocumentInfo.Builder("droiddeck-charuco.pdf").setPageCount(1).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(),true)
            }
            override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, signal: CancellationSignal, callback: WriteResultCallback) {
                if(signal.isCanceled) { callback.onWriteCancelled();return }
                try {
                    val pdf=PdfDocument()
                    try {
                        val page=pdf.startPage(PdfDocument.PageInfo.Builder(595,842,1).create())
                        val scale=72f/25.4f
                        page.canvas.drawBitmap(bitmap,Rect(0,0,1500,2100),RectF(30*scale,35*scale,180*scale,245*scale),null)
                        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 11f; textAlign = Paint.Align.CENTER }
                        printText(page.canvas, getString(R.string.spatial_print_title), 12*scale, ink)
                        ink.textSize = 9f
                        printText(page.canvas, getString(R.string.spatial_print_scale), 21*scale, ink)
                        printText(page.canvas, getString(R.string.spatial_print_stationary), 254*scale, ink)
                        page.canvas.drawLine(55*scale, 270*scale, 155*scale, 270*scale, ink)
                        page.canvas.drawLine(55*scale, 268*scale, 55*scale, 272*scale, ink)
                        page.canvas.drawLine(155*scale, 268*scale, 155*scale, 272*scale, ink)
                        printText(page.canvas, getString(R.string.spatial_print_check), 276*scale, ink)
                        pdf.finishPage(page)
                        java.io.FileOutputStream(destination.fileDescriptor).use { pdf.writeTo(it) }
                    } finally { pdf.close() }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch(e: Exception) { callback.onWriteFailed(e.message) }
            }
            override fun onFinish() { bitmap.recycle() }
        },PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS).build())
    }

    private fun printText(canvas: android.graphics.Canvas, text: String, y: Float, ink: Paint) {
        val paint = TextPaint(ink).apply { textAlign = Paint.Align.LEFT }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 535)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
        val saved = canvas.save()
        canvas.translate(30f, y)
        layout.draw(canvas)
        canvas.restoreToCount(saved)
    }
}

internal data class SpatialSetupActions(
    val recenter: () -> Unit = {}, val restart: () -> Unit = {}, val print: () -> Unit = {},
    val start: () -> Unit = {}, val capture: () -> Unit = {}, val solve: () -> Unit = {},
    val validate: (Float) -> Unit = {}, val gpu: (Boolean) -> Unit = {},
    val lite: (Boolean) -> Unit = {}, val halfRate: (Boolean) -> Unit = {},
    val adaptive: (Boolean) -> Unit = {},
    val saveFrame: () -> Unit = {}, val done: () -> Unit = {},
)

@Composable
internal fun SpatialSetupScreen(
    status: SpatialTrackingStatus, progress: SpatialCalibrationProgress, preview: Bitmap?, message: String,
    actions: SpatialSetupActions, gpu: Boolean = false, lite: Boolean = false, halfRate: Boolean = true,
    adaptive: Boolean = true,
) {
    val host = rememberMenuHost()
    var distance by rememberSaveable { mutableStateOf(CalibrationDistance.INCHES_12) }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }
    val ready = status.trackingValid && status.synchronized && !progress.solving && !progress.validating
    Surface(Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalNarrowPane provides (maxWidth < NarrowPaneWidth)) {
                SettingsPage(host, null, actions.done, compactLayout = true,
                    modifier = Modifier.testTag("spatial-setup-page"), header = {
                        Text(stringResource(R.string.spatial_title), style = MaterialTheme.typography.titleLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }) {
                    SettingsGroup(stringResource(R.string.glasses_tracking)) {
                        SettingsRow(stringResource(R.string.glasses_head_tracking), status.message.ifEmpty { stringResource(R.string.spatial_disabled) }, stacked = true) {
                            SecondaryButton(if (status.recenterPending) stringResource(R.string.spatial_wait_tracking) else stringResource(R.string.cardboard_recenter_button),
                                enabled = !status.recenterPending, onClick = actions.recenter)
                        }
                        preview?.let {
                            Image(it.asImageBitmap(), stringResource(R.string.spatial_cameras_description),
                                Modifier.fillMaxWidth().padding(12.dp).aspectRatio(it.width.toFloat() / it.height))
                        }
                        SettingsRow(stringResource(R.string.spatial_camera_sync), if (status.synchronized) stringResource(R.string.spatial_ready) else
                            stringResource(R.string.spatial_sync_hint)) {}
                    }
                    SettingsGroup(stringResource(R.string.spatial_prepare)) {
                        ActionRow(stringResource(R.string.spatial_print_actual), stringResource(R.string.spatial_print_hint),
                            stringResource(R.string.spatial_print), actions.print)
                    }
                    SettingsGroup(stringResource(R.string.spatial_capture_group)) {
                        SettingsRow(stringResource(R.string.spatial_board_stationary), stringResource(R.string.spatial_capture_hint), stacked = true) {
                            SecondaryButton(stringResource(R.string.spatial_start), enabled = ready, onClick = actions.start)
                        }
                        SettingsRow(stringResource(R.string.spatial_views_count, progress.views), when {
                            !status.trackingValid -> stringResource(R.string.spatial_capture_paused, status.message)
                            !status.synchronized -> stringResource(R.string.spatial_wait_sync)
                            !progress.collecting -> progress.captureMessage ?: stringResource(R.string.spatial_start_hint)
                            else -> progress.captureMessage ?: stringResource(R.string.spatial_views_hint)
                        }, stacked = true) {
                            SecondaryButton(if (progress.capturing) stringResource(R.string.spatial_capturing) else stringResource(R.string.spatial_capture),
                                enabled = ready && progress.collecting && !progress.capturing, onClick = actions.capture)
                        }
                        SettingsRow(stringResource(R.string.spatial_alignment), message, stacked = true) {
                            SecondaryButton(if (progress.solving) stringResource(R.string.spatial_calculating) else stringResource(R.string.spatial_calculate),
                                enabled = !progress.solving && !progress.validating && progress.views >= 24, onClick = actions.solve)
                        }
                    }
                    SettingsGroup(stringResource(R.string.spatial_distance_group)) {
                        ChoiceRow(host, "calibration-distance", stringResource(R.string.spatial_distance), stringResource(R.string.spatial_distance_hint),
                            CalibrationDistance.entries.map { it to stringResource(it.labelRes) }, distance, onPick = { distance = it })
                        SettingsRow(stringResource(R.string.spatial_camera_board_distance), progress.validationMessage ?:
                            stringResource(R.string.spatial_measure_hint, stringResource(distance.labelRes)), stacked = true) {
                            SecondaryButton(if(progress.validating) stringResource(R.string.spatial_checking_distance) else stringResource(R.string.spatial_validate),
                                enabled = ready && progress.fitted, onClick = { actions.validate(distance.meters) })
                        }
                        HandCalibrationStatus(status)
                        SettingsRow(stringResource(R.string.glasses_hands_group), status.handStatus.ifEmpty { stringResource(R.string.spatial_hands_off) }) {}
                    }
                    SettingsGroup(stringResource(R.string.spatial_diagnostics)) {
                        ToggleRow(host, "spatial-diagnostics", stringResource(R.string.spatial_show_diagnostics), stringResource(R.string.spatial_diagnostics_hint), diagnostics) { diagnostics = it }
                        if (diagnostics) {
                            SettingsRow(stringResource(R.string.spatial_camera_timing), stringResource(R.string.spatial_timing_values, status.cameraHz, status.frameAgeMs, status.processingMs)) {}
                            SettingsRow(stringResource(R.string.spatial_hand_input), if (status.cameraHz > 0)
                                stringResource(R.string.spatial_input_values, status.cameraHz*status.cameraCopyPermille/1000f, status.cameraCopyPermille/10f)
                                else stringResource(R.string.spatial_wait_timing)) {}
                            SettingsRow(stringResource(R.string.spatial_frames), stringResource(R.string.spatial_frame_counts, status.frames, status.dropped, status.rateLimitedFrames)) {}
                            ToggleRow(host, "spatial-gpu", stringResource(R.string.spatial_gpu), stringResource(R.string.spatial_gpu_hint), gpu, onChange = actions.gpu)
                            ToggleRow(host, "spatial-lite", stringResource(R.string.spatial_lite), stringResource(R.string.spatial_lite_hint), lite, onChange = actions.lite)
                            ToggleRow(host, "spatial-adaptive-rate", stringResource(R.string.spatial_adaptive), stringResource(R.string.spatial_adaptive_hint), adaptive, onChange = actions.adaptive)
                            if (!adaptive) ToggleRow(host, "spatial-half-rate", stringResource(R.string.spatial_half_rate), stringResource(R.string.spatial_half_rate_hint), halfRate, onChange = actions.halfRate)
                            ActionRow(stringResource(R.string.spatial_diagnostic_capture), stringResource(R.string.spatial_diagnostic_capture_hint), stringResource(R.string.spatial_save_frame), actions.saveFrame)
                            ActionRow(stringResource(R.string.spatial_restart_tracking), stringResource(R.string.spatial_restart_hint), stringResource(R.string.spatial_restart), { confirmRestart = true })
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    SecondaryButton(stringResource(R.string.setup_check_done), onClick = actions.done)
                }
            }
        }
    }
    if (confirmRestart) AlertDialog(
        onDismissRequest = { confirmRestart = false }, title = { Text(stringResource(R.string.spatial_restart_title)) },
        text = { Text(stringResource(R.string.spatial_restart_confirm)) },
        confirmButton = { TextButton(onClick = { confirmRestart = false; actions.restart() }, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.spatial_restart)) } },
        dismissButton = { TextButton(onClick = { confirmRestart = false }, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
internal fun HandCalibrationStatus(status: SpatialTrackingStatus) {
    val (label, hint) = when {
        status.calibrationSource == "luma-ultra-base" -> R.string.glasses_hands_base to R.string.glasses_hands_base_hint
        status.calibrationVerified -> R.string.glasses_hands_calibration_verified to R.string.glasses_hands_ready_hint
        else -> R.string.glasses_hands_calibration_required to R.string.glasses_hands_calibration_required_hint
    }
    SettingsRow(stringResource(label), stringResource(hint)) {}
}
