package com.droiddeck.launcher.stores

import java.io.File

object CloudSavePaths {
    const val GOG_DEFAULT_NAMESPACE = "__default"

    fun profile(prefix: File): File = File(prefix, "drive_c/users/steamuser")

    fun gogGalaxySdkTemplate(clientId: String) =
        "%LocalAppData%/GOG.com/Galaxy/Applications/$clientId/Storage/Shared/Files"

    class GogCloudLocation(val name: String, val template: String)

    /**
     * cloudStorage.enabled with an empty locations list is Galaxy SDK IStorage, whose objects the
     * API names under a location called `__default`.
     */
    fun gogSaveFromRemote(enabled: Boolean, locations: List<Pair<String, String>>, clientId: String): GogCloudLocation? {
        if (!enabled) return null
        val named = locations.filter { it.second.isNotEmpty() }
        val pick = named.firstOrNull { it.first.equals("saves", true) } ?: named.firstOrNull()
        if (pick != null) return GogCloudLocation(pick.first, pick.second)
        if (clientId.isEmpty()) return null
        return GogCloudLocation(GOG_DEFAULT_NAMESPACE, gogGalaxySdkTemplate(clientId))
    }

    fun gog(template: String, prefix: File, install: File): File? {
        val norm = template.replace('\\', '/').trim().trimStart('/')
        val match = Regex("^(<\\?[^?>]+\\?>|%[^%]+%)").find(norm) ?: return null
        val token = match.groupValues[1].trim('<', '>', '?', '%').uppercase()
        val profile = profile(prefix)
        val (base, boundary) = when (token) {
            "INSTALL" -> install to install
            "SAVED_GAMES" -> File(profile, "Saved Games") to profile
            "DOCUMENTS" -> File(profile, "Documents") to profile
            "APPLICATION_DATA_LOCAL", "LOCALAPPDATA" -> File(profile, "AppData/Local") to profile
            "APPLICATION_DATA_LOCAL_LOW" -> File(profile, "AppData/LocalLow") to profile
            "APPLICATION_DATA_ROAMING", "APPDATA" -> File(profile, "AppData/Roaming") to profile
            "USERPROFILE" -> profile to profile
            "PROGRAMDATA" -> File(prefix, "drive_c/ProgramData").let { it to it }
            else -> return null
        }
        return walk(base, boundary, norm.substring(match.value.length), fuzzy = false)
    }

    fun epic(template: String, prefix: File, install: File, accountId: String, appName: String): File? {
        val parsed = com.droiddeck.launcher.stores.epic.EpicSaveTemplate.parse(template, accountId, appName) ?: return null
        val profile = profile(prefix)
        val (base, boundary) = when (parsed.root) {
            com.droiddeck.launcher.stores.epic.EpicSaveTemplate.Root.INSTALL_DIR -> install to install
            com.droiddeck.launcher.stores.epic.EpicSaveTemplate.Root.USER_PROFILE -> profile to profile
            com.droiddeck.launcher.stores.epic.EpicSaveTemplate.Root.DOCUMENTS -> File(profile, "Documents") to profile
            com.droiddeck.launcher.stores.epic.EpicSaveTemplate.Root.SAVED_GAMES -> File(profile, "Saved Games") to profile
            com.droiddeck.launcher.stores.epic.EpicSaveTemplate.Root.LOCAL_APPDATA -> File(profile, "AppData/Local") to profile
            com.droiddeck.launcher.stores.epic.EpicSaveTemplate.Root.ROAMING_APPDATA -> File(profile, "AppData/Roaming") to profile
        }
        return walk(base, boundary, parsed.rest, fuzzy = true)
    }

    private fun walk(base: File, boundary: File, rest: String, fuzzy: Boolean): File? {
        var dir = base
        for (seg in rest.trimStart('/').split('/').filter { it.isNotEmpty() && it != "." }) {
            if (seg == "..") { dir = dir.parentFile ?: return null; continue }
            val kids = dir.listFiles()
            val existing = kids?.firstOrNull { it.name.equals(seg, ignoreCase = true) }
                ?: if (fuzzy) kids?.filter { squash(it.name) == squash(seg) && squash(seg).isNotEmpty() }?.singleOrNull() else null
            dir = existing ?: File(dir, seg)
        }
        val bound = runCatching { boundary.canonicalPath }.getOrNull() ?: return null
        val dest = runCatching { dir.canonicalPath }.getOrNull() ?: return null
        return if (dest == bound || dest.startsWith(bound + File.separator)) dir else null
    }

    private fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
