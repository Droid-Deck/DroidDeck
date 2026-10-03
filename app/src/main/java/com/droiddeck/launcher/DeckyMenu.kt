package com.droiddeck.launcher

import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.runtime.DeckyManager
import com.droiddeck.launcher.session.SessionState
import java.io.File

/**
 * Decky Loader's state in the launcher: what is installed, the releases on offer and an install
 * in progress. The launcher screen keeps one and shows it.
 */
internal class DeckyMenu(private val activity: android.app.Activity, private val ui: android.os.Handler) {
    var deckyInstalled by mutableStateOf<String?>(null)
    var deckyReleases by mutableStateOf<List<DeckyManager.Release>>(emptyList())
    var deckyChecking by mutableStateOf(false)
    var deckyStage by mutableStateOf<String?>(null)
    var deckyPercent by mutableIntStateOf(-1)
    var deckySupervisor by mutableStateOf(false)
    var deckyReleaseRequest = 0

    fun refreshDecky() {
        val request = ++deckyReleaseRequest
        deckyChecking = true
        Thread({
            val channels = runCatching { DeckyManager.releaseChannels(activity) }
                .getOrElse { DeckyManager.ReleaseChannels(emptyList(), emptyList()) }
            ui.post {
                if (request == deckyReleaseRequest) {
                    // Use the newest compatible stable build, or the newest compatible
                    // prerelease when the fork has not published a stable ARM64 asset.
                    deckyReleases = channels.stable.ifEmpty { channels.prerelease }
                    deckyChecking = false
                }
            }
        }, "decky-releases").start()
    }

    fun installDecky(release: DeckyManager.Release) {
        if (deckyStage != null || SessionState.running) return
        deckyStage = "Starting…"; deckyPercent = -1
        Thread({
            val problem = runCatching {
                DeckyManager.install(activity, release) { label, value -> ui.post { deckyStage = label; deckyPercent = value } }
            }.getOrElse { error -> "Decky install failed: ${error.message ?: error.javaClass.simpleName}" }
            ui.post {
                deckyStage = null; deckyPercent = -1; deckyInstalled = DeckyManager.installed(activity)
                if (problem != null) android.widget.Toast.makeText(activity, problem, android.widget.Toast.LENGTH_LONG).show()
            }
        }, "install-decky").start()
    }

    fun importPluginZip(archive: File) {
        if (deckyStage != null || SessionState.running || deckyInstalled == null) return
        deckyStage = "Starting plugin import…"; deckyPercent = -1
        Thread({
            val problem = runCatching {
                DeckyManager.installPluginZip(activity, archive) { label, value ->
                    ui.post { deckyStage = label; deckyPercent = value }
                }
            }.getOrElse { error -> "Plugin import failed: ${error.message ?: error.javaClass.simpleName}" }
            ui.post {
                deckyStage = null; deckyPercent = -1
                val message = problem ?: "Plugin installed. Restart Steam to load it."
                android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }, "import-decky-plugin").start()
    }
}
