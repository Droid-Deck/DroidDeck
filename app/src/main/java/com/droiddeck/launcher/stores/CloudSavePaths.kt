/*
 * Cloud-save folder resolution for GOG and Epic games, after Bannerlator's GogCloudSavePaths and
 * EpicCloudSavePaths (device-proven there, August 2026): a store's save-location template expanded
 * against the game's Proton prefix and its install folder.
 */
package com.droiddeck.launcher.stores

import java.io.File

/**
 * Where a store keeps a game's saves on Windows, made concrete inside this game's Proton prefix.
 *
 * GOG gives a template per game in its public remote-config (`cloudStorage.locations[].location`):
 * a leading `<?TOKEN?>` (or `%TOKEN%`) and a path, e.g. ELDERBORN's
 * `<?APPLICATION_DATA_LOCAL_LOW?>/Hyperstrange/ELDERBORN`. Epic gives `CloudSaveFolder` in the
 * game's catalog attributes: a leading `{Token}`, with `{EpicId}` / `{AppName}` inside, e.g.
 * `{AppData}/Game/Saved/SaveGames`. Profile tokens map under the prefix's Wine user
 * (`drive_c/users/steamuser`), the install tokens to the game's folder. The path is walked against
 * what is on disk case-insensitively; a result outside its boundary is refused, never guessed.
 */
object CloudSavePaths {
    /** The Wine user every prefix has (Proton's). */
    fun profile(prefix: File): File = File(prefix, "drive_c/users/steamuser")

    /** Expands a GOG location template; null for an unknown token or an escape. */
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

    /** Expands an Epic CloudSaveFolder; null for an unknown token or an escape. */
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

    /**
     * Each segment matched against the folder's entries case-insensitively (and, for Epic, by letters
     * and digits alone when that names exactly one), else taken as written - a download creates it.
     */
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
