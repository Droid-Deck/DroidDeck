package com.droiddeck.launcher.stores.epic

import org.json.JSONObject
import java.util.Locale

object EpicInstallTags {
    private class Answer(val locale: Locale, val tags: List<String>, val launchLocale: String)

    @Volatile private var last: Answer? = null

    private fun answer(locale: Locale): Answer? {
        last?.takeIf { it.locale == locale }?.let { return it }
        val call = EpicNative.call("locale", JSONObject().put("language", locale.language).put("region", locale.country)) ?: return null
        val list = call.optJSONArray("tags")
        val tags = List(list?.length() ?: 0) { list!!.optString(it) }.filter { it.isNotEmpty() }
        return Answer(locale, tags, call.optString("launchLocale", "en").ifEmpty { "en" }).also { last = it }
    }

    fun tagsFor(locale: Locale): List<String> = answer(locale)?.tags ?: emptyList()

    fun launchLocale(locale: Locale): String = answer(locale)?.launchLocale ?: "en"

    @JvmStatic
    fun tagsForDevice(): List<String> = tagsFor(Locale.getDefault())

    fun launchLocale(): String = launchLocale(Locale.getDefault())
}
