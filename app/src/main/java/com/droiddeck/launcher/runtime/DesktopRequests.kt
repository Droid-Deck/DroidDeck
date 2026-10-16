package com.droiddeck.launcher.runtime

import java.io.File

// droiddeck-desktop-requests (Plasma autostart) runs the request, and only for programs in /usr/local/bin.
object DesktopRequests {
    const val REQUEST = "desktop-open"

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

    fun reset(dir: File, program: String?, args: List<String> = emptyList()) {
        File(dir, REQUEST).delete()
        if (program != null) open(dir, program, args)
    }
}
