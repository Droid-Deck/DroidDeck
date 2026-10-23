package com.droiddeck.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.ui.UpdatesActions
import com.droiddeck.launcher.ui.UpdatesState
import com.droiddeck.launcher.update.AppUpdates
import com.droiddeck.launcher.update.SelfInstaller
import com.droiddeck.launcher.artifact.RepositorySources
import com.droiddeck.launcher.artifact.GitHubArtifacts
import com.droiddeck.launcher.core.FileUtils

internal class UpdatesMenu(private val activity: Activity, private val ui: Handler) {
    var catalog by mutableStateOf<AppUpdates.Catalog?>(null)
    var follow by mutableStateOf(AppUpdates.Follow(AppUpdates.Channel.STABLE))
    var checking by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var stage by mutableStateOf<String?>(null)
    var percent by mutableIntStateOf(-1)
    var askPermission by mutableStateOf(false)
    var sources by mutableStateOf<List<RepositorySources.Source>>(emptyList())
    private var pending: AppUpdates.Release? = null
    private var lastCheck = 0L
    private var lastRateLimitPopup = 0L
    private var request = 0

    fun state() = UpdatesState(catalog, follow, checking, error, stage, percent, askPermission, sources, follow.sourceId.ifEmpty { null })

    fun actions() = UpdatesActions(
        onCheck = { check(force = true) },
        onFollow = ::choose,
        onInstall = ::install,
        onAllowInstalls = {
            askPermission = false
            runCatching { activity.startActivity(SelfInstaller.permissionIntent(activity)) }
                .onFailure { error = activity.getString(R.string.upd_unknown_apps_failed); pending = null }
        },
        onDismissPermission = { askPermission = false; pending = null },
        onSourcesChanged = ::sourcesChanged,
        onSource = ::chooseSource,
    )

    val hasUpdate: Boolean get() = AppUpdates.hasUpdate(catalog, follow)

    fun onResume() {
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
        val selectedSource = follow.sourceId
        checking = true
        Thread({
            val result = runCatching { AppUpdates.refresh(activity, selectedSource) }
            ui.post {
                if (id != request) return@post
                checking = false
                result.onSuccess {
                    catalog = it
                    follow = AppUpdates.follow(activity, it).let { selected ->
                        if (selected.sourceId.isEmpty()) selected.copy(sourceId = selectedSource) else selected
                    }
                    error = null
                }.onFailure {
                    Log.w("UpdatesMenu", "update source refresh", it)
                    val rateLimited = GitHubArtifacts.isRateLimited(it)
                    if (force || rateLimited) {
                        error = GitHubArtifacts.failureMessage(activity, it).let { message ->
                            if (rateLimited) message else activity.getString(R.string.upd_couldnt_check, message)
                        }
                        val popupAt = System.currentTimeMillis()
                        if (rateLimited && (force || popupAt - lastRateLimitPopup >= 3 * 60_000)) {
                            lastRateLimitPopup = popupAt
                            android.widget.Toast.makeText(activity, error, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }, "app-updates").start()
    }

    private fun choose(f: AppUpdates.Follow) {
        val selected = f.copy(sourceId = follow.sourceId)
        AppUpdates.setFollow(activity, selected)
        follow = selected
        error = null
    }

    private fun sourcesChanged() {
        request++
        checking = false
        sources = AppUpdates.sources(activity).filterNot { it.hidden }
        if (sources.none { it.id == follow.sourceId }) {
            catalog = null
            error = null
            pending = null
            askPermission = false
        } else {
            catalog = AppUpdates.cached(activity, follow.sourceId)
            error = null
            check(force = true)
        }
    }

    private fun chooseSource(sourceId: String) {
        if (sources.none { it.id == sourceId } || sourceId == follow.sourceId) return
        request++
        checking = false
        follow = follow.copy(sourceId = sourceId)
        AppUpdates.setFollow(activity, follow)
        catalog = AppUpdates.cached(activity, sourceId)
        error = null
        check(force = true)
    }

    private fun install(r: AppUpdates.Release) {
        if (stage != null) return
        if (catalog?.sourceId != follow.sourceId || sources.none { it.id == follow.sourceId }) {
            error = activity.getString(R.string.upd_no_download)
            return
        }
        val apk = r.apk ?: run { error = activity.getString(R.string.upd_no_download); return }
        AppUpdates.installBlock(r)?.let { error = it.message(activity); return }
        if (SessionState.running) { error = activity.getString(R.string.upd_stop_first); return }
        if (!SelfInstaller.canInstall(activity)) { pending = r; askPermission = true; return }
        error = null
        val sourceId = follow.sourceId
        val sourceGeneration = RepositorySources.generation(activity, AppUpdates.SOURCE_SCOPE, AppUpdates.sourceBuiltIns())
        if (!RepositorySources.isCurrent(activity, AppUpdates.SOURCE_SCOPE, AppUpdates.sourceBuiltIns(), sourceId, sourceGeneration)) {
            error = activity.getString(R.string.upd_no_download)
            return
        }
        stage = activity.getString(R.string.upd_stage_downloading)
        percent = 0
        Thread({
            val result = runCatching {
                val file = SelfInstaller.download(activity, apk) { pct -> ui.post { percent = pct } }
                if (!RepositorySources.isCurrent(
                        activity, AppUpdates.SOURCE_SCOPE, AppUpdates.sourceBuiltIns(), sourceId, sourceGeneration,
                    )) {
                    FileUtils.delete(file)
                    throw java.io.IOException("Update source changed while downloading")
                }
                ui.post { stage = activity.getString(R.string.upd_stage_installing); percent = -1 }
                SelfInstaller.install(activity, file)
            }
            result.onFailure { e -> ui.post { stage = null; error = e.message ?: activity.getString(R.string.upd_failed) } }
        }, "app-update-install").start()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    if (confirm == null) { stage = null; error = activity.getString(R.string.upd_not_offered); return }
                    stage = activity.getString(R.string.upd_stage_confirm)
                    activity.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> stage = null
                else -> {
                    stage = null
                    error = SelfInstaller.describeFailure(activity, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                }
            }
        }
    }

    fun start() {
        sources = AppUpdates.sources(activity).filterNot { it.hidden }
        follow = AppUpdates.follow(activity, null)
        catalog = AppUpdates.cached(activity, follow.sourceId)
        val filter = IntentFilter(SelfInstaller.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else activity.registerReceiver(receiver, filter)
    }

    fun unregister() {
        runCatching { activity.unregisterReceiver(receiver) }
    }
}
