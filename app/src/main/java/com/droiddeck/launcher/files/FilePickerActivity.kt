package com.droiddeck.launcher.files

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.droiddeck.launcher.R
import com.droiddeck.launcher.ui.DroidDeckTheme
import java.io.File

/**
 * Themed host for [FileManagerScreen] in pick mode. Launched through [InAppFilePicker] and answers with the
 * picked path in [EXTRA_SELECTED_FILE] on RESULT_OK.
 *
 * Extras:
 *  - [EXTRA_EXTENSIONS]        allowed lowercase extensions, no dot; absent or empty = all files
 *  - [EXTRA_PICK_DIRECTORY]    true to pick a folder instead of a file
 *  - [EXTRA_INITIAL_DIRECTORY] where to open; absent = the last folder picked from, then internal storage
 *  - [EXTRA_PICKER_TITLE]      the header
 */
class FilePickerActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        com.droiddeck.launcher.core.AppLanguage.applyTo(this, newBase)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = com.droiddeck.launcher.session.SessionPrefs.orientation(this)
        // targetSdk 28: the old storage permission is exactly what lets us list the card.
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }

        val extensions = intent.getStringArrayExtra(EXTRA_EXTENSIONS)?.toList() ?: emptyList()
        val pickDir = intent.getBooleanExtra(EXTRA_PICK_DIRECTORY, false)
        val title = intent.getStringExtra(EXTRA_PICKER_TITLE)
        // The screen remembers the folder it last picked from; an explicit start wins over it.
        val prefs = getSharedPreferences("file_manager", MODE_PRIVATE)
        val initialDir = (intent.getStringExtra(EXTRA_INITIAL_DIRECTORY) ?: prefs.getString("lastFilePickerDir", null))
            ?.let { File(it) }?.takeIf { it.isDirectory }

        setContent {
            DroidDeckTheme(appScale = com.droiddeck.launcher.core.AppUiPrefs.scale(this)) {
                Surface(modifier = Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
                    FileManagerScreen(
                        pickMode = true,
                        pickDirMode = pickDir,
                        pickExtensions = extensions,
                        initialDir = initialDir,
                        pickerTitle = title ?: getString(if (pickDir) R.string.fm_choose_folder else R.string.fm_choose_file),
                        onPick = { file ->
                            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SELECTED_FILE, file.absolutePath))
                            finish()
                        },
                    )
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // The screen re-lists on its own when the grant lands; nothing to relay.
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) recreate()
    }

    companion object {
        const val EXTRA_EXTENSIONS = "extensions"
        const val EXTRA_PICK_DIRECTORY = "pickDirectory"
        const val EXTRA_INITIAL_DIRECTORY = "initialDirectory"
        const val EXTRA_PICKER_TITLE = "pickerTitle"
        const val EXTRA_SELECTED_FILE = "selectedFile"
    }
}
