package com.droiddeck.launcher.frontend

import java.io.File

object SteamRedists {
    val DEPOTS = linkedMapOf(
        "228981" to ("vcredist2005_dll" to "VC++ 2005"),
        "228982" to ("vcredist2008_dll" to "VC++ 2008"),
        "228983" to ("vcredist2010_dll" to "VC++ 2010"),
        "228984" to ("vcredist2012_dll" to "VC++ 2012"),
        "228985" to ("vcredist2013_dll" to "VC++ 2013"),
        "228986" to ("vcredist2015_dll" to "VC++ 2015"),
        "228987" to ("vcredist2019_dll" to "VC++ 2017"),
        "228988" to ("vcredist2019_dll" to "VC++ 2019"),
        "228989" to ("vcredist2022_dll" to "VC++ 2022"),
        "228990" to ("d3dx9" to "DirectX (June 2010)"),
    )
    private val BLOCK = Regex("\"SharedDepots\"\\s*\\{([^}]*)\\}", RegexOption.IGNORE_CASE)
    private val ENTRY = Regex("\"(\\d+)\"\\s+\"\\d+\"")

    fun manifest(gameDir: File?, appId: Int): File? {
        val steamapps = gameDir?.parentFile?.parentFile ?: return null
        return File(steamapps, "appmanifest_$appId.acf").takeIf { it.isFile }
    }

    fun detect(gameDir: File?, appId: Int): List<DependencyDetector.Recommendation> {
        val text = try {
            manifest(gameDir, appId)?.takeIf { it.length() < 1L shl 20 }?.readText() ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        val block = BLOCK.find(text)?.groupValues?.get(1) ?: return emptyList()
        return ENTRY.findAll(block).mapNotNull { DEPOTS[it.groupValues[1]] }
            .distinctBy { it.first }
            .map { (component, label) -> DependencyDetector.Recommendation(component, label, label, DependencyDetector.Kind.STEAM) }
            .toList()
    }
}
