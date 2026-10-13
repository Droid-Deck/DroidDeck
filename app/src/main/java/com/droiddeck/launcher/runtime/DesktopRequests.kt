package com.droiddeck.launcher.runtime

import java.io.File

/**
 * What the app asks the desktop to open (Battle.net's tile): a request in the session root that
 * droiddeck-desktop-requests, started by Plasma's autostart, runs with the session's environment.
 * The program's path is on the first line, then one argument a line. Only the app's own programs in
 * /usr/local/bin run.
 */
object DesktopRequests {
    const val REQUEST = "desktop-open"

    /** Asks the desktop in session root [dir] to open [program]: the one running, or the next to start. */
    fun open(dir: File, program: String, args: List<String> = emptyList()) {
        require(program.startsWith("/") && (args + program).none { '\n' in it }) { "not a request: $program" }
        dir.mkdirs()
        val partial = File(dir, "$REQUEST.tmp")
        partial.writeText((listOf(program) + args).joinToString("\n") + "\n")
        if (!partial.renameTo(File(dir, REQUEST))) {
            partial.delete()
            error("could not leave a request in $dir")
        }
    }

    /**
     * A desktop starting: [program] is what it opens once Plasma is up, or nothing. A request an earlier
     * desktop never got to is dropped either way.
     */
    fun reset(dir: File, program: String?, args: List<String> = emptyList()) {
        File(dir, REQUEST).delete()
        if (program != null) open(dir, program, args)
    }
}
