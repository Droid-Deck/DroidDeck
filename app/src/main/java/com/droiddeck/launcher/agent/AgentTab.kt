package com.droiddeck.launcher.agent

import com.droiddeck.launcher.ui.NavTabs

object AgentTab {
    val shown: Boolean get() = "agent" !in NavTabs.hidden

    val visible: Boolean get() = shown && AgentHost.available
}
