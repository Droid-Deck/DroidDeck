package com.droiddeck.launcher.session

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.LruCache
import com.droiddeck.launcher.R
import java.io.File

internal class LinuxWindowIcons(private val context: Context) {
    private val cache = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun load(directory: File, name: String): Bitmap? {
        if (!Regex("[a-f0-9]{64}\\.png").matches(name)) return null
        val size = context.getSystemService(ActivityManager::class.java).launcherLargeIconSize
        val key = "$size:$name"
        cache.get(key)?.let { return it }
        return runCatching {
            val file = File(directory, name)
            if (!file.isFile || file.length() !in 1..1024 * 1024) return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            if (options.outMimeType != "image/png" || options.outWidth !in 1..4096 ||
                options.outHeight !in 1..4096 || options.outWidth.toLong() * options.outHeight > 4 * 1024 * 1024) return null
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            while (maxOf(options.outWidth, options.outHeight) / (options.inSampleSize.coerceAtLeast(1) * 2) >= size)
                options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
            val source = BitmapFactory.decodeFile(file.path, options) ?: return null
            try {
                square(source, size).also { cache.put(key, it) }
            } finally {
                source.recycle()
            }
        }.getOrNull()
    }

    fun clear() = cache.evictAll()

    companion object {
        private val primaryColor = Color.rgb(28, 32, 40)

        internal fun square(source: Bitmap, size: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val scale = size.toFloat() / maxOf(source.width, source.height)
            val width = source.width * scale
            val height = source.height * scale
            val left = (size - width) / 2
            val top = (size - height) / 2
            Canvas(bitmap).drawBitmap(source, null, RectF(left, top, left + width, top + height),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            return bitmap
        }

        @Suppress("DEPRECATION")
        fun description(context: Context, label: String, icon: Bitmap?): ActivityManager.TaskDescription {
            // Through API 36, TaskDescription.Builder only accepts compiled drawable resources.
            if (icon != null) return ActivityManager.TaskDescription(label, icon, primaryColor)
            if (Build.VERSION.SDK_INT >= 33) return ActivityManager.TaskDescription.Builder()
                .setLabel(label).setIcon(R.mipmap.ic_linux_window).setPrimaryColor(primaryColor).build()
            val size = context.getSystemService(ActivityManager::class.java).launcherLargeIconSize
            val fallback = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            context.getDrawable(R.mipmap.ic_linux_window)!!.apply {
                setBounds(0, 0, size, size)
                draw(Canvas(fallback))
            }
            return ActivityManager.TaskDescription(label, fallback, primaryColor)
        }
    }
}
