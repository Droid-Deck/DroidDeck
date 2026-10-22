package com.droiddeck.launcher.usbdrive

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.droiddeck.launcher.core.AppLanguage
import com.droiddeck.launcher.core.AppUiPrefs
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.ui.DroidDeckTheme

class UsbDriveActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        AppLanguage.applyTo(this, newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = SessionPrefs.orientation(this)
        val deviceName = intent.getStringExtra(EXTRA_DEVICE)
        if (deviceName == null) {
            finish()
            return
        }
        setContent {
            DroidDeckTheme(appScale = AppUiPrefs.scale(this)) {
                Surface(modifier = Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
                    UsbDriveScreen(deviceName = deviceName, onClose = ::finish)
                }
            }
        }
    }

    companion object {
        private const val EXTRA_DEVICE = "com.droiddeck.launcher.usbdrive.DEVICE"

        fun intent(context: Context, device: UsbDevice): Intent =
            Intent(context, UsbDriveActivity::class.java).putExtra(EXTRA_DEVICE, device.deviceName)
    }
}
