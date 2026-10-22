package com.droiddeck.launcher.stores

import android.content.Context
import com.droiddeck.launcher.core.AppLanguage
import com.droiddeck.launcher.session.SessionPrefs
import org.json.JSONArray
import java.util.Locale

/**
 * Language for a third-party store install ([Store.GOG], [Store.EPIC]): the app/system locale by
 * default, or the code the user picked in the install dialog / store settings.
 *
 * GOG depots and Epic install tags use overlapping but not identical codes (`en` vs `en-US`,
 * `nb` vs `no`). Matching is on the language part plus a few aliases, so a Polish phone selects
 * `pl-PL` depots without the user having to know the store's spelling.
 */
object StoreLanguage {
    const val EXTRA = "installLanguage"

    /** Codes the settings list and Epic's tag table share; GOG often uses the same with a region. */
    val KNOWN: List<String> = listOf(
        "en", "pl", "de", "fr", "es", "es-419", "pt", "pt-BR", "it", "ru", "uk", "cs", "hu",
        "nl", "sv", "da", "fi", "nb", "ro", "bg", "el", "tr", "ar", "th", "vi", "ja", "ko",
        "zh-Hans", "zh-Hant",
    )

    private val ALIASES = mapOf(
        "english" to "en", "polish" to "pl", "german" to "de", "french" to "fr", "spanish" to "es",
        "italian" to "it", "russian" to "ru", "ukrainian" to "uk", "czech" to "cs", "hungarian" to "hu",
        "dutch" to "nl", "swedish" to "sv", "danish" to "da", "finnish" to "fi", "norwegian" to "nb",
        "no" to "nb", "romanian" to "ro", "bulgarian" to "bg", "greek" to "el", "turkish" to "tr",
        "arabic" to "ar", "thai" to "th", "vietnamese" to "vi", "japanese" to "ja", "korean" to "ko",
        "schinese" to "zh-hans", "tchinese" to "zh-hant", "chinese" to "zh-hans",
        "brazilian" to "pt-br", "portuguese" to "pt",
    )

    fun selectable(store: Store): Boolean = store == Store.GOG || store == Store.EPIC

    /** Tag actually sent to the store: settings override, else the app/system locale. */
    @JvmStatic
    fun preferredCode(context: Context): String {
        val chosen = SessionPrefs.storesInstallLanguage(context)
        if (chosen.isNotEmpty()) return chosen
        return tagFor(AppLanguage.effective(context))
    }

    @JvmStatic
    fun preferredCode(): String = tagFor(Locale.getDefault())

    fun tagFor(locale: Locale): String {
        val language = locale.language.ifEmpty { "en" }
        val country = locale.country
        val script = locale.script
        return when {
            language == "zh" && (script == "Hant" || country == "TW" || country == "HK" || country == "MO") -> "zh-Hant"
            language == "zh" -> "zh-Hans"
            language == "pt" && (country.isEmpty() || country == "BR") -> "pt-BR"
            language == "es" && country.isNotEmpty() && country != "ES" -> "es-419"
            country.isNotEmpty() -> "$language-$country"
            else -> language
        }
    }

    fun localeFor(code: String?, context: Context): Locale {
        val tag = code?.takeIf { it.isNotBlank() } ?: preferredCode(context)
        return Locale.forLanguageTag(tag.replace('_', '-'))
    }

    fun fallbackList(store: Store): List<String> = if (selectable(store)) KNOWN else emptyList()

    /**
     * One code from [available]. An exact normalized code wins (`pl-PL` over `pl`). A base-language
     * match is used only when that exact code is not offered, then English, then the first code.
     */
    @JvmStatic
    fun pick(available: List<String>, preferred: String): String {
        if (available.isEmpty()) return preferred
        val want = primary(preferred) ?: return available.first()
        return available.firstOrNull { primary(it) == want }
            ?: available.firstOrNull { depotAccepted(arrayOf(it), preferred) }
            ?: available.firstOrNull { depotAccepted(arrayOf(it), "en") }
            ?: available.first()
    }

    /**
     * True when a GOG depot whose `languages` array is [listed] overlaps [chosen], including a
     * base-language match (`pl` accepts `pl-PL`). Shared depots (`*`, empty, missing) always match.
     * Install selection uses [depotMatchesResolved] after [pick] has chosen a single code.
     */
    @JvmStatic
    fun depotAccepted(listed: Array<String>?, chosen: String): Boolean {
        if (listed.isNullOrEmpty()) return true
        val want = tokens(chosen)
        return listed.any { have ->
            val parts = tokens(have)
            parts.contains("*") || parts.any { it in want }
        }
    }

    /**
     * True when [listed] is shared, or contains the one code [resolved] already selected.
     * `pl-PL` does not also keep a `pl` depot.
     */
    @JvmStatic
    fun depotMatchesResolved(listed: Array<String>?, resolved: String): Boolean {
        if (listed.isNullOrEmpty()) return true
        if (listed.any { it.trim() == "*" }) return true
        val want = primary(resolved) ?: return false
        return listed.any { primary(it) == want }
    }

    /** Normalized code without collapsing to the base language. `pl-PL` stays `pl-pl`. */
    internal fun primary(code: String): String? {
        val raw = code.trim().lowercase().replace('_', '-')
        if (raw.isEmpty()) return null
        if (raw == "*") return "*"
        return ALIASES[raw] ?: raw
    }

    @JvmStatic
    fun codesFromDepots(depots: JSONArray?): List<String> {
        if (depots == null) return emptyList()
        val seen = LinkedHashSet<String>()
        for (i in 0 until depots.length()) {
            val langs = depots.optJSONObject(i)?.optJSONArray("languages") ?: continue
            for (j in 0 until langs.length()) {
                val raw = langs.optString(j, "").trim()
                if (raw.isEmpty() || raw == "*") continue
                val key = raw.lowercase()
                if (seen.none { it.lowercase() == key }) seen.add(raw)
            }
        }
        return seen.toList()
    }

    fun displayName(code: String): String {
        if (code.isEmpty()) return ""
        val locale = Locale.forLanguageTag(code.replace('_', '-'))
        AppLanguage.supportedTag(locale)?.let { return AppLanguage.nativeName(it) }
        val name = locale.getDisplayName(locale)
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }

    internal fun tokens(code: String): Set<String> {
        val raw = code.trim().lowercase().replace('_', '-')
        if (raw.isEmpty()) return emptySet()
        if (raw == "*") return setOf("*")
        val mapped = ALIASES[raw] ?: raw
        val lang = mapped.substringBefore('-').let { ALIASES[it] ?: it }
        val out = mutableSetOf(mapped, lang)
        if (mapped.startsWith("zh-hant") || mapped == "zh-tw" || mapped == "zh-hk") {
            out.add("zh-hant"); out.add("zh")
        }
        if (mapped.startsWith("zh-hans") || mapped == "zh-cn") {
            out.add("zh-hans"); out.add("zh")
        }
        if (mapped == "pt-br" || mapped == "br") out.add("pt-br")
        return out
    }
}
