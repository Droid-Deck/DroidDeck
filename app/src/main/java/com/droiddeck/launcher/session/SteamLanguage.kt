package com.droiddeck.launcher.session

import java.util.Locale

/**
 * Steam's name for a language: the API language codes the client and the Steamworks API use
 * ("english", "schinese", "koreana"...), for every language Steam's interface comes in.
 */
object SteamLanguage {
    const val DEFAULT = "english"

    /** Every language the Steam client's interface is offered in. */
    val all: Set<String> = setOf(
        "arabic", "bulgarian", "schinese", "tchinese", "czech", "danish", "dutch", "english",
        "finnish", "french", "german", "greek", "hungarian", "indonesian", "italian", "japanese",
        "koreana", "norwegian", "polish", "portuguese", "brazilian", "romanian", "russian",
        "spanish", "latam", "swedish", "thai", "turkish", "ukrainian", "vietnamese", "malay",
    )

    /** Spanish-speaking regions Steam serves with Latin American Spanish rather than Spain's. */
    private val latinAmerica = setOf(
        "419", "AR", "BO", "CL", "CO", "CR", "CU", "DO", "EC", "GT", "HN", "MX", "NI", "PA", "PE",
        "PR", "PY", "SV", "US", "UY", "VE",
    )

    /**
     * Steam's language for [locale]. [region] stands in for the locale's own when it has none: the
     * app's Spanish carries no region, and a player in Mexico should get Latin American Spanish.
     */
    fun forLocale(locale: Locale, region: String? = null): String {
        val country = locale.country.ifEmpty { region.orEmpty() }
        return when (locale.language) {
            "ar" -> "arabic"
            "bg" -> "bulgarian"
            "cs" -> "czech"
            "da" -> "danish"
            "nl" -> "dutch"
            "en" -> "english"
            "fi" -> "finnish"
            "fr" -> "french"
            "de" -> "german"
            "el" -> "greek"
            "hu" -> "hungarian"
            "id", "in" -> "indonesian"
            "it" -> "italian"
            "ms" -> "malay"
            "ja" -> "japanese"
            "ko" -> "koreana"
            "nb", "no", "nn" -> "norwegian"
            "pl" -> "polish"
            "pt" -> if (country == "BR") "brazilian" else "portuguese"
            "ro" -> "romanian"
            "ru" -> "russian"
            "es" -> if (country in latinAmerica) "latam" else "spanish"
            "sv" -> "swedish"
            "th" -> "thai"
            "tr" -> "turkish"
            "uk" -> "ukrainian"
            "vi" -> "vietnamese"
            "zh" -> when {
                locale.script == "Hant" -> "tchinese"
                locale.script == "Hans" -> "schinese"
                country == "TW" || country == "HK" || country == "MO" -> "tchinese"
                else -> "schinese"
            }
            else -> DEFAULT
        }
    }
}
