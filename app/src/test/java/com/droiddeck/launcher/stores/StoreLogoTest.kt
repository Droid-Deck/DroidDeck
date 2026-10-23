package com.droiddeck.launcher.stores

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.droiddeck.launcher.stores.amazon.AmazonLibrary
import com.droiddeck.launcher.stores.gog.GogBackend
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoreLogoTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun encoded(format: Bitmap.CompressFormat): ByteArray {
        val bmp = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)
        bmp.setPixel(1, 1, Color.RED)
        return ByteArrayOutputStream().also { bmp.compress(format, 100, it) }.toByteArray()
    }

    @Test fun aPngLogoIsKeptAsItCameWithItsTransparency() {
        val png = encoded(Bitmap.CompressFormat.PNG)
        val dst = File(tmp.root, StoreArt.LOGO)
        assertTrue(StoreArt.writeLogo(png, dst))
        assertArrayEquals(png, dst.readBytes())
        val back = BitmapFactory.decodeFile(dst.path)
        assertEquals(0, Color.alpha(back.getPixel(0, 0)))
        assertEquals(Color.RED, back.getPixel(1, 1))
        assertFalse(File(dst.path + ".tmp").exists())
    }

    @Test fun anotherImageFormatIsStoredAsPng() {
        val dst = File(tmp.root, StoreArt.LOGO)
        assertTrue(StoreArt.writeLogo(encoded(Bitmap.CompressFormat.JPEG), dst))
        val head = dst.readBytes().take(4)
        assertEquals(listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), head)
        assertEquals(8, BitmapFactory.decodeFile(dst.path).width)
    }

    @Test fun whatIsNotAnImageIsRefusedAndAnExistingLogoStays() {
        val dst = File(tmp.root, StoreArt.LOGO)
        assertFalse(StoreArt.writeLogo("<html>not found</html>".toByteArray(), dst))
        assertFalse(dst.exists())
        dst.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(StoreArt.writeLogo(encoded(Bitmap.CompressFormat.PNG), dst))
        assertArrayEquals(byteArrayOf(1, 2, 3), dst.readBytes())
        assertTrue(StoreArt.fetchLogo(tmp.root, "https://invalid.example/logo.png"))
    }

    @Test fun gogTakesTheV2LogoLinkOnly() {
        val v2 = JSONObject("""{"_links":{"logo":{"href":"https://images.gog-statics.com/abc_logo.png"},"galaxyBackgroundImage":{"href":"https://x/bg.jpg"}}}""")
        assertEquals("https://images.gog-statics.com/abc_logo.png", GogBackend.logoOf(v2))
        assertEquals("https://images.gog-statics.com/rel.png", GogBackend.logoOf(JSONObject("""{"_links":{"logo":{"href":"//images.gog-statics.com/rel.png"}}}""")))
        assertNull(GogBackend.logoOf(JSONObject("""{"_links":{}}""")))
        assertNull(GogBackend.logoOf(JSONObject("""{"images":{"logo2x":"https://x/glx_logo_2x.jpg"}}""")))
        assertNull(GogBackend.logoOf(JSONObject("""{"_links":{"logo":{"href":"http://x/logo.png"}}}""")))
    }

    @Test fun amazonKeepsTheLogoFromTheLibraryAndItsCache() {
        val app = RuntimeEnvironment.getApplication()
        val fresh = AmazonLibrary.gameFrom(JSONObject().put("productId", "p").put("title", "Game").put("logo", "https://m/logo.png"), null)
        assertEquals("https://m/logo.png", fresh.logoUrl)
        assertEquals("https://m/logo.png", AmazonLibrary.toCatalogItem(app, fresh).logoUrl)
        assertEquals(listOf(fresh), AmazonLibrary.fromCacheJson(AmazonLibrary.toCacheJson(listOf(fresh))))
        val kept = AmazonLibrary.gameFrom(JSONObject().put("productId", "p").put("title", "Game"), fresh)
        assertEquals("https://m/logo.png", kept.logoUrl)
        val none = AmazonLibrary.gameFrom(JSONObject().put("productId", "q").put("title", "Other"), null)
        assertNull(AmazonLibrary.toCatalogItem(app, none).logoUrl)
        assertEquals("", AmazonLibrary.fromCacheJson("""[{"productId":"old","title":"Old"}]""").single().logoUrl)
    }
}
