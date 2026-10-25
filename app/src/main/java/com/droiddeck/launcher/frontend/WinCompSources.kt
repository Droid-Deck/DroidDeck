package com.droiddeck.launcher.frontend

import android.content.Context
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.session.WinComponents
import org.json.JSONObject
import java.io.File
import java.util.Locale

object WinCompSources {
    enum class Origin { STEAM, GOG, EPIC, AMAZON, STEAM_NAME, FOLDER, XBOX }

    data class Finding(val component: String?, val original: String, val origin: Origin, val auto: Boolean, val args: List<String> = emptyList())


    private val VC_YEAR = mapOf(
        "2005" to "vcredist2005", "2008" to "vcredist2008", "2010" to "vcredist2010", "2012" to "vcredist2012",
        "2013" to "vcredist2013", "2015" to "vcredist2015", "2017" to "vcredist2015", "2019" to "vcredist2019", "2022" to "vcredist2022",
    )
    private val DOTNET = mapOf(
        "35" to "dotnet35", "40" to "dotnet40", "45" to "dotnet45", "451" to "dotnet452", "452" to "dotnet452",
        "46" to "dotnet46", "461" to "dotnet46", "462" to "dotnet46", "47" to "dotnet472", "471" to "dotnet472",
        "472" to "dotnet472", "48" to "dotnet48", "481" to "dotnet48",
    )

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

    private val UE_PREREQ = Regex("""(?i)ue\d?prereqsetup""")

    fun epic(path: String, name: String): List<String> = when {
        UE_PREREQ.containsMatchIn(path) || name.contains("UE4 Prerequisites", true) || name.contains("UE5 Prerequisites", true) ||
            name.contains("Unreal Engine Prerequisites", true) -> listOf("vcredist2022", "d3dx9")
        else -> DependencyDetector.componentsFor(path).ifEmpty { if (path.isEmpty()) gog(name) else emptyList() }
    }

    fun installer(path: String): List<String> = if (UE_PREREQ.containsMatchIn(path)) listOf("vcredist2022", "d3dx9") else DependencyDetector.componentsFor(path)

    private val XBOX_KNOWN = Regex("""<KnownDependency\s+Name="([A-Za-z0-9_.-]{1,40})"""")
    // VC14 also registers the ARM64 runtime: launchers running as ARM64EC look for it and stop when it is missing.
    private val XBOX_COMPONENTS = mapOf("VC14" to listOf("vcredist2022", WinComponents.VCREDIST_ARM64))

    /** Microsoft installs a PC Game Pass game's declared runtimes with it; VC14 is wanted by nearly all and is always added. */
    fun xbox(folder: File?): List<Pair<String, String>> {
        val config = folder?.listFiles().orEmpty().firstOrNull { it.isFile && it.name.equals("MicrosoftGame.config", ignoreCase = true) && it.length() < 1L shl 20 }
        val declared = config?.let { file -> runCatching { XBOX_KNOWN.findAll(file.readText()).map { it.groupValues[1] }.toList() }.getOrNull() }.orEmpty()
        return (listOf("VC14") + declared).flatMap { name -> XBOX_COMPONENTS[name].orEmpty().map { it to name } }.distinctBy { it.first }
    }

    fun steamDepots(ids: List<String>): List<Pair<String, String>> = ids.mapNotNull { SteamRedists.DEPOTS[it] }.distinctBy { it.first }


    const val GOG_DEPENDENCIES = "dependencies"
    const val EPIC_PREREQ_PATH = "prereqPath"
    const val EPIC_PREREQ_NAME = "prereqName"
    const val LIST_READ = "listRead"

    fun amazonPostInstall(folder: File): List<String> = runCatching {
        val arr = JSONObject(File(folder, "fuel.json").readText()).optJSONArray("PostInstall") ?: return emptyList()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("Command")?.takeIf { c -> c.isNotEmpty() } }
    }.getOrDefault(emptyList())


    fun steamList(context: Context, gameDir: File?, appId: Int, gameName: String, auto: Boolean): List<Finding> {
        val local = SteamRedists.detect(gameDir, appId)
        val pairs = if (local.isNotEmpty()) local.map { it.componentName to it.label }
        else SteamAppInfo.info(context, appId)?.let { steamDepots(it.sharedDepots) }.orEmpty()
        return pairs.map { (component, label) ->
            Finding(component, label, if (auto) Origin.STEAM else Origin.STEAM_NAME, auto, listOf(label, gameName, appId.toString()))
        }
    }

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
            store == Store.XBOX -> xbox(folder).forEach { (component, original) -> out += Finding(component, original, Origin.XBOX, true) }
            store == Store.AMAZON -> folder?.let { amazonPostInstall(it) }?.forEach { command ->
                val parts = installer(command)
                val original = command.replace('\\', '/').substringAfterLast('/')
                if (parts.isEmpty()) out += Finding(null, original, Origin.AMAZON, false)
                parts.forEach { out += Finding(it, original, Origin.AMAZON, true) }
            }
            else -> {}
        }
        if (game.library == Library.ADDED && folder != null) SteamMatch.get(context, folder.path)?.let { match ->
            val id = match.appId ?: return@let
            when (match.certainty) {
                SteamMatch.Certainty.FILES -> out += steamList(context, folder, id, game.name, auto = true)
                SteamMatch.Certainty.NAME -> out += steamList(context, folder, id, game.name, auto = false)
                else -> {}
            }
        }
        folder?.let { DependencyDetector.detect(it) }?.forEach { rec ->
            out += Finding(rec.componentName, rec.reason, Origin.FOLDER, false, listOf(rec.reason, rec.kind.name))
        }
        return out
    }
}
