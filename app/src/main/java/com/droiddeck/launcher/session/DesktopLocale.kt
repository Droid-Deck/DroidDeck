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

    fun lang(locale: Locale): String {
        val language = when (locale.language) {
            "in" -> "id"
            "no", "nn" -> "nb"
            else -> locale.language
        }
        val region = when {
            language == "es" && SteamLanguage.forLocale(locale) == "latam" ->
                locale.country.takeIf { it.length == 2 && it != "US" } ?: "MX"
            language == "pt" && locale.country == "BR" -> "BR"
            language == "zh" && (locale.country == "TW" || locale.country == "HK" || locale.script == "Hant") ->
                if (locale.country == "HK") "HK" else "TW"
            else -> regions[language] ?: return DEFAULT
        }
        return "${language}_$region.UTF-8"
    }

    fun language(locale: Locale): String? {
        val lang = lang(locale)
        if (lang == DEFAULT) return null
        val full = lang.removeSuffix(".UTF-8")
        val short = full.substringBefore('_')
        return if (short == "en") full else "$full:$short"
    }
}
