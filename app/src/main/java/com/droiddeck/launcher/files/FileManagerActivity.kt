package com.droiddeck.launcher.files

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.droiddeck.launcher.ui.DroidDeckTheme
import java.io.File

/** The File Manager as a screen of its own, from the Files tile on the main screen. */
class FileManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
        val initialDir = intent.getStringExtra(EXTRA_START_DIR)?.let(::File)
        setContent {
            DroidDeckTheme {
                Surface(modifier = Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
                    FileManagerScreen(initialDir = initialDir)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) recreate()
    }

    companion object {
        const val EXTRA_START_DIR = "com.droiddeck.launcher.files.START_DIR"
    }
}
