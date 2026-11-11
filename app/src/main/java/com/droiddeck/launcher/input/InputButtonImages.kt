package com.droiddeck.launcher.input

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.UUID

internal object InputButtonImages {
    const val DIR = "screenkeys"
    private const val MAX_SIZE_DP = 140

    fun file(context: Context, name: String): File? =
        name.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,128}")) && it != "." && it != ".." }?.let { File(File(context.filesDir, DIR), it) }

    fun import(context: Context, uri: Uri): String? {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val name = "key_" + UUID.randomUUID() + ".png"
        val longest = (MAX_SIZE_DP * context.resources.displayMetrics.density).toInt()
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val source = maxOf(bounds.outWidth, bounds.outHeight)
            if (source <= 0) return@runCatching null
            var sample = 1
            while (source / (sample * 2) >= longest) sample *= 2
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching null
            val scale = longest.toFloat() / maxOf(bitmap.width, bitmap.height)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
            try {
                val saved = File(dir, name).outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (saved) name else null
            } finally {
                if (scaled !== bitmap) bitmap.recycle()
                scaled.recycle()
            }
        }.getOrNull().also { if (it == null) File(dir, name).delete() }
    }
}
