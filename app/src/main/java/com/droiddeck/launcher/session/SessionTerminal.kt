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

    fun clear(generation: Int? = null) {
        val current = launch ?: return
        if (generation == null || current.generation == generation) launch = null
    }
}
