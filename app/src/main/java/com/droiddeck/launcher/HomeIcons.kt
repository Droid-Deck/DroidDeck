package com.droiddeck.launcher

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object HomeIcons {
    private const val PREFS = "home_launcher"
    private const val KEY = "icon_overrides"
    private const val SEPARATOR = '\t'

    data class Choice(val pack: String, val drawable: String)

    var overrides by mutableStateOf<Map<String, Choice>>(emptyMap())
        private set
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        overrides = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet()).orEmpty().mapNotNull(::decode).toMap()
    }

    fun set(context: Context, key: String, pack: String, drawable: String) = save(context, overrides + (key to Choice(pack, drawable)))

    fun clear(context: Context, key: String) = save(context, overrides - key)

    fun clearAll(context: Context) = save(context, emptyMap())

    private fun save(context: Context, next: Map<String, Choice>) {
        overrides = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY, next.map { (key, choice) -> encode(key, choice) }.toSet()).apply()
    }

    internal fun encode(key: String, choice: Choice) = listOf(key, choice.pack, choice.drawable).joinToString(SEPARATOR.toString())

    internal fun decode(entry: String): Pair<String, Choice>? {
        val parts = entry.split(SEPARATOR)
        if (parts.size != 3 || parts.any { it.isEmpty() }) return null
        return parts[0] to Choice(parts[1], parts[2])
    }
}
