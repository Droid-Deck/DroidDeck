package com.droiddeck.launcher.stores.gog

import android.content.Context
import com.droiddeck.launcher.runtime.GuestCommand
import com.droiddeck.launcher.stores.StoreInstallRoot
import java.util.concurrent.atomic.AtomicBoolean

internal object GogInnoextract {
    private const val PROGRAM = "/usr/local/bin/droiddeck-innoextract"
    private const val REASON_LINES = 3
    private const val REASON_CHARS = 400
    private val TREE = Regex("""^[\s├└│─]+""")
    private val BOILERPLATE = Regex(
        """^(\S+ )?warning:|^If you are sure the setup file is not corrupted|^filing a bug report at |^Done with \d+ (errors?|warnings?)""",
        RegexOption.IGNORE_CASE,
    )

    class Result(val exit: Int, val reason: String) {
        fun failure(message: String): String = if (reason.isEmpty()) message else "$message: $reason"
    }

    @JvmStatic
    fun extract(context: Context, setup: String, target: String, logName: String, cancelled: AtomicBoolean, onLine: (String) -> Unit): Result {
        val details = ArrayDeque<String>()
        val exit = GuestCommand.run(context, argv(setup, target), logName = logName,
            extraBinds = StoreInstallRoot.guestBinds(context), cancelled = cancelled) { line ->
            onLine(line)
            val kept = detail(line)
            if (kept != null) {
                details.addLast(kept)
                if (details.size > REASON_LINES) details.removeFirst()
            }
        }
        return Result(exit, if (exit == 0) "" else reason(details))
    }

    internal fun argv(setup: String, target: String): List<String> = listOf(PROGRAM, "--gog", "--silent", "-d", target, setup)

    internal fun reason(lines: Iterable<String>): String =
        lines.mapNotNull(::detail).takeLast(REASON_LINES).joinToString(" ").take(REASON_CHARS)

    private fun detail(raw: String): String? =
        raw.replace(TREE, "").trim().takeUnless { it.isEmpty() || BOILERPLATE.containsMatchIn(it) }
}
