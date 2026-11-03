package com.droiddeck.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.ui.UpdatesActions
import com.droiddeck.launcher.ui.UpdatesState
import com.droiddeck.launcher.update.AppUpdates
import com.droiddeck.launcher.update.SelfInstaller
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability

internal class UpdatesMenu(private val activity: Activity, private val ui: Handler,
                           private val playManager: () -> AppUpdateManager = { AppUpdateManagerFactory.create(activity) }) {
    var catalog by mutableStateOf<AppUpdates.Catalog?>(null)
    var follow by mutableStateOf(AppUpdates.Follow(AppUpdates.Channel.STABLE))
    var checking by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var stage by mutableStateOf<String?>(null)
    var percent by mutableIntStateOf(-1)
    var askPermission by mutableStateOf(false)
    var playUpdateAvailable by mutableStateOf<Boolean?>(null)
    private var playCheckedAt by mutableStateOf(0L)
    private var pending: AppUpdates.Release? = null
    private var lastCheck = 0L
    private var request = 0
    @Volatile private var session = -1

    fun state() = UpdatesState(catalog, follow, checking, error, stage, percent, askPermission,
        playUpdateAvailable, playCheckedAt)

    fun actions() = UpdatesActions(
        onCheck = { check(force = true) },
        onFollow = ::choose,
        onInstall = ::install,
        onAllowInstalls = {
            askPermission = false
            if (!BuildConfig.PLAY_DISTRIBUTION) {
                runCatching { activity.startActivity(SelfInstaller.permissionIntent(activity)) }
                    .onFailure { error = activity.getString(R.string.upd_unknown_apps_failed); pending = null }
            }
        },
        onDismissPermission = { askPermission = false; pending = null },
        onOpenPlay = ::openPlay,
    )

    val hasUpdate: Boolean get() = if (BuildConfig.PLAY_DISTRIBUTION) playUpdateAvailable == true else AppUpdates.hasUpdate(catalog, follow)

    fun onResume() {
        if (BuildConfig.PLAY_DISTRIBUTION) {
            if (!SessionState.running) check(force = false)
            return
        }
        pending?.let { r ->
            if (SelfInstaller.canInstall(activity)) { pending = null; install(r) }
        }
        if (!SessionState.running) check(force = false)
    }

    fun check(force: Boolean) {
        if (BuildConfig.PLAY_DISTRIBUTION) {
            checkPlay(force)
            return
        }
        if (checking || stage != null) return
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 60_000) return
        lastCheck = now
        val id = ++request
        checking = true
        Thread({
            val result = runCatching { AppUpdates.refresh(activity) }
            ui.post {
                if (id != request) return@post
                checking = false
                result.onSuccess {
                    catalog = it
                    follow = AppUpdates.follow(activity, it)
                    error = null
                }.onFailure { if (force) error = activity.getString(R.string.upd_couldnt_check, it.message ?: it.javaClass.simpleName) }
            }
        }, "app-updates").start()
    }

    private fun checkPlay(force: Boolean) {
        if (checking) return
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 60_000) return
        lastCheck = now
        checking = true
        error = null
        val id = ++request
        fun failed(failure: Throwable) {
            if (id != request || activity.isDestroyed) return
            checking = false
            if (force) error = activity.getString(R.string.upd_couldnt_check, failure.message ?: failure.javaClass.simpleName)
        }
        runCatching {
            playManager().appUpdateInfo.addOnSuccessListener { info ->
                if (id != request || activity.isDestroyed) return@addOnSuccessListener
                checking = false
                val availability = info.updateAvailability()
                playUpdateAvailable = when (availability) {
                    UpdateAvailability.UPDATE_AVAILABLE, UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> true
                    UpdateAvailability.UPDATE_NOT_AVAILABLE -> false
                    else -> null
                }
                playCheckedAt = now
            }.addOnFailureListener { failed(it) }
        }.onFailure { failed(it) }
    }

    private fun openPlay() {
        if (!BuildConfig.PLAY_DISTRIBUTION) return
        val listing = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${activity.packageName}"))
            .setPackage("com.android.vending")
        runCatching { activity.startActivity(listing) }.onFailure {
            runCatching {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${activity.packageName}")))
            }.onFailure { error = activity.getString(R.string.upd_couldnt_check, it.message ?: it.javaClass.simpleName) }
        }
    }

    private fun choose(f: AppUpdates.Follow) {
        AppUpdates.setFollow(activity, f)
        follow = f
        error = null
    }

    private fun install(r: AppUpdates.Release) {
        if (BuildConfig.PLAY_DISTRIBUTION) { openPlay(); return }
        if (stage != null) return
        val apk = r.apk ?: run { error = activity.getString(R.string.upd_no_download); return }
        AppUpdates.installBlock(r)?.let { error = it.message(activity); return }
        if (SessionState.running) { error = activity.getString(R.string.upd_stop_first); return }
        if (!SelfInstaller.canInstall(activity)) { pending = r; askPermission = true; return }
        error = null
        session = -1
        stage = activity.getString(R.string.upd_stage_downloading)
        percent = 0
        Thread({
            val result = runCatching {
                val file = SelfInstaller.download(activity, apk) { pct -> ui.post { percent = pct } }
                ui.post { stage = activity.getString(R.string.upd_stage_installing); percent = -1 }
                SelfInstaller.install(activity, file) { id -> session = id }
            }
            result.onFailure { e -> ui.post { stage = null; error = e.message ?: activity.getString(R.string.upd_failed) } }
        }, "app-update-install").start()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1).let { it < 0 || it != session }) return
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    val safe = confirm?.let { SelfInstaller.safeConfirmIntent(activity, it) }
                    if (safe == null) { stage = null; error = activity.getString(R.string.upd_not_offered); return }
                    stage = activity.getString(R.string.upd_stage_confirm)
                    if (runCatching { activity.startActivity(safe) }.isFailure) { stage = null; error = activity.getString(R.string.upd_not_offered) }
                }
                PackageInstaller.STATUS_SUCCESS -> stage = null
                else -> {
                    stage = null
                    error = SelfInstaller.describeFailure(activity, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                }
            }
        }
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun start() {
        if (BuildConfig.PLAY_DISTRIBUTION) return
        catalog = AppUpdates.cached(activity)
        follow = AppUpdates.follow(activity, catalog)
        val filter = IntentFilter(SelfInstaller.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else activity.registerReceiver(receiver, filter)
    }

    fun unregister() {
        request++
        checking = false
        runCatching { activity.unregisterReceiver(receiver) }
    }
}
