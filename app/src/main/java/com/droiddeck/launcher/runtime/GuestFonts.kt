package com.droiddeck.launcher.runtime

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * Fonts for every script Steam and the desktop may be shown in.
 *
 * The runtime ships DejaVu and Adwaita only, which have no Chinese, Japanese, Korean or Thai, so
 * Steam drew those as boxes (#118, #206, #249). Android already carries Noto for all of them in
 * /system/fonts; the guest gets that folder read-only at [GUEST_DIR], where fontconfig finds it
 * with the rest of /usr/share/fonts. Nothing is downloaded and the runtime is not changed.
 *
 * One CJK font holds Chinese, Japanese and Korean under one set of characters, and a character
 * they share is drawn the way the chosen region writes it. [preferenceConf] puts that region's
 * variant first, after DejaVu, which stays the font for Latin text.
 */
object GuestFonts {
    const val HOST_DIR = "/system/fonts"
    const val GUEST_DIR = "/usr/share/fonts/android"
    /** Rewritten at every session start, so it follows the device's language. */
    const val CONF = "etc/fonts/conf.d/65-droiddeck-language.conf"

    /** The bind spec for Android's fonts, or null on a device without the folder. */
    @JvmStatic
    fun bindSpec(): String? = File(HOST_DIR).takeIf { it.isDirectory }?.let { "${it.path}:$GUEST_DIR" }

    /** Writes the guest's font preference for the device's current language; best effort. */
    @JvmStatic
    fun prepare(context: Context, root: File) {
        val conf = File(root, CONF)
        val text = preferenceConf(context.resources.configuration.locales[0])
        try {
            if (conf.isFile && conf.readText() == text) return
            conf.parentFile?.mkdirs()
            conf.writeText(text)
        } catch (e: Exception) {
            // fontconfig still finds every font; shared characters just take its own default shape.
        }
    }

    /** The CJK variant whose shapes match how [locale]'s readers write shared characters. */
    fun cjkRegion(locale: Locale): String {
        val script = locale.script
        return when (locale.language) {
            "ja" -> "JP"
            "ko" -> "KR"
            "zh" -> when {
                script == "Hans" -> "SC"
                locale.country == "HK" || locale.country == "MO" -> "HK"
                script == "Hant" || locale.country == "TW" -> "TC"
                else -> "SC"
            }
            else -> "SC"
        }
    }

    fun preferenceConf(locale: Locale): String {
        val region = cjkRegion(locale)
        fun alias(generic: String, latin: String, cjk: String) =
            "  <alias binding=\"same\">\n    <family>$generic</family>\n    <prefer>\n" +
                "      <family>$latin</family>\n      <family>$cjk $region</family>\n" +
                "    </prefer>\n  </alias>\n"
        return "<?xml version=\"1.0\"?>\n<!DOCTYPE fontconfig SYSTEM \"urn:fontconfig:fonts.dtd\">\n" +
            "<!-- Written by DroidDeck at session start for the device's language (${locale.toLanguageTag()}). -->\n" +
            "<fontconfig>\n" +
            alias("sans-serif", "DejaVu Sans", "Noto Sans CJK") +
            alias("serif", "DejaVu Serif", "Noto Serif CJK") +
            alias("monospace", "DejaVu Sans Mono", "Noto Sans Mono CJK") +
            "</fontconfig>\n"
    }
}
