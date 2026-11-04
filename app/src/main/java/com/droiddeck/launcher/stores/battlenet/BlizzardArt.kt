package com.droiddeck.launcher.stores.battlenet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL

internal object BlizzardArt {
    private const val SHOP = "https://us.shop.battle.net/en-us/product/"
    private const val MAX_PAGE = 16 shl 20
    private const val MAX_IMAGE = 12 shl 20
    private const val WIDE = 1280
    private val OG = Regex("""<meta property="og:image" content="(https://[^"]+)"""")
    private val THUMB = Regex("""\\?"imageUrl\\?":\\?"(//catalog\.blzstatic\.com/[^"\\]+)""")
    private val HOSTS = listOf(".blzstatic.com", ".blz-contentstack.com", "blz-contentstack-images.akamaized.net")

    internal fun imageUrl(page: String): String? {
        val og = OG.find(page)?.groupValues?.get(1)?.takeUnless { "youtube" in it || "maxresdefault" in it }
        val thumbs = THUMB.findAll(page).map { it.groupValues[1] }.filter { "_bundle" !in it }.map { "https:$it" }
        return (sequenceOf(og) + thumbs).filterNotNull().firstOrNull(::allowed)
    }

    private fun allowed(url: String): Boolean {
        val host = runCatching { URL(url).host }.getOrNull() ?: return false
        return HOSTS.any { h -> host == h.trimStart('.') || host.endsWith(h) }
    }

    fun fetch(slug: String, wide: File, cover: File?): Boolean {
        val page = get(URL(SHOP + slug), MAX_PAGE, true)?.toString(Charsets.UTF_8) ?: return false
        val url = imageUrl(page) ?: return false
        val bytes = get(URL(url.substringBefore('?') + "?imwidth=$WIDE"), MAX_IMAGE, false) ?: return false
        val art = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        try {
            val scaled = if (art.width > WIDE) Bitmap.createScaledBitmap(art, WIDE, art.height * WIDE / art.width, true) else art
            save(scaled, wide)
            if (cover != null) {
                val w = minOf(scaled.width, scaled.height * 2 / 3)
                val crop = Bitmap.createBitmap(scaled, (scaled.width - w) / 2, 0, w, w * 3 / 2)
                save(Bitmap.createScaledBitmap(crop, 600, 900, true), cover)
            }
            return true
        } finally {
            art.recycle()
        }
    }

    private fun save(bitmap: Bitmap, file: File) {
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) throw IOException("not saved") }
        if (!tmp.renameTo(file)) throw IOException("not kept")
    }

    private fun get(start: URL, limit: Int, follow: Boolean): ByteArray? {
        val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        var url = start
        repeat(8) {
            if (url.protocol != "https") return null
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "DroidDeck")
                cookies.get(url.toURI(), emptyMap()).forEach { (k, v) -> if (v.isNotEmpty()) setRequestProperty(k, v.joinToString("; ")) }
            }
            try {
                val code = conn.responseCode
                cookies.put(url.toURI(), conn.headerFields)
                when {
                    code == 200 -> return conn.inputStream.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > limit) return null
                        }
                        out.toByteArray()
                    }
                    follow && code in 301..308 -> url = URL(url, conn.getHeaderField("Location") ?: return null)
                    else -> return null
                }
            } finally {
                conn.disconnect()
            }
        }
        return null
    }
}
