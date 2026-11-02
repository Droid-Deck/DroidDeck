package com.droiddeck.launcher.session

import java.util.Locale

object DesktopLocale {
    const val DEFAULT = "C.UTF-8"

    private val regions = mapOf(
        "en" to "US", "id" to "ID", "ms" to "MY", "cs" to "CZ", "da" to "DK", "de" to "DE",
        "es" to "ES", "fr" to "FR", "it" to "IT", "hu" to "HU", "nl" to "NL", "nb" to "NO",
        "pl" to "PL", "pt" to "PT", "ro" to "RO", "fi" to "FI", "sv" to "SE", "vi" to "VN",
        "tr" to "TR", "el" to "GR", "bg" to "BG", "ru" to "RU", "uk" to "UA", "ar" to "EG",
        "hi" to "IN", "th" to "TH", "ja" to "JP", "ko" to "KR", "zh" to "CN",
    )

    private val systemRegions = mapOf(
        "en" to setOf("US", "GB", "AU", "CA", "IN", "NZ", "IE", "ZA", "SG"),
        "fr" to setOf("FR", "CA", "BE", "CH", "LU"),
        "de" to setOf("DE", "AT", "CH", "LU"),
        "it" to setOf("IT", "CH"),
        "nl" to setOf("NL", "BE"),
        "pt" to setOf("PT", "BR"),
        "sv" to setOf("SE", "FI"),
        "ar" to setOf("EG", "SA", "AE", "MA", "DZ", "TN", "JO", "KW", "IQ"),
    )

    private val traditionalRegions = setOf("TW", "HK", "MO")

    fun lang(locale: Locale): String {
        val language = when (locale.language) {
            "in" -> "id"
            "no", "nn" -> "nb"
            else -> locale.language
        }
        val region = when {
            language == "es" && SteamLanguage.forLocale(locale) == "latam" ->
                locale.country.takeIf { it.length == 2 && it != "US" } ?: "MX"
            language == "zh" -> chineseRegion(locale)
            locale.country in systemRegions[language].orEmpty() -> locale.country
            else -> regions[language] ?: return DEFAULT
        }
        return "${language}_$region.UTF-8"
    }

    private fun chineseRegion(locale: Locale): String = when {
        locale.script == "Hans" -> "CN"
        locale.script == "Hant" -> if (locale.country == "HK") "HK" else "TW"
        locale.country == "HK" -> "HK"
        locale.country in traditionalRegions -> "TW"
        else -> "CN"
    }

    fun language(locale: Locale): String? {
        val lang = lang(locale)
        if (lang == DEFAULT) return null
        val full = lang.removeSuffix(".UTF-8")
        val short = full.substringBefore('_')
        return when {
            short == "en" -> full
            full == "zh_HK" -> "zh_HK:zh_TW:zh"
            else -> "$full:$short"
        }
    }
}
