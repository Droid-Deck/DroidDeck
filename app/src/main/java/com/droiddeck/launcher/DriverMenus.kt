package com.droiddeck.launcher

import android.app.Activity
import android.content.Context
import java.io.File
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Handler
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import com.droiddeck.launcher.gpu.AutoDriverTrial
import com.droiddeck.launcher.gpu.AutoPicks
import com.droiddeck.launcher.gpu.DriverBundle
import com.droiddeck.launcher.gpu.DriverPairs
import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.gpu.LinuxVulkanDriver
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import com.droiddeck.launcher.gpu.TurnipDriver
import com.droiddeck.launcher.gpu.TurnipReleases
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.QuestExperimental
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.ui.DriverRow
import com.droiddeck.launcher.ui.DriverSlot
import com.droiddeck.launcher.ui.GPU_MODE_AUTO
import com.droiddeck.launcher.ui.GPU_MODE_MIX
import com.droiddeck.launcher.ui.GPU_MODE_PAIR
import com.droiddeck.launcher.ui.GpuDriversState
import com.droiddeck.launcher.ui.PairRow
import com.droiddeck.launcher.wayland.CompositorHost

internal class DriverMenus(private val activity: Activity, private val ui: Handler) {
    var linuxRows by mutableStateOf<List<DriverRow>>(emptyList())
    var linuxDownloads by mutableStateOf<List<com.droiddeck.launcher.ui.DownloadRow>>(emptyList())
    var androidDownloads by mutableStateOf<List<com.droiddeck.launcher.ui.DownloadRow>>(emptyList())
    var releaseStatus by mutableStateOf("")
    var releaseChecking by mutableStateOf(false)
    var canRestoreBundled by mutableStateOf(false)
    private val releaseProgress = HashMap<String, Int>()
    var linuxSelected by mutableStateOf("")
    val gpu: GpuInfo = GpuInfo.detect()
    var mode by mutableStateOf(SessionPrefs.GPU_DRIVERS_AUTO)
    var pairRows by mutableStateOf<List<PairRow>>(emptyList())
    var pairBusy by mutableStateOf<String?>(null)
    var pairPercent by mutableIntStateOf(-1)
    var autoStatus by mutableStateOf("")
    var activeBundle by mutableStateOf<String?>(null)
    private var lastAutoCheck = 0L
    private var pairModeOpen by mutableStateOf(false)
    var restartPending by mutableStateOf(false)
        private set
    private var runtimePending by mutableStateOf(false)
    private var sessionRunning by mutableStateOf(false)
    var tpUbwcMode: String by mutableStateOf(SessionPrefs.TP_UBWC_AUTO)
        private set

    val uiMode: String get() = when (mode) {
        SessionPrefs.GPU_DRIVERS_AUTO -> GPU_MODE_AUTO
        SessionPrefs.GPU_DRIVERS_MIX -> GPU_MODE_MIX
        else -> if (pairModeOpen || pairRows.any { it.active }) GPU_MODE_PAIR else GPU_MODE_MIX
    }

    fun state() = GpuDriversState(
        gpuName = gpu.displayName(activity), gpuFamily = gpu.family.label(activity), soc = gpu.soc, supportText = gpu.supportText(activity),
        supported = gpu.support == GpuInfo.Support.TESTED, unsupported = gpu.support == GpuInfo.Support.UNSUPPORTED,
        auto = mode == SessionPrefs.GPU_DRIVERS_AUTO, pairs = pairRows, busy = pairBusy, percent = pairPercent,
        autoStatus = autoStatus, releaseStatus = releaseStatus.ifEmpty { activity.getString(R.string.drivers_not_checked) }, checking = releaseChecking,
        linuxRows = linuxRows, linuxSelected = linuxSelected, androidRows = androidRows, androidSelected = androidSelected,
        linuxDownloads = linuxDownloads, androidDownloads = androidDownloads, canRestoreBundled = canRestoreBundled,
        activeBundle = activeBundle,
        mode = uiMode, display = displaySlot(), runtime = runtimeSlot(),
        canRestart = !sessionRunning,
        checkedAt = TurnipReleases.cached(activity)?.checkedAt ?: 0L,
        tpUbwcMode = tpUbwcMode.takeIf { gpu.model == 740 },
        tpUbwcRestartRequired = com.droiddeck.launcher.gpu.TpUbwcHint.restartRequired(activity),
    )

    fun chooseTpUbwcMode(mode: String) {
        if (gpu.model != 740) return
        SessionPrefs.setTpUbwcMode(activity, mode)
        tpUbwcMode = mode
    }

    private fun slotSource(chosenDefault: Boolean, defaultLabel: Int): String = activity.getString(when {
        uiMode == GPU_MODE_AUTO -> R.string.gpu_src_auto
        uiMode == GPU_MODE_PAIR && pairRows.any { it.active } -> R.string.gpu_src_pair
        activeBundle != null -> R.string.gpu_src_bundle
        chosenDefault -> defaultLabel
        else -> R.string.gpu_src_chosen
    })

    private fun displaySlot(): DriverSlot {
        val row = androidRows.firstOrNull { it.id == androidSelected }
        val live = androidRows.firstOrNull { it.id == CompositorHost.displayChoice }?.name
        return DriverSlot(
            name = row?.name ?: activity.getString(R.string.gpu_auto_by_gpu),
            source = slotSource(androidSelected == TurnipDriver.AUTO, R.string.gpu_src_by_gpu),
            pending = restartPending,
            status = when {
                restartPending -> activity.getString(R.string.gpu_display_pending, live ?: activity.getString(R.string.gpu_previous_driver))
                CompositorHost.isStarted -> activity.getString(R.string.gpu_display_live)
                else -> activity.getString(R.string.gpu_display_next)
            },
        )
    }

    private fun runtimeSlot(): DriverSlot {
        val row = linuxRows.firstOrNull { it.id == linuxSelected }
        val live = SessionState.runtimeDriver?.let { id -> linuxRows.firstOrNull { it.id == id }?.name }
        return DriverSlot(
            name = row?.name ?: activity.getString(R.string.gpu_runtime_default),
            source = slotSource(linuxSelected.isEmpty(), R.string.gpu_src_default),
            pending = runtimePending,
            status = when {
                runtimePending -> activity.getString(R.string.gpu_runtime_pending, live ?: activity.getString(R.string.gpu_previous_driver))
                sessionRunning -> activity.getString(R.string.gpu_runtime_live)
                else -> activity.getString(R.string.gpu_runtime_hint)
            },
        )
    }

    fun restartApp() {
        if (SessionState.running) return
        val launch = activity.packageManager.getLaunchIntentForPackage(activity.packageName) ?: return
        activity.startActivity(android.content.Intent.makeRestartActivityTask(launch.component)
            .putExtra(com.droiddeck.launcher.stores.download.StoreDownloadService.EXTRA_NAV, "components"))
        Runtime.getRuntime().exit(0)
    }
    var androidRows by mutableStateOf<List<DriverRow>>(emptyList())
    var androidSelected by mutableStateOf("")

    fun refreshDrivers() {
        tpUbwcMode = SessionPrefs.tpUbwcMode(activity)
        val lm = LinuxVulkanDriverManager(activity)
        fun origin(id: String) = if (TurnipReleases.isDownloaded(activity, id)) DriverRow.DOWNLOADED else DriverRow.IMPORTED
        val bundles = DriverBundle.all(activity)
        fun bundleRow(id: String, linux: Boolean) = bundles.firstOrNull { (if (linux) it.linuxId else it.androidId) == id }?.let {
            DriverRow(id, it.label, activity.getString(if (linux) R.string.drivers_bundle_sets_display else R.string.drivers_bundle_sets_runtime), true, DriverRow.BUNDLE)
        }
        linuxRows = LinuxVulkanDriver.optionValues(activity).map { id ->
            if (id.isEmpty()) DriverRow("", activity.getString(R.string.gpu_runtime_default), activity.getString(R.string.drivers_runtime_builtin), false)
            else bundleRow(id, linux = true) ?: DriverRow(
                id, lm.getDriverName(id),
                listOfNotNull(
                    lm.getDriverVersion(id).takeIf { it.isNotEmpty() },
                    lm.getMinGlibc(id).takeIf { it.isNotEmpty() }?.let { "glibc $it+" },
                ).joinToString(" · "),
                true, origin(id),
            )
        }
        linuxSelected = SessionPrefs.linuxDriver(activity)
        mode = SessionPrefs.gpuDriverMode(activity)
        val td = TurnipDriver(activity)
        val auto = td.autoId()
        androidRows = buildList {
            add(DriverRow(
                TurnipDriver.AUTO, activity.getString(R.string.gpu_auto_by_gpu),
                if (auto == "system") activity.getString(R.string.drivers_system_vulkan) else activity.getString(R.string.drivers_bundled, td.displayName(auto)),
                false,
            ))
            for (id in td.visibleBundled()) add(DriverRow(id, td.displayName(id), td.driverVersion(id), true, DriverRow.BUNDLED))
            for (id in td.enumerateImported()) add(bundleRow(id, linux = false) ?: DriverRow(id, td.displayName(id), td.driverVersion(id), true, origin(id)))
        }
        canRestoreBundled = td.hiddenBundled().isNotEmpty()
        androidSelected = SessionPrefs.androidDriver(activity)
        activeBundle = DriverBundle.active(activity)?.label
        restartPending = CompositorHost.isStarted && CompositorHost.displayChoice.let { it != null && it != androidSelected }
        sessionRunning = SessionState.running
        runtimePending = sessionRunning && SessionState.runtimeDriver.let { it != null && it != linuxSelected }
        refreshReleaseRows()
        refreshPairs()
    }

    fun summary(): String {
        val active = pairRows.firstOrNull { it.active }?.name ?: activeBundle
        val display = displaySlot().name
        val base = when {
            uiMode == GPU_MODE_AUTO -> activity.getString(R.string.drivers_summary, activity.getString(R.string.common_auto), active ?: display)
            uiMode == GPU_MODE_PAIR && active != null -> activity.getString(R.string.drivers_summary, activity.getString(R.string.gpu_mode_pair_short), active)
            else -> activity.getString(R.string.drivers_summary, activity.getString(R.string.gpu_mode_mix_short),
                runtimeSlot().name.let { if (it == display) it else activity.getString(R.string.gpu_summary_mix, display, it) })
        }
        return if (restartPending) activity.getString(R.string.gpu_summary_restart, base) else base
    }

    fun refreshPairs() {
        val lm = LinuxVulkanDriverManager(activity)
        val td = TurnipDriver(activity)
        val pairs = DriverPairs.from(TurnipReleases.cached(activity))
        val recommended = if (QuestExperimental.isSupported()) null else recommendedKey(pairs)
        pairRows = pairs.map { p ->
            val bundle = p.bundle?.let { installedBundle(it) }
            val displayId = if (p.bundle != null) bundle?.androidId else p.display?.let { TurnipReleases.installedId(activity, it, td::isInstalled) }
            val linuxId = if (p.bundle != null) bundle?.linuxId else p.linux?.let { TurnipReleases.installedId(activity, it, lm::isInstalled) }
            val mb = p.assets.sumOf { it.size } / 1_048_576.0
            PairRow(
                key = p.key, name = p.name, version = p.version,
                detail = if (!p.complete) activity.getString(R.string.drivers_half_published) else activity.getString(R.string.drivers_pair_size, mb),
                recommended = p.key == recommended, suits = p.suits(gpu), complete = p.complete,
                installed = displayId != null && linuxId != null,
                active = displayId != null && linuxId != null &&
                    SessionPrefs.androidDriver(activity) == displayId && SessionPrefs.linuxDriver(activity) == linuxId,
            )
        }.sortedByDescending { it.recommended }
    }

    private fun recommendedKey(pairs: List<DriverPairs.DriverPair>) = DriverPairs.recommendedKey(
        gpu, pairs, TurnipReleases.autoPicks(activity), AutoDriverTrial.bad(activity),
        blob = AutoPicks.blobVersion(com.droiddeck.launcher.gpu.VulkanInfo.cachedOrNull()?.get("driver_version")),
    )

    fun chooseMode(next: String) {
        pairModeOpen = next == GPU_MODE_PAIR
        setMode(auto = next == GPU_MODE_AUTO, mix = next == GPU_MODE_MIX)
    }

    fun setMode(auto: Boolean, mix: Boolean = false) {
        mode = when {
            auto -> SessionPrefs.GPU_DRIVERS_AUTO
            mix -> SessionPrefs.GPU_DRIVERS_MIX
            else -> SessionPrefs.GPU_DRIVERS_MANUAL
        }
        SessionPrefs.setGpuDriverMode(activity, mode)
        autoStatus = ""
        if (auto) ensureAuto(force = false)
    }

    fun checkOnOpen() {
        if (SessionPrefs.gpuDriverMode(activity) == SessionPrefs.GPU_DRIVERS_AUTO) ensureAuto(force = false)
        else if (!releaseChecking && releasesStale() && com.droiddeck.launcher.frontend.AutoComponents.isOnline(activity)) checkLatestTurnip()
    }

    private fun releasesStale(): Boolean {
        val cached = TurnipReleases.cached(activity)
        return cached == null || System.currentTimeMillis() - cached.checkedAt > RELEASE_CHECK_INTERVAL_MS ||
            cached.assets.none { it.pair.isNotEmpty() }
    }

    fun ensureAuto(force: Boolean) {
        mode = SessionPrefs.gpuDriverMode(activity)
        if (mode != SessionPrefs.GPU_DRIVERS_AUTO || pairBusy != null || releaseChecking) return
        if (SessionState.running) {
            if (force) autoStatus = activity.getString(R.string.drivers_auto_waits_for_session)
            return
        }
        AutoDriverTrial.rollbackDue(activity)?.let { trial ->
            AutoDriverTrial.rollBack(activity, trial)
            autoStatus = activity.getString(R.string.drivers_rolled_back, trial.name)
            refreshDrivers()
            return
        }
        if (!force && System.currentTimeMillis() - lastAutoCheck < RELEASE_CHECK_INTERVAL_MS) return
        lastAutoCheck = System.currentTimeMillis()
        val displayChanged = SessionPrefs.androidDriver(activity) != QuestExperimental.defaultAndroidDriver()
        if (QuestExperimental.applyAutoDrivers(activity)) {
            autoStatus = activity.getString(
                if (displayChanged && CompositorHost.isStarted) R.string.drivers_using_quest_restart else R.string.drivers_using_quest,
            )
            refreshDrivers()
            return
        }
        if (DriverPairs.recommendedKey(gpu, emptyList()) == null) {
            autoStatus = activity.getString(R.string.drivers_none_to_set, gpu.supportText(activity).lowercase())
            return
        }
        releaseChecking = true
        autoStatus = activity.getString(R.string.drivers_checking_latest)
        Thread({
            val problem = if (force || releasesStale()) runCatching { TurnipReleases.refresh(activity) }.exceptionOrNull()?.message else null
            val holdBack = !force && com.droiddeck.launcher.frontend.AutoComponents.constrained(activity)
            runCatching { com.droiddeck.launcher.gpu.VulkanInfo.query() }
            ui.post {
                releaseChecking = false
                refreshReleaseRows()
                refreshPairs()
                val pairs = DriverPairs.from(TurnipReleases.cached(activity))
                val key = recommendedKey(pairs)
                val pair = pairs.firstOrNull { it.key == key }
                val row = pairRows.firstOrNull { it.key == key }
                when {
                    row?.active == true -> autoStatus = problem?.let { activity.getString(R.string.drivers_up_to_date_check_failed, it) }
                        ?: activity.getString(R.string.upd_current_status)
                    pair == null || !pair.complete ->
                        autoStatus = problem?.let { activity.getString(R.string.upd_couldnt_check, it) } ?: activity.getString(R.string.drivers_recommended_unpublished)
                    AutoDriverTrial.release(pair) in AutoDriverTrial.bad(activity) ->
                        autoStatus = activity.getString(R.string.drivers_skipping_bad, pair.name, pair.version)
                    SessionState.running -> autoStatus = activity.getString(R.string.drivers_auto_waits_for_session)
                    holdBack && row?.installed != true -> autoStatus = activity.getString(R.string.drivers_auto_waits_for_network, pair.name, pair.version)
                    else -> installPair(pair, auto = true)
                }
            }
        }, "gpu-driver-auto").start()
    }

    fun selectPair(key: String) {
        if (pairBusy != null) return
        val pair = DriverPairs.from(TurnipReleases.cached(activity)).firstOrNull { it.key == key && it.complete } ?: return
        pairModeOpen = true
        if (mode != SessionPrefs.GPU_DRIVERS_MANUAL) setMode(auto = false)
        installPair(pair, auto = false)
    }

    private fun installPair(pair: DriverPairs.DriverPair, auto: Boolean) {
        if (!pair.complete) return
        pairBusy = pair.key
        pairPercent = 0
        if (auto) autoStatus = activity.getString(R.string.drivers_downloading_pair, pair.name, pair.version)
        Thread({
            var downloaded = false
            val result = runCatching {
                val zips = pair.assets
                val ids = zips.mapIndexed { i, asset ->
                    installedIdOf(asset)
                        ?: installAsset(asset) { pct -> ui.post { pairPercent = (i * 100 + pct) / zips.size } }.also { downloaded = true }
                }
                if (pair.bundle != null) DriverBundle.get(activity, ids[0])!!.let { listOf(it.androidId, it.linuxId) } else ids
            }
            ui.post {
                pairBusy = null
                pairPercent = -1
                result.onSuccess { (displayId, linuxId) ->
                    if (auto && SessionState.running) {
                        lastAutoCheck = 0L
                        autoStatus = activity.getString(R.string.drivers_auto_waits_for_session)
                        return@onSuccess
                    }
                    val previousDisplay = SessionPrefs.androidDriver(activity)
                    val previousLinux = SessionPrefs.linuxDriver(activity)
                    val displayChanged = previousDisplay != displayId
                    SessionPrefs.setAndroidDriver(activity, displayId)
                    SessionPrefs.setLinuxDriver(activity, linuxId)
                    val restart = displayChanged && CompositorHost.isStarted
                    if (auto) {
                        val trial = AutoDriverTrial.current(activity)
                        val lastGood = trial?.let { setOf(it.previousDisplay, it.previousLinux) } ?: setOf(previousDisplay, previousLinux)
                        val changed = previousDisplay != displayId || previousLinux != linuxId
                        SessionPrefs.setGpuAutoInstalled(activity, SessionPrefs.gpuAutoInstalled(activity) + setOf(displayId, linuxId))
                        if (changed) AutoDriverTrial.begin(
                            activity, AutoDriverTrial.release(pair), "${pair.name} ${pair.version}", displayId, linuxId,
                            trial?.previousDisplay ?: previousDisplay, trial?.previousLinux ?: previousLinux,
                        )
                        if (!SessionState.running) AutoDriverTrial.prune(activity, lastGood + setOf(displayId, linuxId))
                        autoStatus = activity.getString(
                            if (downloaded) { if (restart) R.string.drivers_updated_to_restart else R.string.drivers_updated_to }
                            else { if (restart) R.string.drivers_switched_to_restart else R.string.drivers_switched_to },
                            pair.name, pair.version,
                        )
                    } else {
                        android.widget.Toast.makeText(activity, activity.getString(if (restart) R.string.drivers_using_restart else R.string.drivers_using, pair.name, pair.version), android.widget.Toast.LENGTH_LONG).show()
                    }
                }.onFailure { e ->
                    Log.w(TAG, "driver pair ${pair.key}", e)
                    val why = if (e is IllegalArgumentException) e.message else activity.getString(R.string.comp_download_failed, e.message)
                    if (auto) autoStatus = why ?: activity.getString(R.string.user_apps_download_failed)
                    else android.widget.Toast.makeText(activity, why, android.widget.Toast.LENGTH_LONG).show()
                }
                refreshDrivers()
            }
        }, "gpu-driver-pair").start()
    }

    private fun installedBundle(asset: TurnipReleases.Asset): DriverBundle.Bundle? =
        TurnipReleases.installedId(activity, asset) { DriverBundle.get(activity, it) != null }?.let { DriverBundle.get(activity, it) }

    private fun installedIdOf(asset: TurnipReleases.Asset): String? = when {
        asset.bundle -> installedBundle(asset)?.id
        asset.linux -> TurnipReleases.installedId(activity, asset, LinuxVulkanDriverManager(activity)::isInstalled)
        else -> TurnipReleases.installedId(activity, asset, TurnipDriver(activity)::isInstalled)
    }

    private fun installAsset(asset: TurnipReleases.Asset, progress: (Int) -> Unit): String {
        var file: File? = null
        try {
            file = TurnipReleases.download(activity, asset, progress)
            val uri = Uri.fromFile(file)
            val id = when {
                asset.bundle -> DriverBundle.install(activity, uri).id
                asset.linux -> LinuxVulkanDriverManager(activity).installDriver(uri, asset.name)
                else -> TurnipDriver(activity).installFromZip(uri, asset.name)
            }
            TurnipReleases.recordDownload(activity, asset, id)
            return id
        } finally {
            file?.let { com.droiddeck.launcher.core.FileUtils.delete(it) }
        }
    }

    fun importDriver(uri: Uri, linux: Boolean?) {
        val name = activity.displayNameOf(uri)
        Thread({
            var bundle: DriverBundle.Bundle? = null
            val problem = try {
                when {
                    DriverBundle.isBundle(activity, uri) -> bundle = DriverBundle.install(activity, uri)
                    linux == true -> LinuxVulkanDriverManager(activity).installDriver(uri, name)
                    linux == false -> TurnipDriver(activity).installFromZip(uri, name)
                    else -> try {
                        TurnipDriver(activity).installFromZip(uri, name)
                    } catch (display: IllegalArgumentException) {
                        try {
                            LinuxVulkanDriverManager(activity).installDriver(uri, name)
                        } catch (runtime: IllegalArgumentException) {
                            throw IllegalArgumentException(activity.getString(R.string.drivers_not_a_driver))
                        }
                    }
                }
                null
            } catch (e: IllegalArgumentException) {
                e.message
            } catch (e: Exception) {
                Log.w(TAG, "driver import", e)
                activity.getString(R.string.drivers_import_failed, e.message)
            }
            ui.post {
                val done = bundle?.let { useBundle(it) }
                    ?: name?.let { activity.getString(R.string.comp_imported, it) } ?: activity.getString(R.string.drivers_imported_unnamed)
                android.widget.Toast.makeText(
                    activity, problem ?: done,
                    if (problem != null || bundle != null) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT,
                ).show()
                refreshDrivers()
            }
        }, "import-driver").start()
    }

    fun selectDriver(id: String, linux: Boolean) {
        val bundle = DriverBundle.containing(activity, id, linux)
        when {
            bundle != null -> useBundle(bundle)
            linux -> SessionPrefs.setLinuxDriver(activity, id)
            else -> SessionPrefs.setAndroidDriver(activity, id)
        }
        pairModeOpen = false
        setMode(auto = false, mix = true)
        refreshDrivers()
    }

    private fun useBundle(bundle: DriverBundle.Bundle): String {
        val displayChanged = SessionPrefs.androidDriver(activity) != bundle.androidId
        DriverBundle.select(activity, bundle)
        if (mode == SessionPrefs.GPU_DRIVERS_AUTO) setMode(auto = false, mix = true)
        return activity.getString(
            if (displayChanged && CompositorHost.isStarted) R.string.drivers_using_bundle_restart else R.string.drivers_using_bundle,
            bundle.label,
        )
    }

    fun deleteDriver(id: String, linux: Boolean) {
        val bundle = DriverBundle.containing(activity, id, linux)
        if (bundle != null) {
            DriverBundle.remove(activity, bundle)
            TurnipReleases.forget(activity, bundle.id)
            android.widget.Toast.makeText(activity, activity.getString(R.string.drivers_deleted_bundle, bundle.label), android.widget.Toast.LENGTH_SHORT).show()
            refreshDrivers()
            return
        }
        if (linux) {
            LinuxVulkanDriverManager(activity).removeDriver(id)
            if (SessionPrefs.linuxDriver(activity) == id) SessionPrefs.setLinuxDriver(activity, "")
        } else {
            val td = TurnipDriver(activity)
            if (id in TurnipDriver.BUNDLED) td.hideBundled(id) else td.remove(id)
            if (SessionPrefs.androidDriver(activity) == id) SessionPrefs.setAndroidDriver(activity, TurnipDriver.AUTO)
        }
        TurnipReleases.forget(activity, id)
        android.widget.Toast.makeText(activity, activity.getString(R.string.drivers_deleted, id), android.widget.Toast.LENGTH_SHORT).show()
        refreshDrivers()
    }

    fun refreshReleaseRows() {
        val check = TurnipReleases.cached(activity)
        fun rows(linux: Boolean) = check?.assets.orEmpty()
            .filter { it.bundle || it.linux == linux }
            .filter { a -> installedIdOf(a) == null }
            .map { a ->
                val mb = activity.getString(R.string.common_size_mb, a.size / 1_048_576.0)
                com.droiddeck.launcher.ui.DownloadRow(a.name, "${a.source} ${a.tag}", "${a.label} · $mb", releaseProgress[a.name])
            }
        linuxDownloads = rows(linux = true)
        androidDownloads = rows(linux = false)
        if (!releaseChecking) releaseStatus = when (check) {
            null -> activity.getString(R.string.drivers_not_checked)
            else -> {
                val latest = check.latest.joinToString(" · ") { "${it.first} ${it.second}" }
                if (check.failed.isEmpty()) activity.getString(R.string.drivers_release_status, latest, ago(check.checkedAt))
                else activity.getString(R.string.drivers_release_status_failed, latest, check.failed.joinToString(), ago(check.checkedAt))
            }
        }
    }

    private fun ago(t: Long): String {
        val m = ((System.currentTimeMillis() - t) / 60_000).coerceAtLeast(0)
        return when {
            m < 1 -> activity.getString(R.string.upd_just_now)
            m < 60 -> activity.resources.getQuantityString(R.plurals.drivers_ago_minutes, m.toInt(), m)
            m < 48 * 60 -> activity.resources.getQuantityString(R.plurals.drivers_ago_hours, (m / 60).toInt(), m / 60)
            else -> activity.resources.getQuantityString(R.plurals.drivers_ago_days, (m / (24 * 60)).toInt(), m / (24 * 60))
        }
    }

    fun checkLatestTurnip() {
        if (releaseChecking) return
        releaseChecking = true
        releaseStatus = activity.getString(R.string.drivers_checking_sources)
        Thread({
            val problem = try { TurnipReleases.refresh(activity); null } catch (e: Exception) {
                Log.w(TAG, "latest Turnip check", e); e.message ?: activity.getString(R.string.drivers_check_failed)
            }
            ui.post {
                releaseChecking = false
                refreshReleaseRows()
                refreshPairs()
                if (problem != null) releaseStatus = activity.getString(R.string.upd_couldnt_check, problem)
            }
        }, "turnip-release-check").start()
    }

    fun downloadReleaseDriver(assetName: String) {
        val asset = TurnipReleases.cached(activity)?.assets?.firstOrNull { it.name == assetName } ?: return
        if (releaseProgress.containsKey(assetName)) return
        releaseProgress[assetName] = 0
        refreshReleaseRows()
        Thread({
            val problem = try {
                installAsset(asset) { pct -> ui.post { releaseProgress[assetName] = pct; refreshReleaseRows() } }
                null
            } catch (e: IllegalArgumentException) {
                e.message
            } catch (e: Exception) {
                Log.w(TAG, "release driver download", e)
                activity.getString(R.string.comp_download_failed, e.message)
            }
            ui.post {
                releaseProgress.remove(assetName)
                val installed = activity.getString(
                    if (asset.bundle) R.string.drivers_installed_pick_either else R.string.drivers_installed_pick,
                    asset.name.removeSuffix(".zip"),
                )
                android.widget.Toast.makeText(
                    activity, problem ?: installed,
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                refreshDrivers()
            }
        }, "download-turnip").start()
    }

    private companion object {
        const val TAG = "MainActivity"
        const val RELEASE_CHECK_INTERVAL_MS = 3_600_000L
    }
}

internal fun Context.displayNameOf(uri: Uri): String? = if (uri.scheme == "file") uri.lastPathSegment else try {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
} catch (e: Exception) {
    null
}

