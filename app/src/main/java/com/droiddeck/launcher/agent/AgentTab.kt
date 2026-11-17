package com.droiddeck.launcher.agent

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.session.SessionPrefs

object AgentTab {
    var shown by mutableStateOf(false)
        private set

    val visible: Boolean get() = shown && AgentHost.available

    fun load(context: Context) {
        shown = SessionPrefs.agentTab(context)
    }

    fun set(context: Context, show: Boolean) {
        shown = show
        SessionPrefs.setAgentTab(context, show)
    }
}
