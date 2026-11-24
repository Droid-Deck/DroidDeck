package com.droiddeck.launcher.agent

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/** The text typed in the Agent tab and not yet sent, kept across leaving the tab and restarts. */
object AgentDraft {
    private const val KEY = "draft"
    private var state: MutableState<String>? = null

    fun state(context: Context): MutableState<String> = state ?: run {
        val prefs = AgentHost.prefs(context.applicationContext)
        val backing = mutableStateOf(prefs.getString(KEY, "") ?: "")
        object : MutableState<String> {
            override var value: String
                get() = backing.value
                set(text) {
                    if (text == backing.value) return
                    backing.value = text
                    prefs.edit().apply { if (text.isEmpty()) remove(KEY) else putString(KEY, text) }.apply()
                }
            override fun component1() = value
            override fun component2(): (String) -> Unit = { value = it }
        }.also { state = it }
    }
}
