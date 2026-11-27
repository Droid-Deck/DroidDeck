package com.droiddeck.launcher.session

import android.content.Context
import android.os.Environment
import com.droiddeck.launcher.runtime.GuestCommand
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File

object Ludusavi {
    private const val BIN = "/usr/local/bin/droiddeck-ludusavi"
    private const val CONFIG = "/root/.config/droiddeck-ludusavi"
    private const val REL_BACKUPS = "DroidDeck/Saves/ludusavi"

    fun installed(context: Context): Boolean = File(LinuxRuntime.rootDir(context), BIN.trimStart('/')).isFile

    private fun backupDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), REL_BACKUPS)

    fun run(context: Context, args: List<String>, onLine: (String) -> Unit): Int {
        runCatching { backupDir().mkdirs() }
        val base = listOf(BIN, "--config", CONFIG, "--no-manifest-update")
        return GuestCommand.run(context, base + args, logName = "ludusavi", onLine = onLine)
    }

    fun backup(context: Context, title: String, onLine: (String) -> Unit): Int = run(
        context, listOf("backup", "--api", "--force", "--path", backupDir().path, title), onLine)

    fun preview(context: Context, title: String, onLine: (String) -> Unit): Int = run(
        context, listOf("backup", "--api", "--preview", "--path", backupDir().path, title), onLine)

    fun restore(context: Context, title: String, onLine: (String) -> Unit): Int = run(
        context, listOf("restore", "--api", "--force", "--path", backupDir().path, title), onLine)

    fun updateManifest(context: Context, onLine: (String) -> Unit): Int =
        run(context, listOf("manifest", "update"), onLine)
}
