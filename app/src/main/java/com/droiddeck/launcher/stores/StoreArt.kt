package com.droiddeck.launcher.stores

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The store's art, placed in the game's folder under the names AddedGameArt already looks for -
 * `cover.jpg` (the portrait capsule), `hero.jpg` and `header.jpg` (the wide art) - so a store game
 * shows real art in the Games tab and the session's shortcuts writer copies it into the client's
 * grid. Each image is decoded and re-encoded as an opaque JPEG over a dark ground: a store's PNG
 * with an alpha fade would otherwise end in white, which is what the washed-out cards were.
 */
object StoreArt {
    private const val TAG = "StoreArt"

    /** Fetches what the sidecar names; a missing or unreadable image is skipped, never an error. Blocking. */
    fun fetchInto(folder: File, sidecar: StoreGameSidecar) {
        sidecar.cover?.let { place(it, File(folder, "cover.jpg")) }
        val wide = sidecar.hero ?: return
        if (place(wide, File(folder, "hero.jpg"))) {
            val header = File(folder, "header.jpg")
            if (!header.isFile) runCatching { File(folder, "hero.jpg").copyTo(header, overwrite = true) }
        }
    }

    private fun place(url: String, dst: File): Boolean {
        if (dst.isFile && dst.length() > 0) return true
        val bytes = get(url) ?: return false
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return false
        val opaque = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply { drawColor(Color.rgb(0x12, 0x14, 0x17)); drawBitmap(bitmap, 0f, 0f, null) }
        val tmp = File(dst.path + ".tmp")
        return try {
            tmp.outputStream().use { opaque.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            tmp.renameTo(dst)
        } catch (e: Exception) {
            Log.w(TAG, "${dst.name}: ${e.javaClass.simpleName}")
            tmp.delete()
            false
        } finally {
            bitmap.recycle(); opaque.recycle()
        }
    }

    private fun get(url: String): ByteArray? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 20_000; setRequestProperty("User-Agent", StoreNet.BROWSER_UA) }
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "${StoreLog.redactUrl(url)}: ${e.javaClass.simpleName}")
            null
        } finally {
            c?.disconnect()
        }
    }
}
