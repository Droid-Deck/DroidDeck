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
import com.droiddeck.launcher.gpu.DriverBundle
import com.droiddeck.launcher.gpu.DriverPairs
import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.gpu.LinuxVulkanDriver
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import com.droiddeck.launcher.gpu.TurnipDriver
import com.droiddeck.launcher.gpu.TurnipReleases
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.ui.DriverRow
import com.droiddeck.launcher.ui.GpuDriversState
import com.droiddeck.launcher.ui.PairRow
import com.droiddeck.launcher.wayland.CompositorHost

/**
 * The GPU drivers: what this GPU is, the matched driver pairs the release repos offer for it, and
 * - in Auto, the default - keeping the recommended pair installed and set. Under Advanced, the
 * Linux (runtime) and Android (display) lists as before, each picked on its own - except a
 * bundle's two halves (DriverBundle), which are always picked, and deleted, together. The launcher
 * screen keeps one and hands it to the Components page's GPU drivers tab.
 */
internal class DriverMenus(private val activity: Activity, private val ui: Handler) {
    var linuxRows by mutableStateOf<List<DriverRow>>(emptyList())
    /** The latest Banners-Turnip release as each driver menu offers it (see [refreshReleaseRows]). */
    var linuxDownloads by mutableStateOf<List<com.droiddeck.launcher.ui.DownloadRow>>(emptyList())
    var androidDownloads by mutableStateOf<List<com.droiddeck.launcher.ui.DownloadRow>>(emptyList())
    // Empty until the first refresh: the activity has no context yet while its fields are made.
    var releaseStatus by mutableStateOf("")
    var releaseChecking by mutableStateOf(false)
    var canRestoreBundled by mutableStateOf(false)
    /** Asset name -> download percent, while it downloads. */
    private val releaseProgress = HashMap<String, Int>()
    var linuxSelected by mutableStateOf("")
    val gpu: GpuInfo = GpuInfo.detect()
    // Read in refreshDrivers/ensureAuto: the activity has no context yet while its fields are made.
    var mode by mutableStateOf(SessionPrefs.GPU_DRIVERS_AUTO)
    var pairRows by mutableStateOf<List<PairRow>>(emptyList())
    /** The pair being installed, and how far along. */
    var pairBusy by mutableStateOf<String?>(null)
    var pairPercent by mutableIntStateOf(-1)
    /** What Auto last did or found, for the line under the pair in use. */
    var autoStatus by mutableStateOf("")
    /** The bundle both drivers are set to, as "DD-Turnip 0.1.0", or null when they are not one. */
    var activeBundle by mutableStateOf<String?>(null)
    private var autoCheckedThisProcess = false

    fun state() = GpuDriversState(
        gpuName = gpu.displayName(activity), gpuFamily = gpu.family.label(activity), soc = gpu.soc, supportText = gpu.supportText(activity),
        supported = gpu.support == GpuInfo.Support.TESTED, unsupported = gpu.support == GpuInfo.Support.UNSUPPORTED,
        auto = mode == SessionPrefs.GPU_DRIVERS_AUTO, pairs = pairRows, busy = pairBusy, percent = pairPercent,
        autoStatus = autoStatus, releaseStatus = releaseStatus.ifEmpty { activity.getString(R.string.drivers_not_checked) }, checking = releaseChecking,
        linuxRows = linuxRows, linuxSelected = linuxSelected, androidRows = androidRows, androidSelected = androidSelected,
        linuxDownloads = linuxDownloads, androidDownloads = androidDownloads, canRestoreBundled = canRestoreBundled,
        activeBundle = activeBundle,
    )
    var androidRows by mutableStateOf<List<DriverRow>>(emptyList())
    var androidSelected by mutableStateOf("")

    fun refreshDrivers() {
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
        refreshReleaseRows()
        refreshPairs()
    }

    /** One line for the settings row that opens this: "Auto · WinNative · Balanced". */
    fun summary(): String {
        val active = pairRows.firstOrNull { it.active }?.name ?: activeBundle
        val label = activity.getString(if (mode == SessionPrefs.GPU_DRIVERS_AUTO) R.string.common_auto else R.string.drivers_manual)
        return active?.let { activity.getString(R.string.drivers_summary, label, it) } ?: label
    }

    /** The pairs the last check found, with what is installed and in use; this GPU's first. */
    fun refreshPairs() {
        val lm = LinuxVulkanDriverManager(activity)
        val td = TurnipDriver(activity)
        val pairs = DriverPairs.from(TurnipReleases.cached(activity))
        val recommended = DriverPairs.recommendedKey(gpu, pairs)
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

    fun setMode(auto: Boolean) {
        mode = if (auto) SessionPrefs.GPU_DRIVERS_AUTO else SessionPrefs.GPU_DRIVERS_MANUAL
        SessionPrefs.setGpuDriverMode(activity, mode)
        autoStatus = ""
        if (auto) ensureAuto(force = false)
    }

    /**
     * Auto: make the recommended pair for this GPU the one installed and set. Looks online when
     * [force]d (refresh), or when the last check is a day old - Banners-Turnip rebuilds hourly, and
     * a new pair a day is plenty - otherwise works from the last check. Once per app start on its
     * own; the pair it replaces is removed, anything the user imported or picked is left alone.
     */
    fun ensureAuto(force: Boolean) {
        mode = SessionPrefs.gpuDriverMode(activity)
        if (mode != SessionPrefs.GPU_DRIVERS_AUTO || pairBusy != null || releaseChecking) return
        if (!force && autoCheckedThisProcess) return
        autoCheckedThisProcess = true
        if (DriverPairs.recommendedKey(gpu, emptyList()) == null) {
            autoStatus = activity.getString(R.string.drivers_none_to_set, gpu.supportText(activity).lowercase())
            return
        }
        releaseChecking = true
        autoStatus = activity.getString(R.string.drivers_checking_latest)
        Thread({
            val cached = TurnipReleases.cached(activity)
            val stale = cached == null || System.currentTimeMillis() - cached.checkedAt > 24 * 3_600_000L ||
                cached.assets.none { it.pair.isNotEmpty() }
            val problem = if (force || stale) runCatching { TurnipReleases.refresh(activity) }.exceptionOrNull()?.message else null
            ui.post {
                releaseChecking = false
                refreshReleaseRows()
                refreshPairs()
                val pairs = DriverPairs.from(TurnipReleases.cached(activity))
                val key = DriverPairs.recommendedKey(gpu, pairs)
                val pair = pairs.firstOrNull { it.key == key }
                val row = pairRows.firstOrNull { it.key == key }
                when {
                    row?.active == true -> autoStatus = problem?.let { activity.getString(R.string.drivers_up_to_date_check_failed, it) }
                        ?: activity.getString(R.string.upd_current_status)
                    pair == null || !pair.complete ->
                        autoStatus = problem?.let { activity.getString(R.string.upd_couldnt_check, it) } ?: activity.getString(R.string.drivers_recommended_unpublished)
                    else -> installPair(pair, auto = true)
                }
            }
        }, "gpu-driver-auto").start()
    }

    /** Manual: install (if need be) and set both halves of a pair. */
    fun selectPair(key: String) {
        if (pairBusy != null) return
        val pair = DriverPairs.from(TurnipReleases.cached(activity)).firstOrNull { it.key == key && it.complete } ?: return
        installPair(pair, auto = false)
    }

    /**
     * Download what is missing of [pair], then set both halves - only once both are in, so a
     * failed download never leaves a half-changed pair. Auto's own downloads are recorded, and the
     * ones the new pair replaces are removed.
     */
    private fun installPair(pair: DriverPairs.DriverPair, auto: Boolean) {
        if (!pair.complete) return
        pairBusy = pair.key
        pairPercent = 0
        if (auto) autoStatus = activity.getString(R.string.drivers_downloading_pair, pair.name, pair.version)
        Thread({
            val lm = LinuxVulkanDriverManager(activity)
            val td = TurnipDriver(activity)
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
                    val displayChanged = SessionPrefs.androidDriver(activity) != displayId
                    SessionPrefs.setAndroidDriver(activity, displayId)
                    SessionPrefs.setLinuxDriver(activity, linuxId)
                    val restart = displayChanged && CompositorHost.isStarted
                    if (auto) {
                        val previous = SessionPrefs.gpuAutoInstalled(activity)
                        for (id in previous - setOf(displayId, linuxId)) {
                            val bundle = DriverBundle.containing(activity, id, lm.isInstalled(id))
                            when {
                                bundle != null -> { DriverBundle.remove(activity, bundle); TurnipReleases.forget(activity, bundle.id) }
                                lm.isInstalled(id) -> lm.removeDriver(id)
                                else -> td.remove(id)
                            }
                            TurnipReleases.forget(activity, id)
                        }
                        SessionPrefs.setGpuAutoInstalled(activity, setOf(displayId, linuxId))
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

    /** The bundle a release bundle was downloaded as, while both its halves are installed. */
    private fun installedBundle(asset: TurnipReleases.Asset): DriverBundle.Bundle? =
        TurnipReleases.installedId(activity, asset) { DriverBundle.get(activity, it) != null }?.let { DriverBundle.get(activity, it) }

    /** The id a release asset was installed as (a bundle's id for a bundle), or null when it is not installed. */
    private fun installedIdOf(asset: TurnipReleases.Asset): String? = when {
        asset.bundle -> installedBundle(asset)?.id
        asset.linux -> TurnipReleases.installedId(activity, asset, LinuxVulkanDriverManager(activity)::isInstalled)
        else -> TurnipReleases.installedId(activity, asset, TurnipDriver(activity)::isInstalled)
    }

    /**
     * One release asset, downloaded, checked and installed through the importer; returns its id,
     * a bundle's id for a bundle.
     */
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

    /**
     * Import off the main thread - a driver zip is a few MB and the glibc check reads the whole
     * library - then say what happened. A refusal's message is the user-facing reason. A bundle,
     * from any import, installs both halves and is set as both drivers at once. [linux] null (the
     * tab's own Import .zip) takes a single driver to whichever list its libc belongs in.
     */
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

    /**
     * Pick one driver from a list, which is Manual (Auto would put its pair back). A bundle's half
     * sets the bundle's other half in the other list as well.
     */
    fun selectDriver(id: String, linux: Boolean) {
        val bundle = DriverBundle.containing(activity, id, linux)
        when {
            bundle != null -> useBundle(bundle)
            linux -> SessionPrefs.setLinuxDriver(activity, id)
            else -> SessionPrefs.setAndroidDriver(activity, id)
        }
        setMode(false)
        refreshDrivers()
    }

    /** Set both halves of [bundle] as the drivers in use; returns what to tell the user. */
    private fun useBundle(bundle: DriverBundle.Bundle): String {
        val displayChanged = SessionPrefs.androidDriver(activity) != bundle.androidId
        DriverBundle.select(activity, bundle)
        if (mode == SessionPrefs.GPU_DRIVERS_AUTO) setMode(false)
        return activity.getString(
            if (displayChanged && CompositorHost.isStarted) R.string.drivers_using_bundle_restart else R.string.drivers_using_bundle,
            bundle.label,
        )
    }

    /**
     * Delete an imported or downloaded driver. A mode still set to it goes back to its default, so a
     * session never starts on a driver that is gone; a release download is forgotten, so the menu
     * offers it again.
     */
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

    /** The download entries and the refresh line, from what the last check found. */
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

    /** Only when the user taps refresh: nothing goes online on its own. */
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

    /** Download one release driver and import it through the same importer a picked zip uses. */
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
    }
}

internal fun Context.displayNameOf(uri: Uri): String? = if (uri.scheme == "file") uri.lastPathSegment else try {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
} catch (e: Exception) {
    null
}

