package com.droiddeck.launcher

import android.app.Activity
import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.ui.UpdatesActions
import com.droiddeck.launcher.ui.UpdatesState
import com.droiddeck.launcher.update.AppUpdateService
import com.droiddeck.launcher.update.AppUpdateState
import com.droiddeck.launcher.update.AppUpdateState.Phase
import com.droiddeck.launcher.update.AppUpdates
import com.droiddeck.launcher.update.SelfInstaller

internal class UpdatesMenu(private val activity: Activity, private val ui: Handler) {
    var catalog by mutableStateOf<AppUpdates.Catalog?>(null)
    var follow by mutableStateOf(AppUpdates.Follow(AppUpdates.Channel.STABLE))
    var checking by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var stage by mutableStateOf<String?>(null)
    var percent by mutableIntStateOf(-1)
    var askPermission by mutableStateOf(false)
    private var pending: AppUpdates.Release? = null
    private var lastCheck = 0L
    private var request = 0
    private var resumed = false
    private var ready by mutableStateOf(false)
    private var taskApk by mutableStateOf<AppUpdates.Apk?>(null)
    private var taskLabel by mutableStateOf<String?>(null)
    private var taskError: String? = null
    private var continuing = false
    private var continueReady = false
    private val observer: (AppUpdateState.Snapshot) -> Unit = { task ->
        taskError = task.error
        error = task.error
        ready = task.phase == Phase.READY
        taskApk = if (task.phase in setOf(Phase.IDLE, Phase.FAILED)) null else task.apk
        taskLabel = task.targetLabel
        percent = task.percent
        stage = when (task.phase) {
            Phase.DOWNLOADING -> activity.getString(R.string.upd_stage_downloading)
            Phase.VERIFYING -> activity.getString(R.string.upd_verifying)
            Phase.INSTALLING -> activity.getString(R.string.upd_stage_installing)
            Phase.CONFIRMING -> activity.getString(R.string.upd_stage_confirm)
            else -> null
        }
        continueWhenVisible()
    }

    fun state() = UpdatesState(catalog, follow, checking, error, stage, percent, askPermission, ready, taskApk, taskLabel)

    fun actions() = UpdatesActions(
        onCheck = { check(force = true) },
        onFollow = ::choose,
        onInstall = ::install,
        onContinueInstall = { continueWhenVisible(explicit = true) },
        onDiscardDownload = {
            continueReady = false
            askPermission = false
            pending = null
            AppUpdateState.discardReady(activity)
        },
        onAllowInstalls = {
            askPermission = false
            runCatching { activity.startActivity(SelfInstaller.permissionIntent(activity)) }
                .onFailure { error = activity.getString(R.string.upd_unknown_apps_failed); pending = null }
        },
        onDismissPermission = { askPermission = false; pending = null; continueReady = false },
    )

    val hasUpdate: Boolean get() = AppUpdates.hasUpdate(catalog, follow)

    fun onPause() { resumed = false }

    fun onResume() {
        resumed = true
        continueWhenVisible(explicit = continueReady)
        pending?.let { r ->
            if (SelfInstaller.canInstall(activity)) { pending = null; install(r) }
        }
        if (!SessionState.running) check(force = false)
    }

    fun check(force: Boolean) {
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
                    error = taskError
                }.onFailure { if (force) error = activity.getString(R.string.upd_couldnt_check, it.message ?: it.javaClass.simpleName) }
            }
        }, "app-updates").start()
    }

    private fun choose(f: AppUpdates.Follow) {
        continueReady = false
        askPermission = false
        pending = null
        AppUpdates.setFollow(activity, f)
        follow = f
        error = taskError
    }

    private fun install(r: AppUpdates.Release) {
        if (stage != null) return
        val apk = r.apk ?: run { error = activity.getString(R.string.upd_no_download); return }
        AppUpdates.installBlock(r)?.let { error = it.message(activity); return }
        if (SessionState.running) { error = activity.getString(R.string.upd_stop_first); return }
        if (!SelfInstaller.canInstall(activity)) { pending = r; askPermission = true; return }
        error = null
        val target = "${r.version ?: r.title.ifBlank { r.tag }} (${r.commit.take(7)})"
        AppUpdateService.start(activity, apk, target)
    }

    private fun continueWhenVisible(explicit: Boolean = false) {
        if (!resumed || continuing || SessionState.running) return
        val power = activity.getSystemService(android.os.PowerManager::class.java)
        val keyguard = activity.getSystemService(android.app.KeyguardManager::class.java)
        if (!power.isInteractive || keyguard.isKeyguardLocked) return
        continuing = true
        try {
            val task = AppUpdateState.snapshot
            val selected = catalog?.let { AppUpdates.release(it, follow)?.apk }
            val matchesSelection = selected != null && selected.sha256.equals(task.apk?.sha256, ignoreCase = true)
            if (task.phase == Phase.READY && (explicit || task.automatic && matchesSelection)) {
                if (!SelfInstaller.canInstall(activity)) {
                    continueReady = true
                    askPermission = true
                    return
                }
                continueReady = false
                AppUpdateState.install(activity)
            }
            AppUpdateState.takeConfirmation()?.let { confirm ->
                if (runCatching { activity.startActivity(confirm) }.isFailure) {
                    AppUpdateState.fail(activity, activity.getString(R.string.upd_not_offered))
                }
            }
        } finally {
            continuing = false
        }
    }

    fun start() {
        catalog = AppUpdates.cached(activity)
        follow = AppUpdates.follow(activity, catalog)
        AppUpdateState.initialize(activity)
        AppUpdateState.subscribe(observer)
    }

    fun unregister() {
        resumed = false
        request++
        AppUpdateState.unsubscribe(observer)
    }
}
