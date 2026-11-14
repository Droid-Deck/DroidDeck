package com.droiddeck.launcher.session

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SessionTerminalKeepTest {
    private val client = Proxy.newProxyInstance(
        TerminalSessionClient::class.java.classLoader, arrayOf(TerminalSessionClient::class.java),
    ) { _, method, _ -> if (method.returnType == Int::class.javaPrimitiveType) 0 else null } as TerminalSessionClient

    private fun prepare(generation: Int) =
        SessionTerminal.prepare(listOf("/bin/sh"), emptyArray(), File("/"), generation)

    private fun unstarted() = TerminalSession("/bin/sh", "/", arrayOf("/bin/sh"), emptyArray(), 3000, client)

    @After fun reset() = SessionTerminal.clear()

    @Test fun aKeptShellIsReusedByTheNextPanel() {
        prepare(1)
        val shell = unstarted()
        SessionTerminal.keep(shell)
        assertSame(shell, SessionTerminal.reuse(client))
    }

    @Test fun aReleasedShellIsNotReused() {
        prepare(1)
        val shell = unstarted()
        SessionTerminal.keep(shell)
        SessionTerminal.release(shell)
        assertNull(SessionTerminal.reuse(client))
    }

    @Test fun aShellFromAnEarlierSessionIsNotReused() {
        prepare(1)
        SessionTerminal.keep(unstarted())
        prepare(2)
        assertNull(SessionTerminal.reuse(client))
    }

    @Test fun clearingTheSessionDropsTheKeptShell() {
        prepare(1)
        SessionTerminal.keep(unstarted())
        SessionTerminal.clear(1)
        prepare(1)
        assertNull(SessionTerminal.reuse(client))
    }
}
