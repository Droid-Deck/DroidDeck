package com.droiddeck.launcher.frontend

import android.content.Context
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Which Windows components a game needs, from the list its source keeps - the source badge decides
 * which list is trusted:
 *
 * - **Steam**: the Steamworks Shared redistributables Steam installs with the game (its
 *   appmanifest's SharedDepots, else the client's app info) - turned on by themselves.
 * - **GOG**: the build manifest's `dependencies`, kept in the sidecar at install - on by themselves.
 * - **Epic Games**: the manifest's prerequisite (usually UE4/UE5's prerequisite setup: VC++ and the
 *   DirectX June 2010 runtime), kept in the sidecar at install - on by themselves.
 * - **Amazon Games**: fuel.json's PostInstall installers - on by themselves.
 * - **Custom** whose files name a Steam appid ([SteamMatch], certain): Steam's list for that app -
 *   on by themselves. Matched by name only: the same list as Recommended. No match: the folder scan
 *   ([DependencyDetector]) as Recommended.
 *
 * Every store entry is mapped to catalog names here, in one place; one that maps to nothing is a
 * suggestion under its own name. The folder scan is offered as Recommended for every game.
 */
object WinCompSources {
    /** Whose list a finding came from; the reason line says it. */
    enum class Origin { STEAM, GOG, EPIC, AMAZON, STEAM_NAME, FOLDER }

    /**
     * One thing a list names: [component] the catalog name it maps to (null: none, a suggestion),
     * [original] what the list called it, [auto] whether it is turned on by itself, [args] what
     * the reason line names (Steam: the label, the game, the appid).
     */
    data class Finding(val component: String?, val original: String, val origin: Origin, val auto: Boolean, val args: List<String> = emptyList())

    // ---- mapping tables: a store's names -> catalog components ----

    private val VC_YEAR = mapOf(
        "2005" to "vcredist2005", "2008" to "vcredist2008", "2010" to "vcredist2010", "2012" to "vcredist2012",
        "2013" to "vcredist2013", "2015" to "vcredist2015", "2017" to "vcredist2015", "2019" to "vcredist2019", "2022" to "vcredist2022",
    )
    private val DOTNET = mapOf(
        "35" to "dotnet35", "40" to "dotnet40", "45" to "dotnet45", "451" to "dotnet452", "452" to "dotnet452",
        "46" to "dotnet46", "461" to "dotnet46", "462" to "dotnet46", "47" to "dotnet472", "471" to "dotnet472",
        "472" to "dotnet472", "48" to "dotnet48", "481" to "dotnet48",
    )

    /**
     * A GOG dependency id ("MSVC2017_x64", "DirectX", "DOTNET48", "PhysX", "OpenAL", "XNA4") to its
     * components; empty for one that is none of ours (ISI scripts, GOG's own helpers).
     */
    fun gog(id: String): List<String> {
        val key = id.uppercase(Locale.US).replace(Regex("[^A-Z0-9]"), "")
        Regex("""^(?:MSVC|VCREDIST|VC)(\d{4})""").find(key)?.let { m -> return listOfNotNull(VC_YEAR[m.groupValues[1]]) }
        Regex("""^(?:DOTNET|NETFRAMEWORK|DOTNETFRAMEWORK)(\d{2,3})""").find(key)?.let { m -> return listOfNotNull(DOTNET[m.groupValues[1]]) }
        return when {
            key.startsWith("DIRECTX") || key.startsWith("DX9") || key == "DXSETUP" || key.startsWith("D3DX") -> listOf("d3dx9")
            key.startsWith("PHYSX") -> listOf("physx")
            key.startsWith("OPENAL") -> listOf("oalinst")
            key == "XNA31" || key == "XNA3" -> listOf("xna31")
            key.startsWith("XNA") -> listOf("xna40")
            key.startsWith("XLIVE") || key.startsWith("GFWL") -> listOf("XLiveRedist")
            key.startsWith("XACT") -> listOf("xact")
            else -> emptyList()
        }
    }

    /** Unreal's prerequisite setup (UE4PrereqSetup_x64.exe, UEPrereqSetup_x64.exe): VC++ 2015-2022 and DirectX June 2010. */
    private val UE_PREREQ = Regex("""(?i)ue\d?prereqsetup""")

    /** An Epic prerequisite ([path] the installer it runs, [name] what it is called) to its components. */
    fun epic(path: String, name: String): List<String> = when {
        UE_PREREQ.containsMatchIn(path) || name.contains("UE4 Prerequisites", true) || name.contains("UE5 Prerequisites", true) ||
            name.contains("Unreal Engine Prerequisites", true) -> listOf("vcredist2022", "d3dx9")
        else -> DependencyDetector.componentsFor(path).ifEmpty { if (path.isEmpty()) gog(name) else emptyList() }
    }

    /** An installer a store runs after an install (Amazon's PostInstall "Command") to its components. */
    fun installer(path: String): List<String> = if (UE_PREREQ.containsMatchIn(path)) listOf("vcredist2022", "d3dx9") else DependencyDetector.componentsFor(path)

    /** Steamworks Shared depot ids to (component, Steam's label). */
    fun steamDepots(ids: List<String>): List<Pair<String, String>> = ids.mapNotNull { SteamRedists.DEPOTS[it] }.distinctBy { it.first }

    // ---- what each store left at install ----

    /** Sidecar keys the store installs fill. */
    const val GOG_DEPENDENCIES = "dependencies"
    const val EPIC_PREREQ_PATH = "prereqPath"
    const val EPIC_PREREQ_NAME = "prereqName"
    /** Set beside the list when it was read whole (an empty list then means the store lists none). */
    const val LIST_READ = "listRead"

    /** fuel.json's PostInstall commands. */
    fun amazonPostInstall(folder: File): List<String> = runCatching {
        val arr = JSONObject(File(folder, "fuel.json").readText()).optJSONArray("PostInstall") ?: return emptyList()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("Command")?.takeIf { c -> c.isNotEmpty() } }
    }.getOrDefault(emptyList())

    // ---- per game ----

    /** Steam's list for [appId]: the local appmanifest's SharedDepots, else the client's app info. */
    fun steamList(context: Context, gameDir: File?, appId: Int, gameName: String, auto: Boolean): List<Finding> {
        val local = SteamRedists.detect(gameDir, appId)
        val pairs = if (local.isNotEmpty()) local.map { it.componentName to it.label }
        else SteamAppInfo.info(context, appId)?.let { steamDepots(it.sharedDepots) }.orEmpty()
        return pairs.map { (component, label) ->
            Finding(component, label, if (auto) Origin.STEAM else Origin.STEAM_NAME, auto, listOf(label, gameName, appId.toString()))
        }
    }

    /** Everything known for [game], blocking (files; the client's app info; no network). */
    fun forGame(context: Context, game: Library.SteamGame): List<Finding> {
        val folder = game.gameFiles
        val out = ArrayList<Finding>()
        val store = Store.byId(game.source)
        val sidecar = folder?.let { StoreGameSidecar.read(it) }
        when {
            game.library != Library.ADDED -> if (game.appId > 0) out += steamList(context, folder, game.appId, game.name, auto = true)
            store == Store.GOG -> sidecar?.extra?.get(GOG_DEPENDENCIES)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { id ->
                val parts = gog(id)
                if (parts.isEmpty()) out += Finding(null, id, Origin.GOG, false)
                parts.forEach { out += Finding(it, id, Origin.GOG, true) }
            }
            store == Store.EPIC -> {
                val path = sidecar?.extra?.get(EPIC_PREREQ_PATH).orEmpty()
                val name = sidecar?.extra?.get(EPIC_PREREQ_NAME).orEmpty()
                if (path.isNotEmpty() || name.isNotEmpty()) {
                    val parts = epic(path, name)
                    val original = name.ifEmpty { path.substringAfterLast('/') }
                    if (parts.isEmpty()) out += Finding(null, original, Origin.EPIC, false)
                    parts.forEach { out += Finding(it, original, Origin.EPIC, true) }
                }
            }
            store == Store.AMAZON -> folder?.let { amazonPostInstall(it) }?.forEach { command ->
                val parts = installer(command)
                val original = command.replace('\\', '/').substringAfterLast('/')
                if (parts.isEmpty()) out += Finding(null, original, Origin.AMAZON, false)
                parts.forEach { out += Finding(it, original, Origin.AMAZON, true) }
            }
            else -> {}
        }
        // A game added in DroidDeck (Custom, or a store's) that is a Steam app: Steam's list for it,
        // on by itself when its files say so, else only recommended.
        if (game.library == Library.ADDED && folder != null) SteamMatch.get(context, folder.path)?.let { match ->
            val id = match.appId ?: return@let
            when (match.certainty) {
                SteamMatch.Certainty.FILES -> out += steamList(context, folder, id, game.name, auto = true)
                SteamMatch.Certainty.NAME -> out += steamList(context, folder, id, game.name, auto = false)
                else -> {}
            }
        }
        // The folder scan, for every game: what it bundles, as Recommended.
        folder?.let { DependencyDetector.detect(it) }?.forEach { rec ->
            out += Finding(rec.componentName, rec.reason, Origin.FOLDER, false, listOf(rec.reason, rec.kind.name))
        }
        return out
    }
}
