package com.droiddeck.launcher.stores.epic

object EpicSaveTemplate {
    enum class Root { INSTALL_DIR, USER_PROFILE, DOCUMENTS, SAVED_GAMES, LOCAL_APPDATA, ROAMING_APPDATA }

    class Parsed(val root: Root, val rest: String)

    private val ROOTS = mapOf(
        "installdir" to Root.INSTALL_DIR,
        "userprofile" to Root.USER_PROFILE,
        "userdir" to Root.DOCUMENTS,
        "usersavedgames" to Root.SAVED_GAMES,
        "appdata" to Root.LOCAL_APPDATA,
        "localappdata" to Root.LOCAL_APPDATA,
        "roamingappdata" to Root.ROAMING_APPDATA,
    )

    private val LEAD = Regex("^\\{([A-Za-z]+)\\}")
    private val EPIC_ID = Regex("\\{EpicId\\}", RegexOption.IGNORE_CASE)
    private val APP_NAME = Regex("\\{AppName\\}", RegexOption.IGNORE_CASE)

    fun parse(template: String, accountId: String, appName: String): Parsed? {
        val norm = template.replace('\\', '/').trim()
        val match = LEAD.find(norm) ?: return null
        val root = ROOTS[match.groupValues[1].lowercase()] ?: return null
        val rest = norm.substring(match.value.length)
            .replace(EPIC_ID) { accountId }
            .replace(APP_NAME) { appName }
        return Parsed(root, rest)
    }
}
