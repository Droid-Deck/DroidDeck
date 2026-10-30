package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.AddonPack
import android.content.Context
import android.os.Environment
import android.util.Log
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.runtime.BwrapSpawner
import com.droiddeck.launcher.runtime.DesktopCatalog
import com.droiddeck.launcher.runtime.LegacyNames
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.ProotFastPath
import java.io.File

object SessionFiles {
    private const val TAG = "SessionFiles"
    private const val NO_PAD_SWITCH = "Download/droiddeck-no-pad"
    const val DIRECTAUDIO_DIR = "usr/local/lib/directaudio"
    private const val STAGED_LEDGER = "usr/local/.droiddeck-staged"
    val DIRECTAUDIO_SETS = arrayOf("linux-wine11", "linux-wine11-systhread")

    internal val DESKTOP_FILES = arrayOf(
        "usr/local/bin/droiddeck-desktop" to "usr/local/bin/droiddeck-desktop",
        "usr/local/bin/droiddeck-gpu" to "usr/local/bin/droiddeck-gpu",
        "usr/local/bin/droiddeck-desktop-gpu" to "usr/local/bin/droiddeck-desktop-gpu",
        "usr/local/bin/droiddeck-desktop-window-rules" to "usr/local/bin/droiddeck-desktop-window-rules",
        "usr/local/bin/droiddeck-library-perms" to "usr/local/bin/droiddeck-library-perms",
        "usr/local/bin/droiddeck-ime" to "usr/local/bin/droiddeck-ime",
        "usr/local/bin/kwin_wayland_wrapper" to "usr/local/bin/kwin_wayland_wrapper",
        "usr/lib/firefox/defaults/pref/droiddeck.js" to "usr/lib/firefox/defaults/pref/droiddeck.js",
        "etc/xdg/autostart/droiddeck-clipboard.desktop" to "etc/xdg/autostart/droiddeck-clipboard.desktop",
    )

    /** Puts the session's files in place; [startsSession] is true only for the call that starts the Steam session. */
    fun stage(context: Context, root: File, startsSession: Boolean = false) {
        if (DesktopCatalog.desktopInstalled(root) && !DesktopCatalog.desktopFromPack(root)) try { DesktopCatalog.cleanLegacy(context) }
        catch (e: Exception) { Log.w(TAG, "LXQt cleanup will retry next session", e) }
        GameEnvironmentStore.publish(context)
        WinComponents.publish(context)
        val files = arrayOf(
            "usr/local/bin/droiddeck-game-env" to "usr/local/bin/droiddeck-game-env",
            "usr/local/bin/droiddeck-wincomponents" to "usr/local/bin/droiddeck-wincomponents",
            "usr/local/bin/droiddeck-msi-install" to "usr/local/bin/droiddeck-msi-install",
            "usr/local/bin/droiddeck-esync" to "usr/local/bin/droiddeck-esync",
            "usr/local/bin/droiddeck-steam-compat" to "usr/local/bin/droiddeck-steam-compat",
            "usr/local/bin/droiddeck-fex" to "usr/local/bin/droiddeck-fex",
            "libblsession.so" to "usr/local/lib/libblsession.so",
            "libfakeinput.so" to "usr/local/lib/libfakeinput.so",
            "libblfastpath.so" to "usr/local/lib/libblfastpath.so",
            "libblaudit.so" to "usr/local/lib/libblaudit.so",
            "libssbs.so" to "usr/local/lib/libssbs.so",
            "usr/local/bin/droiddeck-session" to "usr/local/bin/droiddeck-session",
            "usr/local/bin/droiddeck-fonts" to "usr/local/bin/droiddeck-fonts",
            "usr/local/bin/droiddeck-agent" to "usr/local/bin/droiddeck-agent",
            "usr/local/bin/steam-compatibility" to "usr/local/bin/steam-compatibility",
            "usr/local/bin/droiddeck-clipboard" to "usr/local/bin/droiddeck-clipboard",
            "usr/local/bin/droiddeck-desktop-clipboard" to "usr/local/bin/droiddeck-desktop-clipboard",
            "usr/local/bin/droiddeck-gpu-window" to "usr/local/bin/droiddeck-gpu-window",
            "usr/local/bin/droiddeck-steam-install" to "usr/local/bin/droiddeck-steam-install",
            "usr/local/bin/droiddeck-steam-ui-scale" to "usr/local/bin/droiddeck-steam-ui-scale",
            "usr/local/bin/droiddeck-steam-language" to "usr/local/bin/droiddeck-steam-language",
            "usr/local/bin/droiddeck-steam-shaders" to "usr/local/bin/droiddeck-steam-shaders",
            "usr/local/bin/droiddeck-steam-desktop-ui" to "usr/local/bin/droiddeck-steam-desktop-ui",
            "usr/local/bin/droiddeck-steam-library" to "usr/local/bin/droiddeck-steam-library",
            "usr/local/bin/droiddeck-steam-share" to "usr/local/bin/droiddeck-steam-share",
            "usr/local/bin/droiddeck-seed-redists" to "usr/local/bin/droiddeck-seed-redists",
            "usr/local/bin/droiddeck-launcher-cores" to "usr/local/bin/droiddeck-launcher-cores",
            "usr/local/bin/droiddeck-proton-extra" to "usr/local/bin/droiddeck-proton-extra",
            "usr/local/bin/droiddeck-netmanager" to "usr/local/bin/droiddeck-netmanager",
            "usr/local/bin/droiddeck-login1" to "usr/local/bin/droiddeck-login1",
            "usr/local/bin/droiddeck-steam-launch" to "usr/local/bin/droiddeck-steam-launch",
            "usr/local/bin/droiddeck-desktop-games" to "usr/local/bin/droiddeck-desktop-games",
            "usr/local/bin/droiddeck-desktop-bookmarks" to "usr/local/bin/droiddeck-desktop-bookmarks",
            "usr/local/bin/droiddeck-downloads" to "usr/local/bin/droiddeck-downloads",
            "usr/local/bin/droiddeck-root" to "usr/local/bin/droiddeck-root",
            "usr/local/bin/droiddeck-text" to "usr/local/bin/droiddeck-text",
            "usr/local/bin/droiddeck-snapd" to "usr/local/bin/droiddeck-snapd",
            "usr/local/bin/droiddeck-emulators" to "usr/local/bin/droiddeck-emulators",
            "usr/local/bin/droiddeck-snap-run" to "usr/local/bin/droiddeck-snap-run",
            "usr/local/bin/droiddeck-snap-confine" to "usr/local/bin/droiddeck-snap-confine",
            "usr/local/bin/droiddeck-snapctl" to "usr/local/bin/droiddeck-snapctl",
            "usr/local/bin/droiddeck-systemd-run" to "usr/local/bin/systemd-run",
            "usr/local/bin/droiddeck-snap-cli" to "usr/local/bin/snap",
            "usr/local/bin/droiddeck-steam-shim" to "usr/local/bin/droiddeck-steam-shim",
            "usr/local/bin/droiddeck-steam-shortcuts" to "usr/local/bin/droiddeck-steam-shortcuts",
            "usr/local/bin/droiddeck-xbox" to "usr/local/bin/droiddeck-xbox",
            "usr/local/bin/droiddeck-store-launch" to "usr/local/bin/droiddeck-store-launch",
            "usr/local/bin/droiddeck-gog-maintenance" to "usr/local/bin/droiddeck-gog-maintenance",
            "usr/local/bin/droiddeck-battlenet" to "usr/local/bin/droiddeck-battlenet",
            "usr/local/bin/droiddeck-epic" to "usr/local/bin/droiddeck-epic",
            "usr/local/bin/droiddeck-epic-steam" to "usr/local/bin/droiddeck-epic-steam",
            "usr/local/bin/droiddeck-open-url" to "usr/local/bin/droiddeck-open-url",
            "usr/local/bin/droiddeck-steam-games" to "usr/local/bin/droiddeck-steam-games",
            "usr/local/bin/droiddeck-pad-defaults" to "usr/local/bin/droiddeck-pad-defaults",
            "usr/local/bin/droiddeck-steam-x64" to "usr/local/bin/droiddeck-steam-x64",
            "usr/local/bin/droiddeck-fex-update" to "usr/local/bin/droiddeck-fex-update",
            "usr/local/bin/droiddeck-steamrt-shim" to "usr/local/bin/droiddeck-steamrt-shim",
            "usr/local/bin/droiddeck-x64-arm-proton" to "usr/local/bin/droiddeck-x64-arm-proton",
            "usr/local/bin/droiddeck-x64-arm-webhelper" to "usr/local/bin/droiddeck-x64-arm-webhelper",
            "usr/local/bin/droiddeck-x64-webhelper" to "usr/local/bin/droiddeck-x64-webhelper",
            "usr/local/bin/droiddeck-steamvr" to "usr/local/bin/droiddeck-steamvr",
            "usr/local/bin/droiddeck-pipewire" to "usr/local/bin/droiddeck-pipewire",
            "usr/local/bin/droiddeck-steamvr-settings" to "usr/local/bin/droiddeck-steamvr-settings",
            "usr/local/bin/droiddeck-steamvr-game" to "usr/local/bin/droiddeck-steamvr-game",
            "usr/local/bin/droiddeck-steamvr-flat" to "usr/local/bin/droiddeck-steamvr-flat",
            "usr/local/bin/droiddeck-bwrap" to "usr/local/bin/droiddeck-bwrap",
            "usr/local/bin/droiddeck-flatpak" to "usr/local/bin/droiddeck-flatpak",
            "usr/local/bin/droiddeck-flatpak-setup" to "usr/local/bin/droiddeck-flatpak-setup",
            "usr/local/bin/droiddeck-flatpak-run" to "usr/local/bin/droiddeck-flatpak-run",
            "usr/local/bin/droiddeck-appimage-run" to "usr/local/bin/droiddeck-appimage-run",
            "usr/local/bin/droiddeck-script-run" to "usr/local/bin/droiddeck-script-run",
            "usr/local/bin/droiddeck-open" to "usr/local/bin/droiddeck-open",
            "usr/local/bin/droiddeck-proton-run" to "usr/local/bin/droiddeck-proton-run",
            "usr/local/bin/droiddeck-innoextract" to "usr/local/bin/droiddeck-innoextract",
            "usr/local/lib/droiddeck-inno/innoextract" to "usr/local/lib/droiddeck-inno/innoextract",
            "usr/local/lib/droiddeck-inno/libboost_container.so.1.92.0" to "usr/local/lib/droiddeck-inno/libboost_container.so.1.92.0",
            "usr/local/lib/droiddeck-inno/libboost_filesystem.so.1.92.0" to "usr/local/lib/droiddeck-inno/libboost_filesystem.so.1.92.0",
            "usr/local/lib/droiddeck-inno/libboost_iostreams.so.1.92.0" to "usr/local/lib/droiddeck-inno/libboost_iostreams.so.1.92.0",
            "usr/local/lib/droiddeck-inno/libboost_program_options.so.1.92.0" to "usr/local/lib/droiddeck-inno/libboost_program_options.so.1.92.0",
            "usr/local/lib/droiddeck-inno/libboost_random.so.1.92.0" to "usr/local/lib/droiddeck-inno/libboost_random.so.1.92.0",
            "usr/local/share/licenses/droiddeck-inno/LICENSE.innoextract" to "usr/local/share/licenses/droiddeck-inno/LICENSE.innoextract",
            "usr/local/share/licenses/droiddeck-inno/LICENSE.boost" to "usr/local/share/licenses/droiddeck-inno/LICENSE.boost",
            "usr/bin/steamos-update" to "usr/bin/steamos-update",
            "usr/bin/steamos-select-branch" to "usr/bin/steamos-select-branch",
            "usr/bin/steamos-session-select" to "usr/bin/steamos-session-select",
            "usr/bin/jupiter-biosupdate" to "usr/bin/jupiter-biosupdate",
            "usr/bin/steamos-polkit-helpers/steamos-priv-write" to "usr/bin/steamos-polkit-helpers/steamos-priv-write",
            "usr/bin/steamos-polkit-helpers/steamos-set-timezone" to "usr/bin/steamos-polkit-helpers/steamos-set-timezone",
            "usr/bin/steamos-polkit-helpers/steamos-update" to "usr/bin/steamos-polkit-helpers/steamos-update",
            "usr/bin/steamos-polkit-helpers/steamos-select-branch" to "usr/bin/steamos-polkit-helpers/steamos-select-branch",
            "usr/bin/steamos-polkit-helpers/jupiter-biosupdate" to "usr/bin/steamos-polkit-helpers/jupiter-biosupdate",
            "usr/bin/steamos-polkit-helpers/jupiter-dock-updater" to "usr/bin/steamos-polkit-helpers/jupiter-dock-updater",
        )
        val mangoapp = listOf(
            "usr/local/bin/mangoapp",
            "usr/local/lib/mangoapp/mangoapp",
            "usr/local/lib/mangoapp/libfmt.so.10",
            "usr/local/lib/mangoapp/libspdlog.so.1.13",
            "usr/local/lib/mangoapp/libglfw.so.3",
            "usr/local/lib/mangoapp/libtraceevent.so.1",
            "usr/local/lib/mangoapp/libtracefs.so.1",
        ).map { it to it }
        val msitools = listOf("msiinfo", "cabextract", "7z", "7z.so", "libmsi-1.0.so.0", "libgsf-1.so.114", "libgcab-1.0.so.0")
            .map { "usr/local/lib/droiddeck-msitools/$it" } +
            listOf("NOTICE", "GPL-2", "GPL-3", "LGPL-2.1", "7zip-License", "7zip-unRarLicense").map { "usr/local/share/licenses/droiddeck-msitools/$it" }
        val handModels = listOf("left", "right").flatMap { side ->
            listOf("droiddeck_hand_$side.json", "hand.mtl", "hand.png", "hand_open.obj", "hand_fist.obj", "thumb_index_open.obj",
                "thumb_index_pinch.obj")
                .map { "droiddeck/resources/rendermodels/droiddeck_hand_$side/$it" }
        }
        val vr = (listOf(
            "droiddeck/bin/linuxarm64/driver_droiddeck.so",
            "droiddeck/bin/linuxarm64/xr-display-mode",
            "droiddeck/driver.vrdrivermanifest",
            "droiddeck/resources/settings/default.vrsettings",
            "layer/libVkLayer_droiddeck_vr.so",
            "layer/libdroiddeck_vr_scheduling.so",
            "layer/VK_LAYER_DROIDDECK_vr.json",
        ) + handModels).map { "usr/local/lib/droiddeck-vr/$it" to "usr/local/lib/droiddeck-vr/$it" } +
            listOf("usr/local/lib/droiddeck-vr/layer/VK_LAYER_DROIDDECK_vr_implicit.json" to
                "usr/share/vulkan/implicit_layer.d/droiddeck_vr.json")
        val fexPreloads = listOf("x86_64", "i386").flatMap { arch ->
            listOf("libblsession.so", "libfakeinput.so", "libfaultreport.so").map { "$arch/$it" to "usr/local/lib/droiddeck-fex/$arch/$it" }
        } + listOf("libvulkan-thunk.so", "libwebkitinput.so").map { "x86_64/$it" to "usr/local/lib/droiddeck-fex/x86_64/$it" }
        val optional = (arrayOf(
            "usr/local/bin/gamescope" to "usr/local/bin/gamescope",
            // The gamescope pack carries its own WSI layer (patch 0117); it replaces the runtime's stock copy.
            "usr/lib/libVkLayer_FROG_gamescope_wsi_aarch64.so" to "usr/lib/libVkLayer_FROG_gamescope_wsi_aarch64.so",
            "usr/local/lib/droiddeck/uruntime" to "usr/local/lib/droiddeck/uruntime",
            "usr/local/lib/droiddeck-pipewire.tzst" to "usr/local/lib/droiddeck-pipewire.tzst",
            "usr/local/share/licenses/uruntime/LICENSE" to "usr/local/share/licenses/uruntime/LICENSE",
        ) + mangoapp + msitools.map { it to it } + vr + fexPreloads).filter { (asset, _) ->
            val dir = asset.substringBeforeLast('/')
            runCatching { context.assets.list("linuxfs/$dir")?.contains(asset.substringAfterLast('/')) == true }.getOrDefault(false)
        }
        val all = (if (DesktopCatalog.desktopInstalled(root)) files + DESKTOP_FILES else files) + optional
        val ledger = StageLedger(File(root, STAGED_LEDGER), apkIdentity(context))
        for ((asset, relative) in all) {
            val target = File(root, relative)
            if (ledger.current(relative, target)) continue
            val staged = File(target.parentFile, target.name + ".staged")
            var installed = false
            try {
                target.parentFile?.mkdirs()
                context.assets.open("linuxfs/$asset").use { input ->
                    staged.outputStream().use { output -> FileUtils.copy(input, output) }
                }
                installed = staged.setExecutable(true, false) && staged.renameTo(target)
            } catch (e: Exception) {
                Log.w(TAG, "could not stage $relative", e)
            } finally {
                if (!installed) staged.delete()
            }
            if (installed) ledger.staged(relative, target) else Log.e(TAG, "$relative NOT staged")
        }
        File(root, "usr/local/bin/bannerlator-steam-compat").delete()
        LegacyNames.migrateRootfs(root)
        ComponentsManager.migrateLaunchDir(context)
        EsyncPacks.stageBundled(context, root)
        val directAudio = arrayOf(
            "aarch64-unix/winedirectaudio.so",
            "aarch64-windows/winedirectaudio.drv",
            "i386-windows/winedirectaudio.drv",
            "version.txt",
        )
        val directAudioPack = AddonPack.DIRECTAUDIO.takeIf { it.installed(context) }?.dir(context)
        if (directAudioPack == null) Log.w(TAG, "DirectAudio pack is not installed; drivers not staged")
        if (directAudioPack != null) for (set in DIRECTAUDIO_SETS) for (relative in directAudio) {
            val key = "$DIRECTAUDIO_DIR/$set/lib/wine/$relative"
            val target = File(root, key)
            if (ledger.current(key, target)) continue
            val staged = File(target.parentFile, target.name + ".staged")
            var installed = false
            try {
                target.parentFile?.mkdirs()
                File(directAudioPack, "$set/$relative").inputStream().use { input ->
                    staged.outputStream().use { output -> FileUtils.copy(input, output) }
                }
                installed = staged.setReadable(true, false) && staged.renameTo(target)
            } catch (e: Exception) {
                Log.w(TAG, "could not stage DirectAudio $set/$relative", e)
            } finally {
                if (!installed) staged.delete()
            }
            if (installed) ledger.staged(key, target) else Log.e(TAG, "DirectAudio $set/$relative NOT staged")
        }
        ledger.save()
        for (old in arrayOf("aarch64-unix", "aarch64-windows", "i386-windows")) File(root, "$DIRECTAUDIO_DIR/lib/wine/$old").deleteRecursively()
        val preload = StringBuilder("/usr/local/lib/libblsession.so\n")
        if (!File(Environment.getExternalStorageDirectory(), NO_PAD_SWITCH).exists()) {
            preload.append("/usr/local/lib/libfakeinput.so\n")
        }
        if (ProotFastPath.enabled(context)) {
            val library = com.droiddeck.launcher.agent.AgentEnv.fastPathLibrary(context)
            preload.append(library + "\n")
            if (startsSession && library != ProotFastPath.LIBRARY) Log.i(TAG, "fast path preload: $library (debugging override)")
        }
        val etc = File(root, "etc").apply { mkdirs() }
        val staged = File(etc, "ld.so.preload.staged")
        if (!FileUtils.writeString(staged, preload.toString())
            || !staged.renameTo(File(etc, "ld.so.preload"))) {
            staged.delete()
            Log.e(TAG, "could not write ld.so.preload")
        }
        if (!FileUtils.writeString(File(etc, "steam-compatibility-label"), context.getString(R.string.steam_compat_label))) {
            Log.e(TAG, "could not write steam-compatibility-label")
        }
        writeCjkFontConfig(root, etc)
        writeDesktopStrings(context, File(etc, "droiddeck").apply { mkdirs() })
        val droiddeckEtc = File(etc, "droiddeck").apply { mkdirs() }
        val stagedSocket = File(droiddeckEtc, "bwrap-socket.staged")
        if (!FileUtils.writeString(stagedSocket, BwrapSpawner.socket(context) + "\n")
            || !stagedSocket.renameTo(File(droiddeckEtc, "bwrap-socket"))) {
            stagedSocket.delete()
            Log.e(TAG, "could not write bwrap-socket")
        }
        val stagedRoot = File(droiddeckEtc, "root-socket.staged")
        if (!FileUtils.writeString(stagedRoot, com.droiddeck.launcher.runtime.RootSpawner.socket(context) + "\n")
            || !stagedRoot.renameTo(File(droiddeckEtc, "root-socket"))) {
            stagedRoot.delete()
            Log.e(TAG, "could not write root-socket")
        }

        BootMovie.syncFromSteam(context)
        val startupMovie = BootMovie.prepare(context)
        val force = SessionPrefs.bootMovieApplyNext(context)
        val steamHomes = listOf(File(root, "root/.local/share/Steam")) +
            listOf(File(root, "root/.droiddeck-x64/.local/share/Steam")).filter { File(it, "config").isDirectory }
        var allDone = true
        var allInstalled = true
        var seen: String? = null
        val activeIsX64 = SessionPrefs.activeSteamClientArch(context) == "x86_64"
        for (home in steamHomes) {
            val movieDir = File(home, "config/uioverrides/movies")
            val config = File(home, "config/config.vdf")
            if (startupMovie == null) {
                // Nothing to play (a movie whose volume could not be set is never played at another volume), so no earlier copy plays either.
                for (name in listOf("bigpicture_startup.webm", "steam_os_startup.webm")) {
                    File(movieDir, name).delete()
                    File(movieDir, ".$name.source").delete()
                }
            }
            val bigPictureMovieInstalled = startupMovie != null && stageStartupMovie(startupMovie, movieDir, "bigpicture_startup.webm")
            if (startupMovie != null) stageStartupMovie(startupMovie, movieDir, "steam_os_startup.webm")
            val before = startupMoviePath(config)
            val done = if (bigPictureMovieInstalled) ensureStartupMovieDefault(config, force) else resetMissingStartupMovie(config, force)
            BootMovieReport.note(
                context,
                "stage session=$startsSession pending=$force choice=${SessionPrefs.bootMovie(context)} movie=${startupMovie?.path} " +
                    "staged=$bigPictureMovieInstalled done=$done steam=${home.path} config: $before -> ${startupMoviePath(config)}",
            )
            if (!done) allDone = false
            if (!bigPictureMovieInstalled) allInstalled = false
            if (done && home.path.contains(".droiddeck-x64") == activeIsX64) seen = if (bigPictureMovieInstalled) BOOT_MOVIE_PATH else ""
        }
        // A pick made in the app is done once a session started with every Steam client's config having it; guest commands run
        // before that, and a Steam that is already running writes its own choice back when it exits.
        if (force && allDone && allInstalled && startsSession) SessionPrefs.setBootMovieApplyNext(context, false)
        seen?.let { SessionPrefs.setBootMovieSteamSeen(context, it) }
    }

    /**
     * Points Steam's startup movie back at its own default when it names a movie that is gone: DroidDeck's staged copy that
     * could not be prepared, or (when a pick here is waiting) a movie removed from Steam's folder. True when Steam's config
     * no longer names a missing movie.
     */
    internal fun resetMissingStartupMovie(config: File, force: Boolean): Boolean {
        if (!config.isFile) return true
        val text = runCatching { config.readText() }.getOrElse {
            Log.w(TAG, "could not read Steam config to reset the startup movie", it)
            return false
        }
        val movie = activeStartupMovie(text) ?: return true
        val block = text.substring(movie.keyStart, movie.close + 1)
        val path = scalar(block, "LocalPath").orEmpty()
        if (path.isEmpty()) return true
        val missing = File(config.parentFile, "uioverrides/movies/" + path.removePrefix(BOOT_MOVIES_DIR))
        val gone = path.startsWith(BOOT_MOVIES_DIR) && !missing.isFile && (force || path == BOOT_MOVIE_PATH)
        if (!gone) return false
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val updated = setScalar(setScalar(block, "MovieID", "0", newline), "LocalPath", "", newline)
        val staged = File(config.parentFile, config.name + ".staged")
        return try {
            staged.writeText(text.replaceRange(movie.keyStart, movie.close + 1, updated))
            (staged.renameTo(config)).also { if (!it) staged.delete() }
        } catch (e: Exception) {
            staged.delete()
            Log.w(TAG, "could not reset Steam startup movie selection", e)
            false
        }
    }

    private fun apkIdentity(context: Context): String {
        val apk = File(context.applicationInfo.sourceDir)
        return "${apk.path} ${apk.length()} ${apk.lastModified()}"
    }

    private class StageLedger(private val file: File, private val identity: String) {
        private val before: Map<String, String> = FileUtils.readString(file)?.lines()
            ?.takeIf { it.firstOrNull() == identity }
            ?.drop(1)?.mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap()
            .orEmpty()
        private val after = LinkedHashMap<String, String>()

        fun current(relative: String, target: File): Boolean {
            val mark = mark(target) ?: return false
            if (before[relative] != mark) return false
            after[relative] = mark
            return true
        }

        fun staged(relative: String, target: File) {
            mark(target)?.let { after[relative] = it }
        }

        fun save() {
            if (after == before) return
            val text = (listOf(identity) + after.map { (relative, mark) -> "$relative\t$mark" }).joinToString("\n")
            val staged = File(file.parentFile, file.name + ".staged")
            if (!FileUtils.writeString(staged, text) || !staged.renameTo(file)) {
                staged.delete()
                file.delete()
            }
        }

        private fun mark(target: File): String? =
            if (target.isFile) "${target.length()} ${target.lastModified()} ${target.canExecute()}" else null
    }

    private fun stageStartupMovie(source: File, directory: File, name: String): Boolean {
        val movie = File(directory, name)
        val stamp = File(directory, ".$name.source")
        val identity = "${source.absolutePath}\n${source.length()}\n${source.lastModified()}"
        if (movie.length() == source.length() && runCatching { stamp.readText() }.getOrNull() == identity) return true
        val staged = File(directory, "$name.staged")
        var installed = false
        try {
            directory.mkdirs()
            source.inputStream().use { input ->
                staged.outputStream().use { output -> FileUtils.copy(input, output) }
            }
            installed = staged.setReadable(true, false) && staged.renameTo(movie)
            if (installed) {
                FileUtils.writeString(stamp, identity)
                // Steam's picker draws its preview from this picture and does not redraw it when the movie changes.
                File(directory, name.removeSuffix(".webm") + ".jpg").delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not stage Steam startup movie $name", e)
        } finally {
            if (!installed) staged.delete()
        }
        if (!installed) Log.e(TAG, "Steam startup movie $name NOT staged")
        return installed
    }

    private data class VdfBlock(val keyStart: Int, val open: Int, val close: Int)

    internal const val BOOT_MOVIES_DIR = "/uioverrides/movies/"
    internal const val BOOT_MOVIE_PATH = "/uioverrides/movies/bigpicture_startup.webm"

    /**
     * Steam's startup movie setting is the `StartupMovie` section inside its `Customization` section; the one under the Steam
     * settings is where older clients kept it, and DroidDeck still keeps it in step.
     */
    private fun customizationMovie(text: String): VdfBlock? {
        var from = 0
        while (true) {
            val customization = findObject(text, "Customization", from, text.length) ?: return null
            findObject(text, "StartupMovie", customization.open + 1, customization.close)?.let { return it }
            from = customization.close + 1
        }
    }

    /** The older `StartupMovie` section: the first one that is not inside `Customization`. */
    private fun olderMovie(text: String): VdfBlock? {
        val inside = customizationMovie(text)
        var from = 0
        while (true) {
            val movie = findObject(text, "StartupMovie", from, text.length) ?: return null
            if (inside == null || movie.keyStart != inside.keyStart) return movie
            from = movie.close + 1
        }
    }

    /** The section Steam reads: the one under `Customization` when there is one. */
    private fun activeStartupMovie(text: String): VdfBlock? = customizationMovie(text) ?: olderMovie(text)

    /** The movie Steam's config selects, as a path like `/uioverrides/movies/x.webm`; null when none is named. */
    internal fun startupMoviePath(config: File): String? = startupMovieLocalPath(config)?.takeIf { it.isNotEmpty() }

    internal fun startupMovieLocalPath(config: File): String? {
        val text = runCatching { config.readText() }.getOrNull() ?: return null
        val movie = activeStartupMovie(text) ?: return null
        return scalar(text.substring(movie.keyStart, movie.close + 1), "LocalPath")
    }

    /** Both `StartupMovie` sections of Steam's config as written there, for the report; null when there is none. */
    internal fun startupMovieBlock(config: File): String? {
        val text = runCatching { config.readText() }.getOrNull() ?: return null
        val parts = listOfNotNull(
            customizationMovie(text)?.let { "under Customization:\n" + text.substring(it.keyStart, it.close + 1) },
            olderMovie(text)?.let { "older place:\n" + text.substring(it.keyStart, it.close + 1) },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** The `Customization` section of Steam's config with the lines before it, for the report; null when there is none. */
    internal fun customizationBlock(config: File): String? {
        val text = runCatching { config.readText() }.getOrNull() ?: return null
        val customization = findObject(text, "Customization", 0, text.length) ?: return null
        val before = text.substring(0, customization.keyStart).lines().takeLast(4).joinToString("\n")
        return (before + "\n" + text.substring(customization.keyStart, customization.close + 1)).take(4000)
    }

    private fun pointAt(text: String, movie: VdfBlock, path: String, newline: String): String {
        val block = text.substring(movie.keyStart, movie.close + 1)
        return text.replaceRange(movie.keyStart, movie.close + 1, setScalar(setScalar(block, "MovieID", "0", newline), "LocalPath", path, newline))
    }

    /** True when Steam's config selects DroidDeck's staged movie once this returns. */
    internal fun ensureStartupMovieDefault(config: File, force: Boolean = false): Boolean {
        val path = BOOT_MOVIE_PATH
        val legacyPath = "/uioverrides/movies/droiddeck-startup.webm"
        var text = if (config.isFile) runCatching { config.readText() }.getOrElse {
            Log.w(TAG, "could not read Steam config for startup movie", it)
            return false
        } else ""
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val custom = customizationMovie(text)
        val active = custom ?: olderMovie(text)
        if (active != null) {
            val block = text.substring(active.keyStart, active.close + 1)
            val selectedId = scalar(block, "MovieID")
            val selectedPath = scalar(block, "LocalPath")
            if (selectedPath == path && custom != null) {
                Log.i(TAG, "Steam startup movie is set to DroidDeck")
                return true
            }
            val legacyDefault = selectedId == "0" && selectedPath == legacyPath
            val hasExplicitSelection =
                (!selectedId.isNullOrEmpty() && selectedId != "0") || !selectedPath.isNullOrEmpty()
            if (!force && !legacyDefault && hasExplicitSelection && selectedPath != path) {
                Log.i(TAG, "Steam startup movie selection preserved")
                return false
            }
        }
        // Later sections first, so the positions of earlier ones stay valid.
        val targets = listOfNotNull(customizationMovie(text), olderMovie(text)).sortedByDescending { it.keyStart }
        for (target in targets) text = pointAt(text, target, path, newline)
        if (olderMovie(text) == null) {
            val steam = findSteamBlock(text)
            if (steam == null) {
                if (text.isNotBlank() && customizationMovie(text) == null) {
                    Log.w(TAG, "Steam config has no Steam settings block; startup movie default not set")
                    return false
                }
                if (text.isBlank()) {
                    text = "\"InstallConfigStore\"${newline}{${newline}\t\"Software\"${newline}\t{${newline}\t\t\"Valve\"${newline}\t\t{${newline}\t\t\t\"Steam\"${newline}\t\t\t{${newline}"
                    val section = startupMovieSection("\t\t\t\t", path, newline)
                    text += section + newline + "\t\t\t}" + newline + "\t\t}" + newline + "\t}" + newline + "}"
                }
            } else {
                val lineStart = text.lastIndexOf('\n', steam.close).let { if (it < 0) 0 else it + 1 }
                val indent = text.substring(lineStart, steam.close).takeWhile { it == '\t' || it == ' ' }
                val section = startupMovieSection(indent + "\t", path, newline)
                text = text.substring(0, lineStart) + section + newline + indent + text.substring(lineStart)
            }
        }
        if (customizationMovie(text) == null) text = withCustomization(text, path, newline)
        val staged = File(config.parentFile, config.name + ".staged")
        return try {
            config.parentFile?.mkdirs()
            staged.writeText(text)
            if (!staged.renameTo(config)) {
                staged.delete()
                Log.e(TAG, "could not write Steam startup movie selection")
                false
            } else {
                Log.i(TAG, "Steam startup movie default selected")
                true
            }
        } catch (e: Exception) {
            staged.delete()
            Log.w(TAG, "could not write Steam startup movie selection", e)
            false
        }
    }

    /** Adds the `Customization` section Steam reads, next to the `Software` section, when the config has none. */
    private fun withCustomization(text: String, path: String, newline: String): String {
        val root = findObject(text, "InstallConfigStore", 0, text.length) ?: return text
        val lineStart = text.lastIndexOf('\n', root.close).let { if (it < 0) 0 else it + 1 }
        val indent = text.substring(lineStart, root.close).takeWhile { it == '\t' || it == ' ' }
        val section = listOf(indent + "\t\"Customization\"", indent + "\t{", startupMovieSection(indent + "\t\t", path, newline), indent + "\t}").joinToString(newline)
        return text.substring(0, lineStart) + section + newline + text.substring(lineStart)
    }

    private fun findSteamBlock(text: String): VdfBlock? {
        val install = findObject(text, "InstallConfigStore", 0, text.length) ?: return null
        val software = findObject(text, "Software", install.open + 1, install.close) ?: return null
        val valve = findObject(text, "Valve", software.open + 1, software.close) ?: return null
        return findObject(text, "Steam", valve.open + 1, valve.close)
    }

    private fun findObject(text: String, key: String, start: Int, end: Int): VdfBlock? {
        val pattern = Regex("(?m)^[\\t ]*\"${Regex.escape(key)}\"[\\t ]*(?:\\r?\\n[\\t ]*)?\\{")
        val match = pattern.find(text, start)?.takeIf { it.range.first < end && it.range.last < end } ?: return null
        val open = text.indexOf('{', match.range.first).takeIf { it < end } ?: return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in open until end) {
            val char = text[index]
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '\"') quoted = false
            } else {
                when (char) {
                    '\"' -> quoted = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return VdfBlock(match.range.first, open, index)
                    }
                }
            }
        }
        return null
    }

    private fun scalar(block: String, key: String): String? {
        val pattern = Regex("(?m)^[\\t ]*\"${Regex.escape(key)}\"[\\t ]+\"([^\"\\r\\n]*)\"")
        return pattern.find(block)?.groupValues?.get(1)
    }

    private fun setScalar(block: String, key: String, value: String, newline: String): String {
        val pattern = Regex("(?m)^([\\t ]*\"${Regex.escape(key)}\"[\\t ]+)\"[^\"\\r\\n]*\"([\\t ]*)$")
        val match = pattern.find(block)
        if (match != null) return block.replaceRange(match.range, match.groupValues[1] + "\"" + value + "\"" + match.groupValues[2])
        val close = block.lastIndexOf('}')
        if (close < 0) return block
        val lineStart = block.lastIndexOf('\n', close).let { if (it < 0) 0 else it + 1 }
        val indent = block.substring(lineStart, close).takeWhile { it == '\t' || it == ' ' } + "\t"
        val entry = indent + "\"" + key + "\"\t\t\"" + value + "\"" + newline
        return block.substring(0, lineStart) + entry + block.substring(lineStart)
    }

    private fun startupMovieSection(indent: String, path: String, newline: String): String = listOf(
        indent + "\"StartupMovie\"",
        indent + "{",
        indent + "\t\"MovieID\"\t\t\"0\"",
        indent + "\t\"LocalPath\"\t\t\"" + path + "\"",
        indent + "}",
    ).joinToString(newline)

    fun logDirectory(context: Context): File = LinuxRuntime.logDir(context).apply { mkdirs() }

    /**
     * Registers the Android system fonts (which include CJK faces such as Noto Sans CJK /
     * MiSans) with the container's fontconfig, so the Steam Deck UI (CEF/webkit renderer)
     * resolves Chinese/Japanese glyphs instead of showing tofu / mojibake.
     * The fonts themselves are bind-mounted by LinuxRuntime.bindSystemFonts() into
     * /usr/local/share/fonts/android; here we only need to point fontconfig at them.
     */
    private val DESKTOP_STRINGS = listOf(
        "desktop_restore_title" to R.string.desktop_restore_title,
        "desktop_restore_body" to R.string.desktop_restore_body,
        "desktop_restore_done_title" to R.string.desktop_restore_done_title,
        "desktop_restore_done_body" to R.string.desktop_restore_done_body,
        "desktop_restore_failed_title" to R.string.desktop_restore_failed_title,
        "desktop_restore_failed_body" to R.string.desktop_restore_failed_body,
    )

    private fun writeDesktopStrings(context: Context, dir: File) {
        val text = DESKTOP_STRINGS.joinToString("") { (key, id) ->
            key + "=" + context.getString(id).replace("\\", "\\\\").replace("%", "%%").replace("\n", "\\n") + "\n"
        }
        val target = File(dir, "strings")
        if (FileUtils.readString(target) == text) return
        val staged = File(dir, "strings.staged")
        if (!FileUtils.writeString(staged, text) || !staged.renameTo(target)) {
            staged.delete()
            Log.e(TAG, "could not write desktop strings")
        }
    }

    private fun writeCjkFontConfig(root: File, etc: File) {
        try {
            val confDir = File(etc, "fonts/conf.d").apply { mkdirs() }
            val conf = File(confDir, "99-droiddeck-cjk.conf")
            val xml = """<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig>
  <dir>/usr/local/share/fonts/android</dir>
  <dir>/system/fonts</dir>
  <match target="pattern">
    <test name="lang" compare="contains"><string>ja</string></test>
    <test name="family" qual="any"><string>sans-serif</string></test>
    <edit name="family" mode="prepend" binding="strong"><string>Noto Sans CJK JP</string></edit>
  </match>
  <match target="pattern">
    <test name="lang" compare="contains"><string>ja</string></test>
    <test name="family" qual="any"><string>serif</string></test>
    <edit name="family" mode="prepend" binding="strong"><string>Noto Serif CJK JP</string></edit>
  </match>
  <match target="pattern">
    <test name="lang" compare="eq"><string>zh</string></test>
    <test name="family" qual="any"><string>sans-serif</string></test>
    <edit name="family" mode="prepend" binding="strong"><string>Noto Sans CJK SC</string></edit>
  </match>
  <match target="pattern">
    <test name="lang" compare="eq"><string>zh</string></test>
    <test name="family" qual="any"><string>serif</string></test>
    <edit name="family" mode="prepend" binding="strong"><string>Noto Serif CJK SC</string></edit>
  </match>
  <match target="pattern">
    <test name="lang" compare="eq"><string>zh-cn</string></test>
    <test name="family" qual="any"><string>sans-serif</string></test>
    <edit name="family" mode="prepend" binding="strong"><string>Noto Sans CJK SC</string></edit>
  </match>
  <match target="pattern">
    <test name="lang" compare="eq"><string>zh-cn</string></test>
    <test name="family" qual="any"><string>serif</string></test>
    <edit name="family" mode="prepend" binding="strong"><string>Noto Serif CJK SC</string></edit>
  </match>
  <match target="pattern">
    <test name="lang" compare="eq"><string>zh-tw</string></test>
    <test name="family" qual="any"><string>sans-serif</string></test>
    <edit name="family" mode="prepend" binding="strong"><string>Noto Sans CJK TC</string></edit>
  </match>
  <match target="pattern">
    <test name="lang" compare="eq"><string>zh-tw</string></test>
    <test name="family" qual="any"><string>serif</string></test>
    <edit name="family" mode="prepend" binding="strong"><string>Noto Serif CJK TC</string></edit>
  </match>
  <match target="pattern">
    <test name="lang" compare="eq"><string>zh-hk</string></test>
    <test name="family" qual="any"><string>sans-serif</string></test>
    <edit name="family" mode="prepend" binding="strong"><string>Noto Sans CJK TC</string></edit>
  </match>
  <match target="pattern">
    <test name="lang" compare="eq"><string>zh-hk</string></test>
    <test name="family" qual="any"><string>serif</string></test>
    <edit name="family" mode="prepend" binding="strong"><string>Noto Serif CJK TC</string></edit>
  </match>
  <match target="pattern">
    <test name="lang" compare="contains"><string>ko</string></test>
    <test name="family" qual="any"><string>sans-serif</string></test>
    <edit name="family" mode="prepend" binding="strong"><string>Noto Sans CJK KR</string></edit>
  </match>
  <match target="pattern">
    <test name="lang" compare="contains"><string>ko</string></test>
    <test name="family" qual="any"><string>serif</string></test>
    <edit name="family" mode="prepend" binding="strong"><string>Noto Serif CJK KR</string></edit>
  </match>
</fontconfig>
"""
            if (!conf.isFile || FileUtils.readString(conf) != xml) {
                val staged = File(confDir, conf.name + ".staged")
                if (FileUtils.writeString(staged, xml) && staged.renameTo(conf)) {
                    Log.i(TAG, "Wrote CJK fontconfig 99-droiddeck-cjk.conf")
                } else {
                    staged.delete()
                    Log.e(TAG, "Could not write CJK fontconfig")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "CJK fontconfig setup failed", e)
        }
    }
}
