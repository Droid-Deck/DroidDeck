package com.droiddeck.launcher.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

object InAppFilePicker {
    fun buildIntent(
        context: Context,
        extensions: List<String> = emptyList(),
        title: String? = null,
        initialDir: String? = null,
    ): Intent = Intent(context, FilePickerActivity::class.java).apply {
        putExtra(FilePickerActivity.EXTRA_EXTENSIONS, extensions.toTypedArray())
        title?.let { putExtra(FilePickerActivity.EXTRA_PICKER_TITLE, it) }
        initialDir?.let { putExtra(FilePickerActivity.EXTRA_INITIAL_DIRECTORY, it) }
    }

    fun buildDirIntent(
        context: Context,
        title: String? = null,
        initialDir: String? = null,
    ): Intent = Intent(context, FilePickerActivity::class.java).apply {
        putExtra(FilePickerActivity.EXTRA_PICK_DIRECTORY, true)
        title?.let { putExtra(FilePickerActivity.EXTRA_PICKER_TITLE, it) }
        initialDir?.let { putExtra(FilePickerActivity.EXTRA_INITIAL_DIRECTORY, it) }
    }

    fun pickedPath(data: Intent?): String? = data?.getStringExtra(FilePickerActivity.EXTRA_SELECTED_FILE)

    fun pickedFile(data: Intent?): File? = pickedPath(data)?.let { File(it) }

    fun pickedUri(data: Intent?): Uri? = pickedFile(data)?.let { Uri.fromFile(it) }
}
