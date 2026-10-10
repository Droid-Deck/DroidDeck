package com.droiddeck.launcher.stores.epic

import java.util.Locale

/**
 * Epic tags a manifest's files by language so a client installs the base files plus one language
 * instead of every language. The table (the launcher's tag names per language) is Legendary's;
 * the language is the device's locale, English when it is not one Epic ships.
 */
object EpicInstallTags {
    private val LANGUAGE_TO_TAGS: Map<String, List<String>> = mapOf(
        "ar" to listOf("Arabic", "ar"), "bg" to listOf("Bulgarian", "bg-BG", "bg"),
        "zh-Hans" to listOf("Chinese", "ChineseSimplified", "zh-Hans", "zh_Hans", "zh"), "zh-Hant" to listOf("ChineseTraditional", "zh-Hant", "zh_Hant"),
        "cs" to listOf("Czech", "cs-CZ", "cs"), "da" to listOf("Danish", "da-DK", "da"), "nl" to listOf("Dutch", "nl-NL", "nl"),
        "en" to listOf("English", "en-US", "en"), "fi" to listOf("Finnish", "fi-FI", "fi"), "fr" to listOf("French", "fr-FR", "fr"),
        "de" to listOf("German", "de-DE", "de"), "el" to listOf("Greek", "el-GR", "el"), "hu" to listOf("Hungarian", "hu-HU", "hu"),
        "it" to listOf("Italian", "it-IT", "it"), "ja" to listOf("Japanese", "ja-JP", "ja"), "ko" to listOf("Korean", "ko-KR", "ko"),
        "no" to listOf("Norwegian", "nb-NO", "no"), "pl" to listOf("Polish", "pl-PL", "pl"), "pt" to listOf("Portuguese", "pt-PT", "pt"),
        "pt-BR" to listOf("PortugueseBrazilian", "Brazilian", "pt-BR", "br"), "ro" to listOf("Romanian", "ro-RO", "ro"),
        "ru" to listOf("Russian", "ru-RU", "ru"), "es" to listOf("Spanish", "es-ES", "es"), "es-419" to listOf("SpanishLatinAmerica", "Latam", "es-MX", "es_mx"),
        "sv" to listOf("Swedish", "sv-SE", "sv"), "th" to listOf("Thai", "th-TH", "th"), "tr" to listOf("Turkish", "tr-TR", "tr"),
        "uk" to listOf("Ukrainian", "uk-UA", "uk"), "vi" to listOf("Vietnamese", "vi-VN", "vi"),
    )

    private fun key(locale: Locale): String? {
        val l = locale.language.lowercase(Locale.ROOT)
        val r = locale.country.uppercase(Locale.ROOT)
        return when (l) {
            "zh" -> if (r == "TW" || r == "HK" || r == "MO") "zh-Hant" else "zh-Hans"
            "pt" -> if (r == "BR") "pt-BR" else "pt"
            "es" -> if (r == "ES" || r.isEmpty()) "es" else "es-419"
            "nb", "nn" -> "no"
            else -> if (LANGUAGE_TO_TAGS.containsKey(l)) l else null
        }
    }

    /** The install tags for the device's language; English when Epic has no tags for it. Never empty. */
    @JvmStatic
    fun tagsForDevice(): List<String> = LANGUAGE_TO_TAGS[key(Locale.getDefault()) ?: "en"] ?: LANGUAGE_TO_TAGS.getValue("en")

    /** The two-letter code handed to the game as -epiclocale; "en" when the language is not one Epic knows. */
    @JvmStatic
    fun localeCodeForDevice(): String = key(Locale.getDefault())?.substringBefore('-') ?: "en"
}
