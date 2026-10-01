package com.droiddeck.launcher.frontend

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.GameStorage
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SecondaryLibrary
import java.io.File

/**
 * What the front end lists: the Steam client's installed games (from its own appmanifests, both
 * libraries) and each emulator's games (files in the ROMs folder, by extension, with a system
 * folder as a hint). Read on a worker thread; nothing here is cached beyond one screen refresh.
 */
object Library {
    /** [gameId] is what steam://rungameid/ takes: the appid for a Steam title, the shortcut id for an added game. */
    /**
     * [art] is the portrait capsule; [hero] the wide banner Steam shows above a game's page, when
     * the client has cached one. [lastPlayed] is Steam's own "LastPlayed" (unix seconds), 0 = never.
     */
    class SteamGame(
        val appId: Int, val name: String, val art: File?, val library: String, val gameId: Long = appId.toLong(),
        val hero: File? = null, val lastPlayed: Long = 0L,
        val gameFiles: File? = null, val protonPrefix: File? = null,
    )
    /** The [SteamGame.library] of a game added to the library rather than installed by Steam. */
    const val ADDED = "added"

    class Rom(val name: String, val hostPath: File, val guestPath: String, val emulatorId: String, val art: File? = null)
    class Emulator(val id: String, val name: String, val system: String, val program: String, val installed: Boolean, val games: List<Rom>) {
        /** The emulator's own icon, bundled (the runtime keeps them as theme SVGs the app cannot draw). */
        val iconRes: Int get() = when (id) {
            "rpcs3" -> R.drawable.emu_rpcs3; "armsx2" -> R.drawable.emu_pcsx2; "dolphin" -> R.drawable.emu_dolphin
            "duckstation" -> R.drawable.emu_duckstation; "melonds" -> R.drawable.emu_melonds; "cemu" -> R.drawable.emu_cemu
            "ppsspp" -> R.drawable.emu_ppsspp; else -> R.drawable.emu_retroarch
        }
    }

    /** The client's own tools and runtimes live in steamapps beside the games; they are not titles. */
    private val NOT_GAMES = setOf(
        858280,  // Proton 3.7
        961940,  // Proton 3.16
        993090,  // Lossless Scaling
        1054830, // Proton 4.2
        1070560, // Steam Linux Runtime 1.0
        1113280, // Proton 4.11
        1245040, // Proton 5.0
        1391110, // Steam Linux Runtime 2.0
        1420170, // Proton 5.13
        1493710, // Proton Experimental
        1580130, // Proton 6.3
        1628350, // Steam Linux Runtime 3.0
        1887720, // Proton 7
        2180100, // Proton Hotfix
        228980,  // Steamworks Common Redistributables
        2348590, // Proton 8
        2805730, // Proton 9
        3029110, // Lepton
        3127680, // FEX
        3658110, // Proton 10
        4183110, // Steam Linux Runtime 4.0
        4185400, // Steam Linux Runtime 4.0 for ARM64
        4427310, // Proton Experimental for ARM64
        4628710, // Proton 11 / Proton Next
        4628740, // Proton 11 for ARM64
        4690330, // Legacy Steam Runtime
    )
    private val STEAM_CAPSULES = listOf("library_capsule.jpg", "library_600x900.jpg")
    private val STEAM_HEROES = listOf("library_hero.jpg")
    private val NAME = Regex("^\\s*\"name\"\\s*\"([^\"]*)\"", RegexOption.MULTILINE)
    private val STATE = Regex("^\\s*\"StateFlags\"\\s*\"(\\d+)\"", RegexOption.MULTILINE)
    private val LAST_PLAYED = Regex("^\\s*\"LastPlayed\"\\s*\"(\\d+)\"", RegexOption.MULTILINE)
    private val INSTALL_DIR = Regex("^\\s*\"installdir\"\\s*\"([^\"]*)\"", RegexOption.MULTILINE)

    /** Steam's library roots visible to this launcher: its private default plus the selected library. */
    private fun steamLibraries(context: Context): List<Pair<File, String>> {
        val root = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam")
        return listOfNotNull(
            root to "internal",
            GameStorage.effective(context)?.let { File(it.path) to it.label },
        )
    }

    /** Proton keeps each game's prefix below compatdata/<appid>/pfx in a Steam library. */
    fun protonPrefix(context: Context, appId: Long, preferredLibrary: File? = null): File? {
        val ids = listOf(appId.toString(), java.lang.Integer.toString(appId.toInt())).distinct()
        val secondary = GameStorage.effective(context)?.let { File(it.path) }
        fun prefixRoot(root: File): File = if (secondary != null && root.canonicalPath == secondary.canonicalPath)
            SecondaryLibrary.privateRoot(context.filesDir, root).takeIf { File(it, "steamapps/compatdata").isDirectory } ?: root
            else root
        val roots = (listOfNotNull(preferredLibrary) + steamLibraries(context).map { it.first }).map(::prefixRoot)
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        return roots.asSequence()
            .flatMap { root -> ids.asSequence().map { id -> File(root, "steamapps/compatdata/$id/pfx") } }
            .firstOrNull { it.isDirectory }
    }

    fun steamGames(context: Context): List<SteamGame> {
        val root = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam")
        val cache = File(root, "appcache/librarycache")
        val libraries = steamLibraries(context)
        val out = LinkedHashMap<Int, SteamGame>()
        for ((library, label) in libraries) {
            val steamapps = File(library, "steamapps")
            steamapps.listFiles { f -> f.isFile && f.name.startsWith("appmanifest_") && f.name.endsWith(".acf") }
                ?.sortedBy { it.name }?.forEach { manifest ->
                    val appId = manifest.name.removePrefix("appmanifest_").removeSuffix(".acf").toIntOrNull() ?: return@forEach
                    if (appId in NOT_GAMES || out.containsKey(appId)) return@forEach
                    val text = try { manifest.readText() } catch (e: Exception) { return@forEach }
                    val name = NAME.find(text)?.groupValues?.get(1)?.trim().orEmpty()
                    val flags = STATE.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    // StateFlags 4 = fully installed; anything else is downloading, updating or broken.
                    if (name.isEmpty() || flags and 4 == 0) return@forEach
                    val art = steamCacheImage(cache, appId, STEAM_CAPSULES)
                    val hero = steamCacheImage(cache, appId, STEAM_HEROES)
                    val lastPlayed = LAST_PLAYED.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    // App manifests are Valve KeyValues (VDF) files. installdir is one folder
                    // below steamapps/common; only expose it when the directory exists and the
                    // manifest value cannot escape that directory.
                    val installDir = INSTALL_DIR.find(text)?.groupValues?.get(1)?.trim()
                        ?.takeIf { it.isNotEmpty() && it != "." && it != ".." && '/' !in it && '\\' !in it }
                    val gameFiles = installDir?.let { File(steamapps, "common/$it").takeIf(File::isDirectory) }
                    out[appId] = SteamGame(
                        appId, name, art, label, hero = hero, lastPlayed = lastPlayed,
                        gameFiles = gameFiles,
                        protonPrefix = protonPrefix(context, appId.toLong(), library),
                    )
                }
        }
        return out.values.toList()
    }

    /** Steam stores current library art inside hash-named folders under the app's cache dir. */
    private fun steamCacheImage(cache: File, appId: Int, names: List<String>): File? {
        val appDir = File(cache, appId.toString())
        val dirs = listOf(appDir) + appDir.listFiles()
            .orEmpty().filter { it.isDirectory }.sortedBy { it.name }
        return names.asSequence()
            .flatMap { name -> dirs.asSequence().map { File(it, name) } }
            .firstOrNull { it.isFile && it.length() > 0L }
    }

    /** [atPanel]: frames cheap enough to draw at the panel's own size (see [drawsAtPanel]). */
    private class Spec(val id: String, val name: String, val system: String, val program: String, val folders: List<String>, val exts: Set<String>, val atPanel: Boolean = false)
    private val specs = listOf(
        Spec("rpcs3", "RPCS3", "PS3", "/opt/appimages/rpcs3.AppImage", listOf("ps3"), setOf("iso")),
        // PS2: ARMSX2, the PCSX2 fork with ARM64 recompilers. Upstream PCSX2 interprets the PS2's
        // CPUs on ARM64 (NFS Underground 2: 17 fps against ARMSX2's full 60), so it is not offered.
        Spec("armsx2", "ARMSX2", "PS2", "/opt/appimages/armsx2.AppImage", listOf("ps2"), setOf("iso", "chd", "cso", "gz")),
        Spec("dolphin", "Dolphin", "GameCube / Wii", "/opt/appimages/dolphin.AppImage", listOf("gc", "gamecube", "wii"), setOf("iso", "rvz", "gcz", "wbfs", "ciso")),
        Spec("duckstation", "DuckStation", "PS1", "/opt/appimages/duckstation.AppImage", listOf("ps1", "psx"), setOf("cue", "chd", "pbp", "iso", "bin", "img", "ecm", "m3u")),
        Spec("melonds", "melonDS", "DS", "/opt/appimages/melonds.AppImage", listOf("ds", "nds"), setOf("nds", "dsi"), atPanel = true),
        Spec("cemu", "Cemu", "Wii U", "/opt/appimages/cemu.AppImage", listOf("wiiu", "wii u"), setOf("wua", "wud", "wux", "rpx")),
        Spec("ppsspp", "PPSSPP", "PSP", "/usr/bin/PPSSPPSDL", listOf("psp"), setOf("iso", "cso", "pbp", "chd")),
        Spec("retroarch", "RetroArch", "many systems", "/usr/bin/retroarch", emptyList(), emptySet()),
    )
    /**
     * A dump's file name as a title: its tags - (USA), (En,Fr,Es,Pt), [!], (v1.01) - identify the
     * file and are dropped for reading ("Tomb Raider (USA) (En,Fr,Es,Pt)" -> "Tomb Raider"). The
     * file keeps them, and the cover lookup still uses them.
     */
    private fun displayTitle(name: String): String =
        name.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "").trim().ifEmpty { name }

    /** Every system folder name the specs claim, so a loose-file scan of the root skips them. */
    private val systemFolders: Set<String> by lazy { specs.flatMap { it.folders }.toSet() }
    private val installedIds = mapOf(
        "rpcs3" to "rpcs3", "armsx2" to "armsx2", "dolphin" to "dolphin", "duckstation" to "duckstation",
        "melonds" to "melonds", "cemu" to "cemu", "ppsspp" to "emulators", "retroarch" to "emulators",
    )

    /** The emulator's name for a program path from the rail ("ARMSX2"), or null. */
    fun nameForProgram(program: String?): String? =
        if (program == com.droiddeck.launcher.runtime.FlatpakManager.LAUNCHER || program == com.droiddeck.launcher.runtime.AppImageManager.LAUNCHER) {
            com.droiddeck.launcher.session.SessionState.programArgs.firstOrNull()?.let { flatpakNames[it] ?: it.substringAfterLast('.') }
        } else specs.firstOrNull { it.program == program }?.name

    /** Flatpak apps' and AppImages' names by id or directory, as launched: the session only knows that. */
    val flatpakNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Whether a program from the rail runs at the panel's own resolution rather than the session's
     * 720p default. melonDS draws two 256x192 screens on the CPU: the panel's size costs it
     * nothing, and 720p scaled up to the panel blurs the sharp pixels its screen layout is set
     * up for (bannerlator-pad-defaults).
     */
    fun drawsAtPanel(program: String?): Boolean = specs.any { it.program == program && it.atPanel }

    /** Desktop catalog package that supplies this emulator. */
    fun packageId(emulatorId: String): String? = installedIds[emulatorId]

    /** Every emulator the app knows, installed or not, with the games its system folder holds. */
    fun emulators(context: Context, installedPackage: (String) -> Boolean): List<Emulator> {
        val romsRoot = SessionPrefs.romsDir(context).takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isDirectory }
        return specs.map { spec ->
            val games = ArrayList<Rom>()
            if (romsRoot != null && spec.exts.isNotEmpty()) {
                // The system's folder(s), matched without regard to case, and up to three folders
                // inside them - a dump usually comes as a folder named for the game with the image
                // inside it, and people sort those into folders of their own (ps2/games/<game>/).
                // The root itself only one deep, for a file left loose there.
                val systemDirs = romsRoot.listFiles { f -> f.isDirectory && f.name.lowercase() in spec.folders }.orEmpty().toList()
                val dirs = LinkedHashSet<File>()
                // A BIOS folder holds the console's firmware, not games (psx/bios/SCPH1001.BIN).
                for (top in systemDirs) top.walkTopDown().maxDepth(3).onEnter { it.name.lowercase() !in firmwareFolders }
                    .filter { it.isDirectory }.forEach { dirs.add(it) }
                dirs.add(romsRoot)
                // ...and not into another system's folder: a PS3 .iso in ps3/ is not a PS2 game.
                romsRoot.listFiles { f -> f.isDirectory && f.name.lowercase() !in systemFolders }?.forEach { dirs.add(it) }
                for (dir in dirs) {
                    // A PS3 disc dump is a folder with PS3_GAME in it; RPCS3 boots the folder.
                    if (spec.id == "rpcs3" && File(dir, "PS3_GAME").isDirectory) {
                        val rel = dir.relativeTo(romsRoot).path
                        games.add(Rom(displayTitle(dir.name), dir, "/root/ROMs/$rel", spec.id,
                            // The dump carries its own art, as an installed package does.
                            art = File(dir, "PS3_GAME/ICON0.PNG").takeIf { it.isFile }))
                        continue
                    }
                    val files = dir.listFiles()?.sortedBy { it.name.lowercase() }.orEmpty()
                    // A disc sheet (.cue, .gdi) names its track files and a playlist (.m3u) its
                    // discs: the sheet is the game, what it names is part of it. A .bin beside a
                    // .cue of the same name is a track even when the sheet can't be read.
                    val parts = files.filter { it.isFile && it.extension.lowercase() in sheetExts }.flatMap(::sheetParts).toSet()
                    files.forEach { f ->
                        val ext = f.extension.lowercase()
                        if (f.isFile && ext in spec.exts && f.name.lowercase() !in parts &&
                            !(ext == "bin" && File(dir, f.nameWithoutExtension + ".cue").isFile)) {
                            val rel = f.relativeTo(romsRoot).path
                            games.add(Rom(displayTitle(f.nameWithoutExtension.removeSuffix(".dec")), f, "/root/ROMs/$rel", spec.id))
                        }
                    }
                }
            }
            if (spec.id == "rpcs3") games.addAll(rpcs3Installed(context))
            // A cover found for the game before (CoverArt) where it has no art of its own.
            val withArt = games.map { g -> if (g.art != null) g else CoverArt.cached(context, g)?.let { Rom(g.name, g.hostPath, g.guestPath, g.emulatorId, it) } ?: g }
            Emulator(spec.id, spec.name, spec.system, spec.program, installedPackage(installedIds.getValue(spec.id)), withArt)
        }
    }

    private val firmwareFolders = setOf("bios", "firmware")
    private val sheetExts = setOf("cue", "gdi", "m3u")

    /** The file names (lower case) a .cue, .gdi or .m3u refers to, from its own folder. */
    private fun sheetParts(sheet: File): List<String> = runCatching {
        if (sheet.length() > 64 * 1024) return emptyList()
        sheet.readLines().mapNotNull { line ->
            val t = line.trim()
            when (sheet.extension.lowercase()) {
                // FILE "Tekken 3 (USA) (Track 1).bin" BINARY
                "cue" -> Regex("^FILE\\s+\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)
                    ?: Regex("^FILE\\s+(\\S+)", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)
                // 1 0 4 2352 "track01.bin" 0  /  1 0 4 2352 track01.bin 0
                "gdi" -> Regex("\"([^\"]+)\"").find(t)?.groupValues?.get(1) ?: t.split(Regex("\\s+")).getOrNull(4)
                else -> t.takeIf { it.isNotEmpty() && !it.startsWith("#") }
            }
        }.map { File(it.replace('\\', '/')).name.lowercase() }
    }.getOrDefault(emptyList())

    /**
     * What RPCS3 has installed on its own HDD - packages (PSN games) land in dev_hdd0/game/<ID>
     * with a PARAM.SFO for the title and an EBOOT to boot - as RPCS3's own file list would show
     * them. Not in the ROMs folder, so listed from RPCS3's home in the runtime.
     */
    private fun rpcs3Installed(context: Context): List<Rom> {
        val hdd = File(LinuxRuntime.rootDir(context), "root/.config/rpcs3/dev_hdd0/game")
        return hdd.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.mapNotNull { dir ->
            val eboot = File(dir, "USRDIR/EBOOT.BIN")
            val sfo = File(dir, "PARAM.SFO")
            if (!eboot.isFile || !sfo.isFile) return@mapNotNull null
            val fields = readSfo(sfo)
            // Only games: patches, DLC and save data live here too, with their own categories.
            if (fields["CATEGORY"]?.let { it == "HG" || it == "DG" || it == "GD" } == false) return@mapNotNull null
            val title = fields["TITLE"]?.trim()?.takeIf { it.isNotEmpty() } ?: dir.name
            Rom(title, eboot, "/root/.config/rpcs3/dev_hdd0/game/${dir.name}/USRDIR/EBOOT.BIN", "rpcs3",
                art = File(dir, "ICON0.PNG").takeIf { it.isFile })
        } ?: emptyList()
    }

    /** The string fields of a PARAM.SFO (the PSP/PS3 metadata file): a small binary table. */
    private fun readSfo(file: File): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            val b = file.readBytes()
            if (b.size < 20 || b[0] != 0.toByte() || b[1] != 'P'.code.toByte()) return out
            fun u32(at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)
            fun u16(at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)
            val keys = u32(8); val data = u32(12); val count = u32(16)
            for (i in 0 until count) {
                val e = 20 + i * 16
                if (e + 16 > b.size) break
                val keyOff = u16(e); val fmt = u16(e + 2); val len = u32(e + 4); val dataOff = u32(e + 12)
                val keyStart = keys + keyOff
                var keyEnd = keyStart
                while (keyEnd < b.size && b[keyEnd] != 0.toByte()) keyEnd++
                val key = String(b, keyStart, keyEnd - keyStart, Charsets.US_ASCII)
                if (fmt == 0x0204 || fmt == 0x0004) {   // utf8 string (null-terminated or not)
                    val start = data + dataOff
                    val end = minOf(b.size, start + len)
                    var stop = start
                    while (stop < end && b[stop] != 0.toByte()) stop++
                    out[key] = String(b, start, stop - start, Charsets.UTF_8)
                }
            }
        } catch (e: Exception) {
            // unreadable metadata: the folder name stands in
        }
        return out
    }

    /** How the emulator is told which game to boot, on its command line. */
    fun launchArgs(emulatorId: String, guestPath: String): List<String> = when (emulatorId) {
        "rpcs3" -> listOf("--no-gui", guestPath)
        // Straight into the game in its controller-driven full-screen UI (first-time setup there
        // too), and gone when the game is quit from its pause menu (guide button): -batch.
        "armsx2" -> listOf("-batch", "-bigpicture", "-fullscreen", "--", guestPath)
        // DuckStation shares PCSX2's flags (src/duckstation-qt/qthost.cpp): the same launch and exit.
        "duckstation" -> listOf("-batch", "-bigpicture", "-fullscreen", "--", guestPath)
        // Dolphin: full screen, drawn inside its own main window, without its "stop the emulation?"
        // question; no warning boxes either, which wait for a click a controller cannot give
        // (they still go to Dolphin's log). The guide button is Dolphin's Toggle Fullscreen hotkey
        // (bannerlator-pad-defaults), which shows Dolphin's window and its settings - so no -b, which
        // hides that window; closing Dolphin ends the session. Under gamescope Dolphin does not
        // always see its window as focused, and by default both its hotkeys and the game's
        // controller then stop: HotkeysRequireFocus off, BackgroundInput on. -C sets a Dolphin.ini
        // value for this run only, so Dolphin started from the desktop keeps its own settings.
        "dolphin" -> listOf(
            "-C", "Dolphin.Display.Fullscreen=True", "-C", "Dolphin.Display.RenderToMain=True",
            "-C", "Dolphin.Interface.ConfirmStop=False", "-C", "Dolphin.Interface.UsePanicHandlers=False",
            "-C", "Dolphin.General.HotkeysRequireFocus=False", "-C", "Dolphin.Input.BackgroundInput=True",
            "-e", guestPath,
        )
        // Full screen (LaunchSettings.cpp -f); the settings seed keeps the Getting Started
        // dialog away (bannerlator-pad-defaults).
        "cemu" -> listOf("-f", "-g", guestPath)
        // Full screen (CLI.cpp --fullscreen); the guide button leaves it for melonDS's menus and
        // comes back (HK_FullscreenToggle, bannerlator-pad-defaults).
        "melonds" -> listOf("-f", guestPath)
        else -> listOf(guestPath)
    }
}
