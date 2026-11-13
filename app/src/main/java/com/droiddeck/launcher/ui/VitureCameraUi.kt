package com.droiddeck.launcher.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.droiddeck.launcher.R
import com.droiddeck.launcher.glasses.GlassesState
import com.droiddeck.launcher.glasses.VitureCamera

@Composable
internal fun VitureCameraControls(glasses: GlassesState) {
    val device = glasses.viture ?: return
    if (!glasses.canControl || device.cameraVid <= 0 || device.cameraPid <= 0) return
    var preview by remember(device.cameraVid, device.cameraPid) { mutableStateOf(false) }
    SettingsGroup(stringResource(R.string.viture_camera_title)) {
        ActionRow(stringResource(R.string.viture_camera_live_view), stringResource(R.string.viture_camera_hint),
            stringResource(R.string.viture_camera_open), { preview = true }, modifier = Modifier.testTag("viture-camera-open"))
    }
    if (preview) VitureCameraPreview(device.cameraVid, device.cameraPid) { preview = false }
}

@Composable
private fun VitureCameraPreview(vendorId: Int, productId: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val camera = remember(vendorId, productId) { VitureCamera(context, vendorId, productId) }
    val status by camera.status.collectAsState()
    val frame by camera.frame.collectAsState()
    var permissionDenied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) camera.start()
    }
    DisposableEffect(camera, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> camera.start()
                Lifecycle.Event.ON_STOP -> camera.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) camera.start()
        onDispose {
            lifecycle.removeObserver(observer)
            camera.close()
        }
    }
    GlideDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f).testTag("viture-camera-preview"),
            shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.viture_camera_live_view), modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium)
                    SecondaryButton(stringResource(R.string.common_close), compact = true,
                        modifier = Modifier.testTag("viture-camera-close"), onClick = onDismiss)
                }
                Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black), contentAlignment = Alignment.Center) {
                    frame?.let {
                        Image(it.asImageBitmap(), stringResource(R.string.viture_camera_description),
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                    if (status == VitureCamera.Status.STARTING) CircularProgressIndicator()
                }
                Text(stringResource(when (status) {
                    VitureCamera.Status.PAUSED -> R.string.viture_camera_paused
                    VitureCamera.Status.CAMERA_PERMISSION -> R.string.viture_camera_permission
                    VitureCamera.Status.USB_PERMISSION -> R.string.viture_camera_usb_permission
                    VitureCamera.Status.USB_DENIED -> R.string.viture_camera_usb_denied
                    VitureCamera.Status.STARTING -> R.string.viture_camera_starting
                    VitureCamera.Status.LIVE -> R.string.viture_camera_live
                    VitureCamera.Status.DISCONNECTED -> R.string.viture_camera_disconnected
                    VitureCamera.Status.FAILED -> R.string.viture_camera_failed
                }), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(if (status == VitureCamera.Status.LIVE) "viture-camera-live" else "viture-camera-status"))
                when (status) {
                    VitureCamera.Status.CAMERA_PERMISSION -> SecondaryButton(
                        stringResource(if (permissionDenied) R.string.viture_camera_settings else R.string.viture_camera_allow),
                        onClick = {
                            if (permissionDenied) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")))
                            else permission.launch(Manifest.permission.CAMERA)
                        })
                    VitureCamera.Status.USB_DENIED, VitureCamera.Status.DISCONNECTED, VitureCamera.Status.FAILED ->
                        SecondaryButton(stringResource(R.string.viture_camera_retry), onClick = camera::start)
                    else -> Unit
                }
            }
        }
    }
}
