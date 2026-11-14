package com.droiddeck.launcher.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentSetupRootTest {
    @Test fun onlyThePackageInstallRunsAsRoot() {
        assertEquals(listOf(AgentSetup.Step.INSTALL), AgentSetup.Step.values().filter { AgentSetup.runsAsRoot(it) })
    }
}
