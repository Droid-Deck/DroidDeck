package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File

object SteamRepair {
    private fun home(context: Context) = File(LinuxRuntime.rootDir(context), "root")
    private fun request(context: Context) = File(home(context), ".bl-steam-repair")
    private fun unconfirmed(context: Context) = File(home(context), ".local/share/Steam/package/droiddeck-unconfirmed")

    fun queued(context: Context): Boolean = request(context).exists()

    fun queue(context: Context): Boolean = runCatching {
        request(context).apply { parentFile?.mkdirs() }.writeText("1\n")
    }.isSuccess

    fun clientShown(context: Context) {
        unconfirmed(context).delete()
    }
}
