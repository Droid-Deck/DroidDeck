package com.droiddeck.launcher.agent

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentBashrcTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun aPinnedAdbPortGivesWayToTheLiveOne() {
        val bashrc = tmp.newFile(".bashrc")
        bashrc.writeText("alias ll='ls -l'\n# droiddeck-agent-path\nexport PATH=\"\$HOME/.local/bin:\$PATH\" ADB_SERIAL=127.0.0.1:5555 BROWSER=\"\$HOME/.local/bin/android-open\"\n")
        AgentSetup.unpinAdbSerial(bashrc)
        assertEquals(
            "alias ll='ls -l'\n# droiddeck-agent-path\nexport PATH=\"\$HOME/.local/bin:\$PATH\" ADB_SERIAL=\"\${ADB_SERIAL:-127.0.0.1:5555}\" BROWSER=\"\$HOME/.local/bin/android-open\"\n",
            bashrc.readText(),
        )
        AgentSetup.unpinAdbSerial(bashrc)
        assertEquals(1, Regex("ADB_SERIAL=").findAll(bashrc.readText()).count())
    }
}
