package com.droiddeck.launcher.session

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A screenshot for the bottom-screen gallery: one DroidDeck took (a MediaStore [uri]) or one Steam saved (a [file]). */
data class BottomShot(val uri: Uri?, val file: File?, val takenAt: Long, val steam: Boolean) {
    val model: Any get() = uri ?: file!!
}

/** Game frames DroidDeck saves to Pictures/DroidDeck, plus the screenshots Steam keeps under userdata. */
object BottomScreenshots {
    private const val FOLDER = "DroidDeck"
    private const val QUALITY = 95
    private const val LIMIT = 120

    internal fun fileName(at: Long): String =
        "DroidDeck_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(at)) + ".jpg"

    fun save(context: Context, bitmap: Bitmap, at: Long = System.currentTimeMillis()): Boolean = runCatching {
        val name = fileName(at)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_TAKEN, at)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            val written = runCatching {
                resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) } == true
            }.getOrDefault(false)
            if (!written) {
                resolver.delete(uri, null, null)
                return false
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER)
            if (!dir.isDirectory && !dir.mkdirs()) return false
            val file = File(dir, name)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            android.media.MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf("image/jpeg"), null)
            true
        }
    }.getOrDefault(false)

    /** Newest first, at most [LIMIT]. */
    fun list(context: Context, runtimeRoot: File): List<BottomShot> =
        (own(context) + steam(runtimeRoot)).sortedByDescending { it.takenAt }.take(LIMIT)

    private fun own(context: Context): List<BottomShot> = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER)
            return dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg", true) }.orEmpty()
                .map { BottomShot(null, it, it.lastModified(), steam = false) }
        }
        val shots = ArrayList<BottomShot>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED),
            "${MediaStore.Images.Media.RELATIVE_PATH} = ?", arrayOf("${Environment.DIRECTORY_PICTURES}/$FOLDER/"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext() && shots.size < LIMIT) {
                val taken = c.getLong(1).takeIf { it > 0L } ?: (c.getLong(2) * 1000L)
                shots += BottomShot(android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)),
                    null, taken, steam = false)
            }
        }
        shots
    }.getOrDefault(emptyList())

    /** The .jpg files in userdata/<account>/760/remote/<app>/screenshots of each Steam home; the thumbnails folder is skipped. */
    internal fun steam(runtimeRoot: File): List<BottomShot> =
        SteamDownloadMonitor.STEAM_HOMES.flatMap { home ->
            File(runtimeRoot, "$home/userdata").listFiles { f -> f.isDirectory }.orEmpty().flatMap { account ->
                File(account, "760/remote").listFiles { f -> f.isDirectory }.orEmpty().flatMap { app ->
                    File(app, "screenshots").listFiles { f -> f.isFile && f.name.endsWith(".jpg", true) }.orEmpty().toList()
                }
            }
        }.map { BottomShot(null, it, it.lastModified(), steam = true) }
}
