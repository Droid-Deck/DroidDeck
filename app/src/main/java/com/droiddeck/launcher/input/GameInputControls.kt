package com.droiddeck.launcher.input

import android.content.Context

object GameInputControls {
    private fun prefs(context: Context) = context.getSharedPreferences("input-game-controls", Context.MODE_PRIVATE)
    fun profile(context: Context, game: String?): String? = game?.let { prefs(context).getString(it, null) }
    fun set(context: Context, game: String, profile: String?) {
        require(game.isNotBlank() && game.length <= 512)
        require(profile == null || InputLibraries.read(context).profiles.any { it.id == profile })
        val edit = prefs(context).edit()
        if (profile == null) edit.remove(game) else edit.putString(game, profile)
        check(edit.commit()) { "Could not save game controls." }
    }
    fun resolve(context: Context, library: InputLibrary, desktop: Boolean, game: String?): InputLibrary {
        val selection = library.selection(desktop)
        if (game == null || selection.profile != InputProfiles.AUTO) return library
        val target = profile(context, game)?.let { id -> library.profiles.firstOrNull { it.id == id } } ?: return library
        var result = library
        for (role in listOf(InputRole.PRIMARY, InputRole.SECONDARY)) {
            if (role !in selection.parts) result = result.selectPart(desktop, role, target.binding(role))
        }
        return result
    }
}
