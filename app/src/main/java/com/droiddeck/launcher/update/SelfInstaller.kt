package com.droiddeck.launcher.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.Hashes
import java.io.File
import java.io.IOException
import java.security.MessageDigest

object SelfInstaller {
    const val ACTION_STATUS = "com.droiddeck.launcher.update.INSTALL_STATUS"

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun download(context: Context, apk: AppUpdates.Apk, progress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates")
        dir.listFiles()?.forEach { if (it.name != apk.name) FileUtils.delete(it) }
        dir.mkdirs()
        val target = File(dir, apk.name)
        val ok = Downloader.downloadFile(apk.url, target, true, apk.sha256) { f -> progress(if (f < 0) -1 else (f * 100).toInt()) }
        if (!ok) throw IOException(context.getString(R.string.selfinst_download_failed))
        try {
            if (apk.size > 0 && target.length() != apk.size) {
                throw IOException(context.getString(R.string.selfinst_size_mismatch))
            }
            if (!apk.sha256.equals(Hashes.sha256(target), ignoreCase = true)) {
                throw IOException(context.getString(R.string.selfinst_checksum_mismatch))
            }
            validateDownloaded(context, target, apk)
        } catch (e: Exception) {
            FileUtils.delete(target)
            throw e
        }
        return target
    }

    private fun validateDownloaded(context: Context, file: File, apk: AppUpdates.Apk) {
        if (apk.packageName != context.packageName) {
            throw IOException(context.getString(R.string.selfinst_wrong_package, apk.packageName, context.packageName))
        }
        if (!apk.signerSha256.equals(BuildConfig.RELEASE_SIGNER, ignoreCase = true)) {
            throw IOException(context.getString(R.string.selfinst_metadata_signer))
        }
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IOException(context.getString(R.string.selfinst_unreadable))
        if (info.packageName != context.packageName) {
            throw IOException(context.getString(R.string.selfinst_apk_wrong_package, info.packageName, context.packageName))
        }
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        if (code != apk.versionCode.toLong()) {
            throw IOException(context.getString(R.string.selfinst_version_mismatch, code, apk.versionCode))
        }
        if (code < BuildConfig.VERSION_CODE.toLong()) {
            throw IOException(context.getString(R.string.selfinst_downgrade, code, BuildConfig.VERSION_CODE))
        }

        if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: throw IOException(context.getString(R.string.selfinst_no_signing))
            val digests = signing.apkContentsSigners.orEmpty().map { cert ->
                MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }
            if (digests.none { it.equals(BuildConfig.RELEASE_SIGNER, ignoreCase = true) }) {
                throw IOException(context.getString(R.string.selfinst_wrong_signer))
            }
        }
    }

    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
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

    fun describeFailure(context: Context, status: Int, message: String?): String = when {
        message?.contains("VERSION_DOWNGRADE") == true -> context.getString(R.string.upd_fail_downgrade)
        status == PackageInstaller.STATUS_FAILURE_CONFLICT || message?.contains("UPDATE_INCOMPATIBLE") == true ->
            context.getString(R.string.upd_fail_signature)
        status == PackageInstaller.STATUS_FAILURE_STORAGE -> context.getString(R.string.upd_fail_storage)
        status == PackageInstaller.STATUS_FAILURE_ABORTED -> context.getString(R.string.upd_fail_aborted)
        else -> message?.let { context.getString(R.string.upd_fail_with_message, it) } ?: context.getString(R.string.upd_fail_generic)
    }
}
