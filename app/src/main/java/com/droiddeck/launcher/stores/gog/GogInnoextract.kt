package com.droiddeck.launcher.stores.gog

import android.content.Context
import com.droiddeck.launcher.runtime.GuestCommand
import com.droiddeck.launcher.stores.StoreInstallRoot
import java.util.concurrent.atomic.AtomicBoolean

internal object GogInnoextract {
    private const val PROGRAM = "/usr/local/bin/droiddeck-innoextract"

    @JvmStatic
    fun extract(context: Context, setup: String, target: String, logName: String, cancelled: AtomicBoolean, onLine: (String) -> Unit): Int =
        GuestCommand.run(context, argv(setup, target), logName = logName,
            extraBinds = StoreInstallRoot.guestBinds(context), cancelled = cancelled, onLine = onLine)

    internal fun argv(setup: String, target: String): List<String> = listOf(PROGRAM, "--gog", "--silent", "-d", target, setup)
}
