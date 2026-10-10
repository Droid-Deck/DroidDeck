package com.droiddeck.launcher.session

import android.content.Context
import android.os.Environment
import android.util.Log
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.runtime.DesktopCatalog
import com.droiddeck.launcher.runtime.LegacyNames
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.ProotFastPath
import java.io.File

/** Everything the session needs written into the runtime before it starts. */
object SessionFiles {
    private const val TAG = "SessionFiles"
    private const val NO_PAD_SWITCH = "Download/droiddeck-no-pad"
    /** Where the DirectAudio driver lives inside the runtime; the wrappers get it as BL_DIRECTAUDIO. */
    const val DIRECTAUDIO_DIR = "usr/local/lib/directaudio"
    /** The driver sets staged under it: classic Wine 11 (Valve, GE) and the system-thread interface (Proton-CachyOS). */
    val DIRECTAUDIO_SETS = arrayOf("linux-wine11", "linux-wine11-systhread")

    /**
     * The libraries and scripts the session runs, refreshed from the apk at every launch.
     *
     * The runtime image carries its own copies, but a runtime installed months ago carries the
     * copies of that day and a device has no way to replace them from outside the app. Staging
     * them here is how a fix inside the session shim, the controller reader or one of the scripts
     * reaches an already-installed runtime without a ~790 MB re-download. Each lands through a
     * rename, so a session that still has one mapped keeps the file it opened.
     */
    fun stage(context: Context, root: File) {
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
            "usr/local/bin/droiddeck-steam-desktop-ui" to "usr/local/bin/droiddeck-steam-desktop-ui",
            "usr/local/bin/droiddeck-steam-library" to "usr/local/bin/droiddeck-steam-library",
            "usr/local/bin/droiddeck-seed-redists" to "usr/local/bin/droiddeck-seed-redists",
            "usr/local/bin/droiddeck-proton-extra" to "usr/local/bin/droiddeck-proton-extra",
            "usr/local/bin/droiddeck-netmanager" to "usr/local/bin/droiddeck-netmanager",
            "usr/local/bin/droiddeck-login1" to "usr/local/bin/droiddeck-login1",
            "usr/local/bin/droiddeck-steam-launch" to "usr/local/bin/droiddeck-steam-launch",
            "usr/local/bin/droiddeck-desktop-games" to "usr/local/bin/droiddeck-desktop-games",
            "usr/local/bin/droiddeck-desktop-bookmarks" to "usr/local/bin/droiddeck-desktop-bookmarks",
            "usr/local/bin/droiddeck-steam-shim" to "usr/local/bin/droiddeck-steam-shim",
            "usr/local/bin/droiddeck-steam-shortcuts" to "usr/local/bin/droiddeck-steam-shortcuts",
            // Asks the app for an Epic game's sign-in code at launch, from the compat tool (StoreLaunchRequests).
            "usr/local/bin/droiddeck-store-launch" to "usr/local/bin/droiddeck-store-launch",
            // A game's web links to Android's browser (Wine's winebrowser starts it).
            "usr/local/bin/droiddeck-open-url" to "usr/local/bin/droiddeck-open-url",
            "usr/local/bin/droiddeck-steam-games" to "usr/local/bin/droiddeck-steam-games",
            "usr/local/bin/droiddeck-pad-defaults" to "usr/local/bin/droiddeck-pad-defaults",
            // Flatpak: the bwrap stand-in, the store's helper and setup, and the front end's launcher.
            "usr/local/bin/droiddeck-bwrap" to "usr/local/bin/droiddeck-bwrap",
            "usr/local/bin/droiddeck-flatpak" to "usr/local/bin/droiddeck-flatpak",
            "usr/local/bin/droiddeck-flatpak-setup" to "usr/local/bin/droiddeck-flatpak-setup",
            "usr/local/bin/droiddeck-flatpak-run" to "usr/local/bin/droiddeck-flatpak-run",
            // The user's own AppImages, extracted at import (AppImageManager).
            "usr/local/bin/droiddeck-appimage-run" to "usr/local/bin/droiddeck-appimage-run",
            "usr/local/bin/droiddeck-script-run" to "usr/local/bin/droiddeck-script-run",
            // The SteamOS helpers the client calls in Deck mode: the two Armada found it needs, plus
            // the three under /usr/bin, all no-ops that answer "nothing to do" (see each file).
            "usr/bin/steamos-update" to "usr/bin/steamos-update",
            "usr/bin/steamos-select-branch" to "usr/bin/steamos-select-branch",
            // Big Picture's "Switch to Desktop": asks the app for the desktop with Steam in it.
            "usr/bin/steamos-session-select" to "usr/bin/steamos-session-select",
            "usr/bin/jupiter-biosupdate" to "usr/bin/jupiter-biosupdate",
            "usr/bin/steamos-polkit-helpers/steamos-priv-write" to "usr/bin/steamos-polkit-helpers/steamos-priv-write",
            "usr/bin/steamos-polkit-helpers/steamos-set-timezone" to "usr/bin/steamos-polkit-helpers/steamos-set-timezone",
            // On device the client called these four by their polkit-helpers path, not /usr/bin: the
            // "Update Error" dialog was steamos-update missing there.
            "usr/bin/steamos-polkit-helpers/steamos-update" to "usr/bin/steamos-polkit-helpers/steamos-update",
            "usr/bin/steamos-polkit-helpers/steamos-select-branch" to "usr/bin/steamos-polkit-helpers/steamos-select-branch",
            "usr/bin/steamos-polkit-helpers/jupiter-biosupdate" to "usr/bin/steamos-polkit-helpers/jupiter-biosupdate",
            "usr/bin/steamos-polkit-helpers/jupiter-dock-updater" to "usr/bin/steamos-polkit-helpers/jupiter-dock-updater",
        )
        // The desktop's launchers, only where the desktop package is installed:
        // staging them into a runtime without it would make the desktop look present when it is not.
        val desktop = arrayOf(
            "usr/local/bin/droiddeck-desktop" to "usr/local/bin/droiddeck-desktop",
            // Games and emulators from the menu, full screen in a gamescope of their own.
            "usr/local/bin/droiddeck-gpu" to "usr/local/bin/droiddeck-gpu",
            "usr/local/bin/droiddeck-desktop-gpu" to "usr/local/bin/droiddeck-desktop-gpu",
            "usr/local/bin/droiddeck-desktop-window-rules" to "usr/local/bin/droiddeck-desktop-window-rules",
            // KWin at the app's surface size, for Plasma's session (it starts KWin through this name).
            "usr/local/bin/kwin_wayland_wrapper" to "usr/local/bin/kwin_wayland_wrapper",
            "usr/lib/firefox/defaults/pref/droiddeck.js" to "usr/lib/firefox/defaults/pref/droiddeck.js",
            "etc/xdg/autostart/droiddeck-clipboard.desktop" to "etc/xdg/autostart/droiddeck-clipboard.desktop",
        )
        // The patched gamescope (tools/gamescope): the runtime's own version rebuilt with the ARM64
        // client fixes, over /usr/local/bin so it comes first in the session's PATH. Only when the
        // apk carries it - a build without the asset leaves the runtime's copy alone.
        // Valve's mangoapp (tools/mangoapp) - Deck mode's performance overlay - with the five
        // libraries the runtime lacks beside it, and the wrapper on PATH that points it at them.
        val mangoapp = listOf(
            "usr/local/bin/mangoapp",
            "usr/local/lib/mangoapp/mangoapp",
            "usr/local/lib/mangoapp/libfmt.so.10",
            "usr/local/lib/mangoapp/libspdlog.so.1.13",
            "usr/local/lib/mangoapp/libglfw.so.3",
            "usr/local/lib/mangoapp/libtraceevent.so.1",
            "usr/local/lib/mangoapp/libtracefs.so.1",
        ).map { it to it }
        // What the Windows components installer reads .msi packages with (tools/msitools).
        val msitools = listOf("msiinfo", "cabextract", "7z", "7z.so", "libmsi-1.0.so.0", "libgsf-1.so.114", "libgcab-1.0.so.0")
            .map { "usr/local/lib/droiddeck-msitools/$it" } +
            listOf("NOTICE", "GPL-2", "GPL-3", "LGPL-2.1", "7zip-License", "7zip-unRarLicense").map { "usr/local/share/licenses/droiddeck-msitools/$it" }
        val fexPreloads = listOf("x86_64", "i386").flatMap { arch ->
            listOf("libblsession.so", "libfakeinput.so").map { "$arch/$it" to "usr/local/lib/droiddeck-fex/$arch/$it" }
        } + listOf("libfaultreport.so", "libthunkaudit.so", "libvulkan-thunk.so").map { "x86_64/$it" to "usr/local/lib/droiddeck-fex/x86_64/$it" }
        val optional = (arrayOf(
            "usr/local/bin/gamescope" to "usr/local/bin/gamescope",
            "usr/local/lib/droiddeck/uruntime" to "usr/local/lib/droiddeck/uruntime",
            "usr/local/share/licenses/uruntime/LICENSE" to "usr/local/share/licenses/uruntime/LICENSE",
        ) + mangoapp + msitools.map { it to it } + fexPreloads).filter { (asset, _) ->
            val dir = asset.substringBeforeLast('/')
            runCatching { context.assets.list("linuxfs/$dir")?.contains(asset.substringAfterLast('/')) == true }.getOrDefault(false)
        }
        val all = (if (DesktopCatalog.desktopInstalled(root)) files + desktop else files) + optional
        for ((asset, relative) in all) {
            val target = File(root, relative)
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
            if (!installed) Log.e(TAG, "$relative NOT staged")
        }
        File(root, "usr/local/bin/bannerlator-steam-compat").delete()
        LegacyNames.migrateRootfs(root)
        ComponentsManager.migrateLaunchDir(context)
        EsyncPacks.stageBundled(context, root)
        // The DirectAudio driver for games under Proton: the glibc build of winedirectaudio, which
        // the Proton wrappers add to WINEDLLPATH when the session asks for it (BL_DIRECTAUDIO). Two
        // sets, one per Wine audio interface - the wrapper picks by what the Proton's own winepulse.so
        // imports (see steam-compatibility), because both Wines call themselves 11.0 and the wrong set
        // plays silence. Staged like the scripts, so a driver fix reaches an installed runtime without
        // re-hosting. The sets come from the pinned DirectAudio release (tools/directaudio/release.env).
        val directAudio = arrayOf(
            "aarch64-unix/winedirectaudio.so",
            "aarch64-windows/winedirectaudio.drv",
            "i386-windows/winedirectaudio.drv",
            "version.txt",
        )
        for (set in DIRECTAUDIO_SETS) for (relative in directAudio) {
            val target = File(root, "$DIRECTAUDIO_DIR/$set/lib/wine/$relative")
            val staged = File(target.parentFile, target.name + ".staged")
            var installed = false
            try {
                target.parentFile?.mkdirs()
                context.assets.open("directaudio/$set/$relative").use { input ->
                    staged.outputStream().use { output -> FileUtils.copy(input, output) }
                }
                installed = staged.setReadable(true, false) && staged.renameTo(target)
            } catch (e: Exception) {
                Log.w(TAG, "could not stage DirectAudio $set/$relative", e)
            } finally {
                if (!installed) staged.delete()
            }
            if (!installed) Log.e(TAG, "DirectAudio $set/$relative NOT staged")
        }
        // A runtime from before the sets had names kept the driver at the directory's root; it would
        // never be picked again, so it goes.
        for (old in arrayOf("aarch64-unix", "aarch64-windows", "i386-windows")) File(root, "$DIRECTAUDIO_DIR/lib/wine/$old").deleteRecursively()
        // What every process in the session preloads. LD_PRELOAD in the environment would not
        // survive: the Steam client rebuilds it for each process it starts and appends its own
        // overlay entry without a separator, which silently drops whatever was there.
        val preload = StringBuilder("/usr/local/lib/libblsession.so\n")
        if (!File(Environment.getExternalStorageDirectory(), NO_PAD_SWITCH).exists()) {
            preload.append("/usr/local/lib/libfakeinput.so\n")
        }
        // Last, so the others' opens and stats reach it through RTLD_NEXT. It stays idle in any
        // process its proot did not describe to it (ProotFastPath).
        if (ProotFastPath.enabled(context)) preload.append(ProotFastPath.LIBRARY + "\n")
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

        val startupMovieDir = File(root, "root/.local/share/Steam/config/uioverrides/movies")
        // Steam looks up these conventional names in its user override directory. Keep both
        // variants populated because the startup movie name differs across Steam clients.
        val bigPictureMovieInstalled = stageStartupMovie(
            context,
            startupMovieDir,
            "bigpicture_startup.webm",
        )
        stageStartupMovie(context, startupMovieDir, "steam_os_startup.webm")
        if (bigPictureMovieInstalled) {
            ensureStartupMovieDefault(File(root, "root/.local/share/Steam/config/config.vdf"))
        }
    }

    private fun stageStartupMovie(context: Context, directory: File, name: String): Boolean {
        val movie = File(directory, name)
        val staged = File(directory, "$name.staged")
        var installed = false
        try {
            directory.mkdirs()
            context.assets.open("steam-startup/droiddeck-startup.webm").use { input ->
                staged.outputStream().use { output -> FileUtils.copy(input, output) }
            }
            installed = staged.setReadable(true, false) && staged.renameTo(movie)
        } catch (e: Exception) {
            Log.w(TAG, "could not stage Steam startup movie $name", e)
        } finally {
            if (!installed) staged.delete()
        }
        if (!installed) Log.e(TAG, "Steam startup movie $name NOT staged")
        return installed
    }

    private data class VdfBlock(val keyStart: Int, val open: Int, val close: Int)

    private fun ensureStartupMovieDefault(config: File) {
        val path = "/uioverrides/movies/bigpicture_startup.webm"
        val legacyPath = "/uioverrides/movies/droiddeck-startup.webm"
        var text = if (config.isFile) runCatching { config.readText() }.getOrElse {
            Log.w(TAG, "could not read Steam config for startup movie", it)
            return
        } else ""
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val movie = findObject(text, "StartupMovie", 0, text.length)
        if (movie != null) {
            val block = text.substring(movie.keyStart, movie.close + 1)
            val selectedId = scalar(block, "MovieID")
            val selectedPath = scalar(block, "LocalPath")
            if (selectedPath == path) {
                Log.i(TAG, "Steam startup movie is set to DroidDeck")
                return
            }
            val legacyDefault = selectedId == "0" && selectedPath == legacyPath
            val hasExplicitSelection =
                (!selectedId.isNullOrEmpty() && selectedId != "0") || !selectedPath.isNullOrEmpty()
            if (!legacyDefault && hasExplicitSelection) {
                Log.i(TAG, "Steam startup movie selection preserved")
                return
            }
            val updated = setScalar(block, "MovieID", "0", newline)
                .let { setScalar(it, "LocalPath", path, newline) }
            text = text.replaceRange(movie.keyStart, movie.close + 1, updated)
        } else {
            val steam = findSteamBlock(text)
            if (steam == null) {
                if (text.isNotBlank()) {
                    Log.w(TAG, "Steam config has no Steam settings block; startup movie default not set")
                    return
                }
                text = "\"InstallConfigStore\"${newline}{${newline}\t\"Software\"${newline}\t{${newline}\t\t\"Valve\"${newline}\t\t{${newline}\t\t\t\"Steam\"${newline}\t\t\t{${newline}"
                val section = startupMovieSection("\t\t\t\t", path, newline)
                text += section + newline + "\t\t\t}" + newline + "\t\t}" + newline + "\t}" + newline + "}"
            } else {
                val lineStart = text.lastIndexOf('\n', steam.close).let { if (it < 0) 0 else it + 1 }
                val indent = text.substring(lineStart, steam.close).takeWhile { it == '\t' || it == ' ' }
                val section = startupMovieSection(indent + "\t", path, newline)
                text = text.substring(0, lineStart) + section + newline + indent + text.substring(lineStart)
            }
        }
        val staged = File(config.parentFile, config.name + ".staged")
        try {
            config.parentFile?.mkdirs()
            staged.writeText(text)
            if (!staged.renameTo(config)) {
                staged.delete()
                Log.e(TAG, "could not write Steam startup movie selection")
            } else {
                Log.i(TAG, "Steam startup movie default selected")
            }
        } catch (e: Exception) {
            staged.delete()
            Log.w(TAG, "could not write Steam startup movie selection", e)
        }
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

    /**
     * Where the session writes its log: app-private `files/logs`, bound into the session at the same
     * path, so the session script's own redirection always opens. A folder leaves the device only
     * through Share logs, which scrubs every file again on the way into the zip.
     */
    fun logDirectory(context: Context): File = LinuxRuntime.logDir(context).apply { mkdirs() }
}
