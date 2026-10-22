package com.droiddeck.launcher

import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.runtime.DeckyManager
import com.droiddeck.launcher.session.SessionState
import java.io.File

internal class DeckyMenu(private val activity: android.app.Activity, private val ui: android.os.Handler) {
    var deckyInstalled by mutableStateOf<String?>(null)
    var deckyLatest by mutableStateOf<DeckyManager.Release?>(null)
    var deckyBranch by mutableIntStateOf(DeckyManager.BRANCH_STABLE)
    private var deckyChannels = DeckyManager.ReleaseChannels(emptyList(), emptyList())
    var deckyChecking by mutableStateOf(false)
    var deckyStage by mutableStateOf<String?>(null)
    var deckyPercent by mutableIntStateOf(-1)
    var deckyDownloadingBinary by mutableStateOf(false)
    var deckySupervisor by mutableStateOf(false)
    var deckyPendingZip by mutableStateOf<File?>(null)
    var deckyReleaseRequest = 0

    fun refreshDecky() {
        val request = ++deckyReleaseRequest
        deckyBranch = DeckyManager.branch(activity)
        deckyChecking = true
        Thread({
            val channels = runCatching { DeckyManager.releaseChannels(activity) }
                .getOrElse { DeckyManager.ReleaseChannels(emptyList(), emptyList()) }
            ui.post {
                if (request == deckyReleaseRequest) {
                    deckyChannels = channels
                    deckyLatest = DeckyManager.latest(channels, deckyBranch)
                    deckyChecking = false
                }
            }
        }, "decky-releases").start()
    }

    fun switchDeckyBranch(branch: Int) {
        if (branch == deckyBranch || deckyStage != null || SessionState.running) return
        val saved = runCatching { DeckyManager.setBranch(activity, branch) }
        if (saved.isFailure) {
            android.widget.Toast.makeText(activity, R.string.decky_channel_failed, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        deckyBranch = branch
        deckyLatest = DeckyManager.latest(deckyChannels, branch)
        refreshDecky()
    }

    fun installDecky(release: DeckyManager.Release) {
        if (deckyStage != null || SessionState.running) return
        deckyStage = activity.getString(R.string.store_starting); deckyPercent = -1
        Thread({
            val problem = runCatching {
                DeckyManager.install(activity, release) { label, value -> ui.post { deckyStage = label; deckyPercent = value } }
            }.getOrElse { error -> activity.getString(R.string.decky_install_failed, error.message ?: error.javaClass.simpleName) }
            ui.post {
                deckyStage = null; deckyPercent = -1; deckyInstalled = DeckyManager.installed(activity)
                if (problem != null) android.widget.Toast.makeText(activity, problem, android.widget.Toast.LENGTH_LONG).show()
            }
        }, "install-decky").start()
    }

    fun importPluginZip(archive: File) {
        if (deckyStage != null || SessionState.running || deckyInstalled == null) return
        deckyPendingZip = archive
    }

    fun cancelPluginZip() {
        deckyPendingZip = null
    }

    fun confirmPluginZip() {
        val archive = deckyPendingZip ?: return
        deckyPendingZip = null
        if (deckyStage != null || SessionState.running || deckyInstalled == null) return
        deckyStage = activity.getString(R.string.decky_plugin_starting); deckyPercent = -1
        Thread({
            val problem = runCatching {
                DeckyManager.installPluginZip(activity, archive) { label, value ->
                    val binary = DeckyManager.downloadingBinary
                    ui.post { deckyStage = label; deckyPercent = value; deckyDownloadingBinary = binary }
                }
            }.getOrElse { error -> activity.getString(R.string.decky_plugin_failed, error.message ?: error.javaClass.simpleName) }
            ui.post {
                deckyStage = null; deckyPercent = -1; deckyDownloadingBinary = false
                val message = problem ?: activity.getString(R.string.decky_plugin_installed)
                android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }, "import-decky-plugin").start()
    }
}
