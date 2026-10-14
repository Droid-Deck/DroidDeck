package com.droiddeck.launcher.stores.epic

import org.json.JSONObject
import java.util.Locale

object EpicInstallTags {
    private class Answer(val locale: Locale, val tags: List<String>, val launchLocale: String)

    @Volatile private var last: Answer? = null

    private fun current(): Answer? {
        val locale = Locale.getDefault()
        last?.takeIf { it.locale == locale }?.let { return it }
        val answer = EpicNative.call("locale", JSONObject().put("language", locale.language).put("region", locale.country)) ?: return null
        val list = answer.optJSONArray("tags")
        val tags = List(list?.length() ?: 0) { list!!.optString(it) }.filter { it.isNotEmpty() }
        return Answer(locale, tags, answer.optString("launchLocale", "en").ifEmpty { "en" }).also { last = it }
    }

    @JvmStatic
    fun tagsForDevice(): List<String> = current()?.tags ?: emptyList()

    fun launchLocale(): String = current()?.launchLocale ?: "en"
}
