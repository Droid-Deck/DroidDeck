package com.droiddeck.launcher.frontend

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.GameStorage
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SecondaryLibrary
import java.io.File

object Library {
    class SteamGame(
        val appId: Int, val name: String, val art: File?, val library: String, val gameId: Long = appId.toLong(),
        val hero: File? = null, val lastPlayed: Long = 0L,
        val gameFiles: File? = null, val protonPrefix: File? = null,
        val icon: File? = null,
        val source: String = SOURCE_STEAM,
        val storeId: String? = null,
        val logo: File? = null,
    ) {
        fun withArt(art: File?, hero: File?, icon: File?, logo: File? = this.logo) =
            SteamGame(appId, name, art, library, gameId, hero, lastPlayed, gameFiles, protonPrefix, icon, source, storeId, logo)

        val gameIdString: String get() = java.lang.Long.toUnsignedString(gameId)
        val profileKey: String get() = if (library == ADDED) java.lang.Integer.toUnsignedString(appId) else appId.toString()
    }
    const val ADDED = "added"
    const val EPIC_LAUNCHER = "epic-launcher"
    const val EPIC_LAUNCHER_NAME = "Epic Games Launcher"
    const val SOURCE_STEAM = "steam"

    class Rom(val name: String, val hostPath: File, val guestPath: String, val emulatorId: String, val art: File? = null)
    class Emulator(val id: String, val name: String, val system: String, val program: String, val installed: Boolean, val games: List<Rom>) {
        val systemRes: Int get() = if (id == "retroarch") R.string.content_system_many else 0
        val iconRes: Int get() = when (id) {
            "rpcs3" -> R.drawable.emu_rpcs3; "armsx2" -> R.drawable.emu_pcsx2; "dolphin" -> R.drawable.emu_dolphin
            "duckstation" -> R.drawable.emu_duckstation; "melonds" -> R.drawable.emu_melonds; "cemu" -> R.drawable.emu_cemu
            "ppsspp" -> R.drawable.emu_ppsspp; else -> R.drawable.emu_retroarch
        }
    }

    private val NOT_GAMES = setOf(
        858280,
        961940,
        993090,
        1054830,
        1070560,
        1113280,
        1245040,
        1391110,
        1420170,
        1493710,
        1580130,
        1628350,
        1887720,
        2180100,
        228980,
        2348590,
        2805730,
        3029110,
        3127680,
        3658110,
        4183110,
        4185400,
        4427310,
        4628710,
        4628740,
        4690330,
    )
    private val STEAM_CAPSULES = listOf("library_capsule.jpg", "library_600x900.jpg")
    private val STEAM_HEROES = listOf("library_hero.jpg")
    internal val STEAM_LOGOS = listOf("logo.png")
    private val STEAM_ICON = Regex("[a-fA-F0-9]{40}\\.(jpg|png)")
    private val NAME = Regex("^\\s*\"name\"\\s*\"([^\"]*)\"", RegexOption.MULTILINE)
    private val STATE = Regex("^\\s*\"StateFlags\"\\s*\"(\\d+)\"", RegexOption.MULTILINE)
    private val LAST_PLAYED = Regex("^\\s*\"LastPlayed\"\\s*\"(\\d+)\"", RegexOption.MULTILINE)
    private val PRIVATE_APPS = Regex("\"PrivateApps_\\d+\"\\s*\"\\[([^\\]]*)\\]\"")
    private val INSTALL_DIR = Regex("^\\s*\"installdir\"\\s*\"([^\"]*)\"", RegexOption.MULTILINE)

    internal fun steamLibraries(context: Context, arch: String = SessionPrefs.activeSteamClientArch(context)): List<Pair<File, String>> {
        val root = SessionPrefs.steamRoot(context, arch)
        // The session gives the x86-64 client only its card folder; the games folders stay the arm64 client's.
        val card = GameStorage.effective(context)
        if (arch == "x86_64") return listOfNotNull(root to "internal", card?.let { File(it.path, GameStorage.X64_CARD_DIR) to it.label })
        return listOfNotNull(
            root to "internal",
            card?.let { File(it.path) to it.label },
        ) + GameStorage.gamesFolderLibraries(context).map { it.host to it.label }
    }

    fun protonPrefix(
        context: Context, appId: Long, preferredLibrary: File? = null,
        libraries: List<File> = steamLibraries(context).map { it.first },
    ): File? {
        val ids = listOf(appId.toString(), java.lang.Integer.toString(appId.toInt())).distinct()
        val secondary = libraries.drop(1).mapNotNull { runCatching { it.canonicalPath }.getOrNull() }.toSet()
        fun prefixRoot(root: File): File = if (runCatching { root.canonicalPath }.getOrNull() in secondary)
            SecondaryLibrary.privateRoot(context.filesDir, root).takeIf { File(it, "steamapps/compatdata").isDirectory } ?: root
            else root
        val roots = (listOfNotNull(preferredLibrary) + libraries).map(::prefixRoot)
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        return roots.asSequence()
            .flatMap { root -> ids.asSequence().map { id -> File(root, "steamapps/compatdata/$id/pfx") } }
            .firstOrNull { it.isDirectory }
    }

    fun steamGames(context: Context, strictRead: Boolean = false, arch: String = SessionPrefs.activeSteamClientArch(context)): List<SteamGame> {
        val root = SessionPrefs.steamRoot(context, arch)
        val cache = File(root, "appcache/librarycache")
        val libraries = steamLibraries(context, arch)
        val roots = libraries.map { it.first }
        val out = LinkedHashMap<Int, SteamGame>()
        for ((library, label) in libraries) {
            val steamapps = File(library, "steamapps")
            val manifests = steamapps.listFiles { f -> f.isFile && f.name.startsWith("appmanifest_") && f.name.endsWith(".acf") }
            if (strictRead && steamapps.isDirectory) check(manifests != null) { context.getString(R.string.game_sync_err_manifests) }
            manifests?.sortedBy { it.name }?.forEach { manifest ->
                    val appId = manifest.name.removePrefix("appmanifest_").removeSuffix(".acf").toIntOrNull() ?: return@forEach
                    if (appId in NOT_GAMES || out.containsKey(appId)) return@forEach
                    val text = try { manifest.readText() } catch (e: Exception) { if (strictRead) throw e; return@forEach }
                    val name = NAME.find(text)?.groupValues?.get(1)?.trim().orEmpty()
                    val flags = STATE.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (name.isEmpty() || flags and 4 == 0) return@forEach
                    val art = steamCacheImage(cache, appId, STEAM_CAPSULES)
                    val hero = steamCacheImage(cache, appId, STEAM_HEROES)
                    val lastPlayed = LAST_PLAYED.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val installDir = INSTALL_DIR.find(text)?.groupValues?.get(1)?.trim()
                        ?.takeIf { it.isNotEmpty() && it != "." && it != ".." && '/' !in it && '\\' !in it }
                    val gameFiles = installDir?.let { File(steamapps, "common/$it").takeIf(File::isDirectory) }
                    out[appId] = SteamGameArt.withOverrides(context, SteamGame(
                        appId, name, art, label, hero = hero, lastPlayed = lastPlayed,
                        gameFiles = gameFiles,
                        protonPrefix = protonPrefix(context, appId.toLong(), library, roots),
                        icon = steamCacheImage(cache, appId, listOf("icon.jpg", "icon.png"))
                            ?: File(cache, appId.toString()).listFiles()?.firstOrNull { it.isFile && STEAM_ICON.matches(it.name) },
                        logo = steamCacheImage(cache, appId, STEAM_LOGOS),
                    ))
                }
        }
        return out.values.toList()
    }

    fun steamLibraryArt(context: Context): List<Pair<Int, File>> {
        val root = SessionPrefs.activeSteamRoot(context)
        val cache = File(root, "appcache/librarycache")
        val concealed = concealedSteamApps(root)
        return cache.listFiles().orEmpty().mapNotNull { dir ->
            val appId = dir.name.toIntOrNull()?.takeIf { it !in NOT_GAMES && it.toLong() !in concealed } ?: return@mapNotNull null
            steamCacheImage(cache, appId, STEAM_CAPSULES)?.let { appId to it }
        }
    }

    /** Apps the user hid (the "hidden" collection) or marked private in Steam. */
    internal fun concealedSteamApps(root: File): Set<Long> {
        val config = AddedGames.accountConfig(root) ?: return emptySet()
        val hidden = runCatching {
            val entries = org.json.JSONArray(File(config, "cloudstorage/cloud-storage-namespace-1.json").readText())
            val entry = (0 until entries.length()).map { entries.getJSONArray(it) }
                .firstOrNull { it.optString(0) == "user-collections.hidden" }?.optJSONObject(1)
            if (entry == null || entry.optBoolean("is_deleted")) emptySet() else {
                val collection = org.json.JSONObject(entry.optString("value"))
                fun ids(key: String) = collection.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optLong(it) } }.orEmpty()
                ids("added").toSet() - ids("removed").toSet()
            }
        }.getOrDefault(emptySet())
        val private = runCatching {
            PRIVATE_APPS.find(File(config, "localconfig.vdf").readText())?.groupValues?.get(1)
                ?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet()
        }.getOrNull().orEmpty()
        return hidden + private
    }

    fun fromSteamClient(g: SteamGame): Boolean = g.library != ADDED && g.source == SOURCE_STEAM

    fun launchableGames(
        context: Context, strictRead: Boolean = false, added: List<AddedGames.Game>? = null,
        arch: String = SessionPrefs.activeSteamClientArch(context),
    ): List<SteamGame> {
        val roots = steamLibraries(context, arch).map { it.first }
        val list = added ?: AddedGames.scan(context)
        var steam = steamGames(context, strictRead, arch)
        val targets = steam.filter(::fromSteamClient).map { SteamGrid.target(it, context) } + list.map { SteamGrid.target(it, context) }
        if (runCatching { SteamGrid.sync(context, targets) }.getOrDefault(false)) steam = steamGames(context, strictRead, arch)
        val claimed = claimedFolders(steam)
        val own = list.filter { g ->
            g.steamAppId != null || claimed.isEmpty() ||
                (runCatching { g.folder.canonicalPath }.getOrDefault(g.folder.absolutePath) !in claimed &&
                    AddedGames.guestPath(context, g.folder).let { it == null || it !in claimed })
        }
        return (steam + own.map { g -> addedGame(context, g, roots) }).distinctBy { it.gameId }
    }

    fun claimedFolders(steam: List<SteamGame>): Set<String> = steam.filter(::fromSteamClient).flatMap { g ->
        val dir = g.gameFiles ?: return@flatMap emptyList()
        listOfNotNull(
            runCatching { dir.canonicalPath }.getOrNull(),
            runCatching { java.nio.file.Files.readSymbolicLink(dir.toPath()).toString() }.getOrNull(),
        )
    }.toSet()

    fun addedGame(context: Context, g: AddedGames.Game, roots: List<File> = steamLibraries(context).map { it.first }): SteamGame =
        AddedGameArt.resolve(context, g).let { art ->
            SteamGame(
                g.steamAppId ?: g.appId.toInt(), g.name, art.portrait ?: art.header, ADDED, g.gameId,
                hero = art.hero ?: art.header, gameFiles = g.folder,
                protonPrefix = protonPrefix(context, g.steamAppId?.toLong() ?: g.appId, libraries = roots),
                icon = art.icon?.takeIf { it.extension.lowercase() != "ico" },
                source = g.source, storeId = g.storeId,
                logo = art.logo,
            )
        }

    internal fun steamCacheImage(cache: File, appId: Int, names: List<String>): File? {
        val appDir = File(cache, appId.toString())
        val dirs = listOf(appDir) + appDir.listFiles()
            .orEmpty().filter { it.isDirectory }.sortedBy { it.name }
        return names.asSequence()
            .flatMap { name -> dirs.asSequence().map { File(it, name) } }
            .firstOrNull { it.isFile && it.length() > 0L }
    }

    private class Spec(val id: String, val name: String, val system: String, val program: String, val folders: List<String>, val exts: Set<String>, val atPanel: Boolean = false)
    private val specs = listOf(
        Spec("rpcs3", "RPCS3", "PS3", "/opt/appimages/rpcs3.AppImage", listOf("ps3"), setOf("iso")),
        Spec("armsx2", "ARMSX2", "PS2", "/opt/appimages/armsx2.AppImage", listOf("ps2"), setOf("iso", "chd", "cso", "gz")),
        Spec("dolphin", "Dolphin", "GameCube / Wii", "/opt/appimages/dolphin.AppImage", listOf("gc", "gamecube", "wii"), setOf("iso", "rvz", "gcz", "wbfs", "ciso")),
        Spec("duckstation", "DuckStation", "PS1", "/opt/appimages/duckstation.AppImage", listOf("ps1", "psx"), setOf("cue", "chd", "pbp", "iso", "bin", "img", "ecm", "m3u")),
        Spec("melonds", "melonDS", "DS", "/opt/appimages/melonds.AppImage", listOf("ds", "nds"), setOf("nds", "dsi"), atPanel = true),
        Spec("cemu", "Cemu", "Wii U", "/opt/appimages/cemu.AppImage", listOf("wiiu", "wii u"), setOf("wua", "wud", "wux", "rpx")),
        Spec("ppsspp", "PPSSPP", "PSP", "/usr/bin/PPSSPPSDL", listOf("psp"), setOf("iso", "cso", "pbp", "chd")),
        Spec("retroarch", "RetroArch", "many systems", "/usr/bin/retroarch", emptyList(), emptySet()),
    )
    private fun displayTitle(name: String): String =
        name.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "").trim().ifEmpty { name }

    private val systemFolders: Set<String> by lazy { specs.flatMap { it.folders }.toSet() }
    private val installedIds = mapOf(
        "rpcs3" to "rpcs3", "armsx2" to "armsx2", "dolphin" to "dolphin", "duckstation" to "duckstation",
        "melonds" to "melonds", "cemu" to "cemu", "ppsspp" to "emulators", "retroarch" to "emulators",
    )

    fun nameForProgram(program: String?): String? =
        if (program == com.droiddeck.launcher.runtime.FlatpakManager.LAUNCHER || program == com.droiddeck.launcher.runtime.AppImageManager.LAUNCHER ||
            program == com.droiddeck.launcher.runtime.UserApps.SCRIPT_LAUNCHER) {
            com.droiddeck.launcher.session.SessionState.programArgs.firstOrNull()?.let { flatpakNames[it] ?: it.substringAfterLast('.') }
        } else com.droiddeck.launcher.session.NoSteamLaunch.nameFor(program, com.droiddeck.launcher.session.SessionState.programArgs)
            ?: specs.firstOrNull { it.program == program }?.name

    val flatpakNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun drawsAtPanel(program: String?): Boolean = specs.any { it.program == program && it.atPanel }

    fun packageId(emulatorId: String): String? = installedIds[emulatorId]

    fun emulators(context: Context, installedPackage: (String) -> Boolean): List<Emulator> {
        val romsRoot = SessionPrefs.romsDir(context).takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isDirectory }
        return specs.map { spec ->
            val games = ArrayList<Rom>()
            if (romsRoot != null && spec.exts.isNotEmpty()) {
                val systemDirs = romsRoot.listFiles { f -> f.isDirectory && f.name.lowercase() in spec.folders }.orEmpty().toList()
                val dirs = LinkedHashSet<File>()
                for (top in systemDirs) top.walkTopDown().maxDepth(3).onEnter { it.name.lowercase() !in firmwareFolders }
                    .filter { it.isDirectory }.forEach { dirs.add(it) }
                dirs.add(romsRoot)
                romsRoot.listFiles { f -> f.isDirectory && f.name.lowercase() !in systemFolders }?.forEach { dirs.add(it) }
                for (dir in dirs) {
                    if (spec.id == "rpcs3" && File(dir, "PS3_GAME").isDirectory) {
                        val rel = dir.relativeTo(romsRoot).path
                        games.add(Rom(displayTitle(dir.name), dir, "/root/ROMs/$rel", spec.id,
                            art = File(dir, "PS3_GAME/ICON0.PNG").takeIf { it.isFile }))
                        continue
                    }
                    val files = dir.listFiles()?.sortedBy { it.name.lowercase() }.orEmpty()
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
            val withArt = games.map { g -> if (g.art != null) g else CoverArt.cached(context, g)?.let { Rom(g.name, g.hostPath, g.guestPath, g.emulatorId, it) } ?: g }
            Emulator(spec.id, spec.name, spec.system, spec.program, installedPackage(installedIds.getValue(spec.id)), withArt)
        }
    }

    private val firmwareFolders = setOf("bios", "firmware")
    private val sheetExts = setOf("cue", "gdi", "m3u")

    private fun sheetParts(sheet: File): List<String> = runCatching {
        if (sheet.length() > 64 * 1024) return emptyList()
        sheet.readLines().mapNotNull { line ->
            val t = line.trim()
            when (sheet.extension.lowercase()) {
                "cue" -> Regex("^FILE\\s+\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)
                    ?: Regex("^FILE\\s+(\\S+)", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)
                "gdi" -> Regex("\"([^\"]+)\"").find(t)?.groupValues?.get(1) ?: t.split(Regex("\\s+")).getOrNull(4)
                else -> t.takeIf { it.isNotEmpty() && !it.startsWith("#") }
            }
        }.map { File(it.replace('\\', '/')).name.lowercase() }
    }.getOrDefault(emptyList())

    private fun rpcs3Installed(context: Context): List<Rom> {
        val hdd = File(LinuxRuntime.rootDir(context), "root/.config/rpcs3/dev_hdd0/game")
        return hdd.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.mapNotNull { dir ->
            val eboot = File(dir, "USRDIR/EBOOT.BIN")
            val sfo = File(dir, "PARAM.SFO")
            if (!eboot.isFile || !sfo.isFile) return@mapNotNull null
            val fields = readSfo(sfo)
            if (fields["CATEGORY"]?.let { it == "HG" || it == "DG" || it == "GD" } == false) return@mapNotNull null
            val title = fields["TITLE"]?.trim()?.takeIf { it.isNotEmpty() } ?: dir.name
            Rom(title, eboot, "/root/.config/rpcs3/dev_hdd0/game/${dir.name}/USRDIR/EBOOT.BIN", "rpcs3",
                art = File(dir, "ICON0.PNG").takeIf { it.isFile })
        } ?: emptyList()
    }

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
                if (fmt == 0x0204 || fmt == 0x0004) {
                    val start = data + dataOff
                    val end = minOf(b.size, start + len)
                    var stop = start
                    while (stop < end && b[stop] != 0.toByte()) stop++
                    out[key] = String(b, start, stop - start, Charsets.UTF_8)
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun launchArgs(emulatorId: String, guestPath: String): List<String> = when (emulatorId) {
        "rpcs3" -> listOf("--no-gui", guestPath)
        "armsx2" -> listOf("-batch", "-bigpicture", "-fullscreen", "--", guestPath)
        "duckstation" -> listOf("-batch", "-bigpicture", "-fullscreen", "--", guestPath)
        "dolphin" -> listOf(
            "-C", "Dolphin.Display.Fullscreen=True", "-C", "Dolphin.Display.RenderToMain=True",
            "-C", "Dolphin.Interface.ConfirmStop=False", "-C", "Dolphin.Interface.UsePanicHandlers=False",
            "-C", "Dolphin.General.HotkeysRequireFocus=False", "-C", "Dolphin.Input.BackgroundInput=True",
            "-e", guestPath,
        )
        "cemu" -> listOf("-f", "-g", guestPath)
        "melonds" -> listOf("-f", guestPath)
        else -> listOf(guestPath)
    }
}
