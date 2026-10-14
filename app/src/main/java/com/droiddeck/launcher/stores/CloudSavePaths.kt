package com.droiddeck.launcher.stores

import java.io.File

object CloudSavePaths {
    fun profile(prefix: File): File = File(prefix, "drive_c/users/steamuser")

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
        val norm = template.replace('\\', '/').trim().trimStart('/')
        val match = Regex("^\\{([^}]+)\\}").find(norm) ?: return null
        val profile = profile(prefix)
        val (base, boundary) = when (match.groupValues[1].lowercase()) {
            "installdir" -> install to install
            "userprofile" -> profile to profile
            "userdir" -> File(profile, "Documents") to profile
            "usersavedgames" -> File(profile, "Saved Games") to profile
            "appdata", "localappdata" -> File(profile, "AppData/Local") to profile
            "roamingappdata" -> File(profile, "AppData/Roaming") to profile
            else -> return null
        }
        val rest = norm.substring(match.value.length)
            .replace("{epicid}", accountId, ignoreCase = true)
            .replace("{appname}", appName, ignoreCase = true)
        return walk(base, boundary, rest, fuzzy = true)
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
