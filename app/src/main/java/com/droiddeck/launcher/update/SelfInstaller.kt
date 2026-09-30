package com.droiddeck.launcher.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.Hashes
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Installs one of DroidDeck's own builds over itself: download into the cache, check the file
 * against GitHub's sha256, then hand it to PackageInstaller. Android shows its own "Update this
 * app?" prompt, and the app is restarted on the new build.
 */
object SelfInstaller {
    /** The broadcast PackageInstaller answers on; the launcher registers for it while it is open. */
    const val ACTION_STATUS = "com.droiddeck.launcher.update.INSTALL_STATUS"

    /** Android lets an app install packages only once the user allows it in "Install unknown apps". */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Downloads [apk] into the cache and checks it; a download left from before is replaced. */
    fun download(context: Context, apk: AppUpdates.Apk, progress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates")
        FileUtils.delete(dir)
        dir.mkdirs()
        val target = File(dir, apk.name)
        val c = URL(apk.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "DroidDeck-app")
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode != 200) throw IOException("The download answered HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: apk.size
            c.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        out.write(buf, 0, r)
                        done += r
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != last) { last = pct; progress(pct) }
                    }
                }
            }
            if (!apk.sha256.equals(Hashes.sha256(target), ignoreCase = true)) {
                throw IOException("The download didn't match its checksum and was discarded - try again")
            }
            return target
        } catch (e: Exception) {
            FileUtils.delete(target)
            throw e
        } finally {
            c.disconnect()
        }
    }

    /** Writes [apk] into an install session and commits it; the answer comes as an [ACTION_STATUS] broadcast. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Skips Android's prompt where it allows that (the app installed its current build itself).
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out, 1 shl 16)
                    session.fsync(out)
                }
            }
            val status = Intent(ACTION_STATUS).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, status, flags).intentSender)
        }
    }

    /** What went wrong, in words for the user, from PackageInstaller's status and message. */
    fun describeFailure(status: Int, message: String?): String = when {
        message?.contains("VERSION_DOWNGRADE") == true ->
            "Android won't install an older version over a newer one. Stay on this build until the channel catches up."
        status == PackageInstaller.STATUS_FAILURE_CONFLICT || message?.contains("UPDATE_INCOMPATIBLE") == true ->
            "This copy of DroidDeck is signed differently (a local build?), so Android won't update it in place."
        status == PackageInstaller.STATUS_FAILURE_STORAGE -> "There isn't enough free space for the update."
        status == PackageInstaller.STATUS_FAILURE_ABORTED -> "The update was cancelled."
        else -> "The update didn't install" + (message?.let { ": $it" } ?: ".")
    }
}
