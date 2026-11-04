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
    private const val KEY_ART_ASSETS = "https://blz-contentstack-images.akamaized.net/v3/assets/bltf408a0557f4e4998/"
    private const val KEY_ART_CATALOG = "https://catalog.blzstatic.com/"
    internal val KEY_ART = mapOf(
        "WoW" to "${KEY_ART_ASSETS}blt80af93933ab07df3/68b8bf2d804a9776216cd63e/Midnight_keyart.png",
        "WoWC" to "${KEY_ART_ASSETS}blt6a80c8e7260f84f2/690d2219b415dd246153245b/BCC.png",
        "WoWF" to "${KEY_ART_ASSETS}blt1d58537e928b17c5/6ae2c99668c747dd1b4f558b/WoW_Forever_AnimatedTrailerSupportAssets_BnetShop_1920x1080.png",
        "D3" to "${KEY_ART_CATALOG}/f96/8d92f4d8bd316d3787f02d8a86935d6a-Store_Product_960x540_D3.jpg",
        "D3CN" to "${KEY_ART_CATALOG}/f96/8d92f4d8bd316d3787f02d8a86935d6a-Store_Product_960x540_D3.jpg",
        "S2" to "${KEY_ART_ASSETS}blt4b6d6857f147a6de/61254804841f180993fedb61/6885-32192.png",
        "S1" to "${KEY_ART_ASSETS}blt03d69a4563a6ae63/6a34b50f799ece2cb2da248c/9458-28298.jpg",
        "WTCG" to "${KEY_ART_ASSETS}blt5a3d84e1d0b2bbcf/662aea9f842cecfd4fd95327/HS_Key_Art_SG2.png",
        "Hero" to "${KEY_ART_ASSETS}blt73de967d17ca81ca/67db7dea931b380b4c76239a/Hanamura_Loading_Screen7.png",
        "W3" to "${KEY_ART_ASSETS}blt66c211421d2c5495/6a7e66d8dee8846a67cba6fa/WC_Rebirth_Announce_BnetShop_ComparsionCard_Reforged_1920x1080.png",
        "VIPR" to "${KEY_ART_ASSETS}bltd11d425fcbbe3c46/63eecbbd1e040260dfbede83/BO4_(2).png",
        "ODIN" to "${KEY_ART_CATALOG}/ffe/5e9926e8de75095fa953b8e71aea966c-_Kronos-Bnet_Game-Card_Feature_3A_Standard-960x540.jpg",
        "LAZR" to "${KEY_ART_CATALOG}/7f/ec4770b465f107f24f9a46a66d163ab9-Lazarus-Bnet_Placeholder-Card_PDG-960x540-For_20200331.jpg",
        "ZEUS" to "${KEY_ART_ASSETS}blt3e4e126b8153648b/63eeca6b998e686b2e53b748/CW-keyArt-thumb.jpg",
        "FORE" to "${KEY_ART_ASSETS}bltff4d6ec7afd2e49a/63f695ff583a5b674fbcde69/CODVG_Reveal_Ultimate_Keyart_Textless-Bnet-Launcher_Content_UI_(Phoenix)-1920x1080_01a_FINAL.jpg",
        "AUKS" to "${KEY_ART_ASSETS}bltd31831eda9199937/62abc0df5cbe472513c0ce59/Cortez_Base_Game-Bnet_Game-Card_Feature-960x540.jpg",
        "PNTA" to "${KEY_ART_ASSETS}bltd6fa384b60ff3fbd/64cc2fe367e57a3aa2cec653/Jupiter_Coming_Soon-Bnet_Game-Content_UI_(Phoenix)-EN-1920x1080_textless.jpg",
        "WLBY" to "${KEY_ART_ASSETS}blt861e34883e31a113/63eec7f166192b60e0205205/CB4_Launch_Keyart_CTA_02-Bnet-Launcher_Launcher_2.0-1920x1080_textless.jpg",
        "RTRO" to "${KEY_ART_ASSETS}bltc2737ca8175c8330/620d7693f2a06e30446fa9c7/BCO_PR_Shop_Assets_prod-thumb_TS01.jpg",
        "ANBS" to "${KEY_ART_ASSETS}bltcd126fd51eb09be1/6a21ee1fc451656b3d6841b5/DI_Art_Warlock-Login_illustration_(all_classes)-Illustration_(2).png",
        "Fen" to "${KEY_ART_ASSETS}blt8de255bf153ad1e2/63d0275cd791066264c678f4/en_D4_pre-purchase_blog_1920x1080_Primary_TS01.jpg",
        "D1" to "${KEY_ART_ASSETS}bltf183b52d38f5d94e/65a089802357a8ed25a05da1/blizzard_diablo_launch_desktop_shopproductasset_bchbnet_stat_enus_1920x1080_EE01.png",
        "W1" to "${KEY_ART_ASSETS}blt20214ec16801085a/65a07418cd72b186912c1fac/blizzard_warcraft1_launch_desktop_shopproductasset_bchbnet_stat_enus_1920x1080_EE01.png",
        "W2" to "${KEY_ART_ASSETS}blt45a874eacd7a27e4/65a08bf2db6bb9361a1fbc63/blizzard_warcraft2_launch_desktop_shopproductasset_bchbnet_stat_enus_1920x1080_EE01.png",
        "W1R" to "${KEY_ART_CATALOG}/7d/405b3c58ecc70c495dcf4b333cdd9e01-WC01_keyArt_finalcopy.png",
        "W2R" to "${KEY_ART_CATALOG}/7d/924e7c287781c84865b662d20029680c-WC02_KeyArt_finalcopyV2.png",
        "GRY" to "${KEY_ART_ASSETS}bltd22bde30bb1b973e/652d87d52c84c8e75f881b5f/SplashAnims_landscape_1.png",
        "ARIS" to "${KEY_ART_ASSETS}bltc5face49eea05c7e/67ca48cb4b61d73198918ba6/shop_product_feature_card_aries_standard.webp",
        "SCOR" to "${KEY_ART_ASSETS}blt82dbffc2b51e8aa1/67a562cc7113284f3b17ff7d/phx_notification_browsing_card_scorpio_announce.webp",
        "ARK" to "${KEY_ART_ASSETS}blt4a061067c30285dc/6837812aedfd13b46a16c38b/shop_asset_gallery_card_arkansas_wishlist.webp",
        "AQUA" to "${KEY_ART_ASSETS}blte982469df0746ee4/672ea9f71dee4754a60cde05/shop_product_edition_card_avowed_standard.jpg",
    )

    internal fun imageUrl(page: String): String? {
        val og = OG.find(page)?.groupValues?.get(1)?.takeUnless { "youtube" in it || "maxresdefault" in it }
        val thumbs = THUMB.findAll(page).map { it.groupValues[1] }.filter { "_bundle" !in it }.map { "https:$it" }
        return (sequenceOf(og) + thumbs).filterNotNull().firstOrNull(::allowed)
    }

    internal fun allowed(url: String): Boolean {
        val host = runCatching { URL(url).host }.getOrNull() ?: return false
        return HOSTS.any { h -> host == h.trimStart('.') || host.endsWith(h) }
    }

    private fun image(url: String): Bitmap? {
        val bytes = get(URL(url.substringBefore('?') + "?imwidth=$WIDE"), MAX_IMAGE, false) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun shopImage(slug: String): Bitmap? {
        val page = get(URL(SHOP + slug), MAX_PAGE, true)?.toString(Charsets.UTF_8) ?: return null
        return imageUrl(page)?.let(::image)
    }

    fun fetch(code: String, slug: String, wide: File, cover: File?): Boolean {
        val art = KEY_ART[code]?.let(::image) ?: shopImage(slug) ?: return false
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
