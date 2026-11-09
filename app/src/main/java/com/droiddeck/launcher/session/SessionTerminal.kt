package com.droiddeck.launcher.session

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

object SessionTerminal {
    private data class Launch(
        val command: Array<String>,
        val hostEnvironment: Array<String>,
        val workingDirectory: String,
        val generation: Int,
    )

    @Volatile private var launch: Launch? = null

    private class Kept(val session: TerminalSession, val generation: Int)

    // The bottom screen's presentation is dismissed whenever another app covers that display
    // (a browser opened for a sign-in), so its shell is kept for the next panel.
    @Volatile private var kept: Kept? = null

    fun prepare(
        command: List<String>,
        hostEnvironment: Array<String>,
        workingDirectory: File,
        generation: Int,
    ) {
        launch = Launch(command.toTypedArray(), hostEnvironment.copyOf(), workingDirectory.path, generation)
    }

    fun create(client: TerminalSessionClient): TerminalSession? {
        val current = launch ?: return null
        if (!SessionState.running) return null
        return TerminalSession(
            current.command.first(), current.workingDirectory, current.command,
            current.hostEnvironment, 3000, client,
        ).also { it.mSessionName = "DroidDeck Linux shell" }
    }

    fun reuse(client: TerminalSessionClient): TerminalSession? {
        val current = kept ?: return null
        if (current.generation != launch?.generation || !current.session.isRunning) {
            kept = null
            return null
        }
        current.session.updateTerminalSessionClient(client)
        return current.session
    }

    fun keep(session: TerminalSession) {
        val generation = launch?.generation ?: return
        kept = Kept(session, generation)
    }

    fun release(session: TerminalSession?) {
        if (session != null && kept?.session === session) kept = null
    }

    fun clear(generation: Int? = null) {
        val current = launch ?: return
        if (generation == null || current.generation == generation) {
            launch = null
            kept?.session?.takeIf { it.pid > 0 }?.finishIfRunning()
            kept = null
        }
    }
}
