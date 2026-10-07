package com.droiddeck.launcher.files

import androidx.compose.runtime.Composable
import com.droiddeck.launcher.ui.DroidDeckPreviews
import com.droiddeck.launcher.ui.PreviewCase
import com.droiddeck.launcher.ui.PreviewHost
import java.io.File

/** File metadata only: no files are created and no real directory is enumerated. */
private class SampleFile(path: String, private val directory: Boolean = false, private val bytes: Long = 0) : File(path) {
    override fun isDirectory() = directory
    override fun isFile() = !directory
    override fun exists() = true
    override fun canRead() = true
    override fun canWrite() = false
    override fun length() = bytes
    override fun getUsableSpace() = 32_000_000_000L
    override fun getFreeSpace() = 32_000_000_000L
    override fun lastModified() = 1_791_331_200_000L
    override fun getCanonicalPath() = absolutePath
    override fun getCanonicalFile() = this
    override fun listFiles(): Array<File> = emptyArray()
}

private val directory = SampleFile("/storage/emulated/0", directory = true)
private val entries = listOf(
    SampleFile("/storage/emulated/0/Games", directory = true),
    SampleFile("/storage/emulated/0/ROMs", directory = true),
    SampleFile("/storage/emulated/0/Downloads", directory = true),
    SampleFile("/storage/emulated/0/Portal 2.exe", bytes = 4_200_000),
    SampleFile("/storage/emulated/0/session.log", bytes = 32_000),
    SampleFile("/storage/emulated/0/save.zip", bytes = 12_000_000),
)

@DroidDeckPreviews
@Composable
internal fun FileGridPreview() = PreviewHost {
    FileManagerScreen(preview = FileManagerPreviewState(directory, entries))
}

@DroidDeckPreviews
@Composable
internal fun FileListPreview() = PreviewHost {
    FileManagerScreen(preview = FileManagerPreviewState(directory, entries, grid = false))
}

@DroidDeckPreviews
@Composable
internal fun FileEmptyPreview() = PreviewHost {
    FileManagerScreen(preview = FileManagerPreviewState(directory, emptyList()))
}

@DroidDeckPreviews
@Composable
internal fun FilePickerPreview() = PreviewHost {
    FileManagerScreen(pickMode = true, pickExtensions = listOf("exe"), pickerTitle = "Choose executable", onPick = {},
        preview = FileManagerPreviewState(directory, entries.filter { it.isDirectory || it.extension == "exe" }))
}

@DroidDeckPreviews
@Composable
internal fun FolderPickerPreview() = PreviewHost {
    FileManagerScreen(pickMode = true, pickDirMode = true, onPick = {},
        preview = FileManagerPreviewState(directory, entries.filter { it.isDirectory }))
}

@DroidDeckPreviews
@Composable
internal fun FilePropertiesPreview() = PreviewHost {
    FilePropertiesDialog(entries.last(), {}, {})
}

@DroidDeckPreviews
@Composable
internal fun FavoritesPreview() = PreviewHost {
    FileManagerScreen(preview = FileManagerPreviewState(directory, entries, favorites = true))
}

internal val filePreviewCases = listOf(
    PreviewCase("Favorites") { FavoritesPreview() },
    PreviewCase("FileGrid") { FileGridPreview() },
    PreviewCase("FileList") { FileListPreview() },
    PreviewCase("FileEmpty") { FileEmptyPreview() },
    PreviewCase("FilePicker") { FilePickerPreview() },
    PreviewCase("FolderPicker") { FolderPickerPreview() },
    PreviewCase("FileProperties") { FilePropertiesPreview() },
)
