package com.droiddeck.launcher.input

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionPrefs

/** The on-screen layout each game draws, shared by the in-game Controller page and Settings › Controller. A null game is the default. */
object ControlsChoice {
    const val NEW = "new"
    const val DEFAULT = "default"

    data class Game(val id: Long, val name: String, val layout: String)

    private fun own(library: InputLibrary, id: String?): String? = id?.takeIf { bindings ->
        library.bindings.any { it.id == bindings && !it.physical && it.layoutId?.let { l -> library.layouts.any { x -> x.id == l && x.supports(InputRole.PRIMARY) } } == true }
    }

    private fun own(context: Context, library: InputLibrary, game: Long?) = own(library, GameControls.bindings(context, game))

    /** The input library with [game]'s main-screen layout selected, without saving it. */
    fun library(context: Context, game: Long?, desktop: Boolean): InputLibrary {
        val library = InputLibraries.read(context)
        val chosen = own(context, library, game)
        return if (chosen == null) library else library.selectPart(desktop, InputRole.PRIMARY, chosen)
    }

    data class Option(val id: String, val name: String, val detail: String? = null, val deletable: Boolean = false)

    /** Layouts made for one game, so they're listed only for that game. Layouts made before that was recorded are recognised by carrying the name of the game that draws them. */
    private fun owners(context: Context, library: InputLibrary): Map<String, Set<Long>> {
        val named = GameControls.games(context).mapNotNull { game ->
            val layout = own(library, GameControls.bindings(context, game))?.let { library.preset(it).layoutId } ?: return@mapNotNull null
            val name = GameControls.name(context, game) ?: return@mapNotNull null
            (layout to game).takeIf { library.layouts.any { it.id == layout && it.name == name } }
        }
        return (named + GameControls.madeFor(context).toList()).groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
    }

    private fun defaults(library: InputLibrary) =
        listOf(false, true).mapNotNull { library.activeLayout(it, InputRole.PRIMARY)?.id }.toSet()

    private fun deletable(context: Context, library: InputLibrary, layout: SavedInputLayout): Boolean {
        if (!layout.editable || layout.id == InputLibraries.OVERLAY || layout.id in defaults(library)) return false
        if (GameControls.games(context).any { game -> own(library, GameControls.bindings(context, game))?.let { library.preset(it).layoutId } == layout.id }) return false
        return library.profiles.none { p -> p.parts.any { (role, part) -> part.layout == layout.id && drawn(library, p.id, role) } } &&
            library.bindings.none { it.layoutId == layout.id && library.selectedBindings(it.id) }
    }

    /** Whether [profile]'s [role] part is what a mode shows, rather than one a choice made since covers. */
    private fun drawn(library: InputLibrary, profile: String, role: InputRole) =
        listOf(false, true).any { desktop -> library.activeId(desktop) == profile && library.selection(desktop).parts[role] == null }

    private fun keeps(library: InputLibrary, profile: String) =
        profile == InputProfile.GAME || profile == InputProfile.DESKTOP || listOf(false, true).any { library.activeId(it) == profile }

    fun options(context: Context, game: Long?, steamMode: Boolean, gameName: String? = null, desktop: Boolean = false): List<Option> {
        val library = InputLibraries.read(context)
        val own = game != null && game in GameControls.games(context)
        val current = current(context, game, steamMode, desktop)
        val owners = owners(context, library)
        val defaults = defaults(library)
        val forGame = gameName?.takeIf { game != null && it.isNotBlank() }
        val newKitDetail = context.getString(R.string.osc_try_new_layout)
        fun detail(layout: SavedInputLayout): String? = when {
            OnScreenLayout.generated(layout) && current == InputLibraries.previousLayoutId(layout.id) -> newKitDetail
            layout.kitless && layout.id.startsWith("kept.") && layout.id != current -> context.getString(R.string.osc_restore_previous_layout)
            game != null && game in owners[layout.id].orEmpty() && layout.id !in defaults && forGame != null ->
                context.getString(R.string.osc_layout_detail_game, forGame)
            else -> null
        }
        val saved = library.layouts.filter { it.editable && it.supports(InputRole.PRIMARY) && it.id != InputLibraries.OVERLAY }
            .filter { l -> l.id == current || l.id in defaults || owners[l.id].isNullOrEmpty() || game in owners[l.id].orEmpty() }
            .map { l -> Option(l.id, l.name, detail(l), l.id != current && deletable(context, library, l)) }
        val stock = library.layouts.firstOrNull { it.id == InputLibraries.OVERLAY }
        return (if (own) listOf(Option(DEFAULT, context.getString(R.string.osc_default))) else emptyList()) +
            Option(InputLibraries.OVERLAY, context.getString(R.string.osc_layout_gamepad),
                stock?.let(::detail) ?: context.getString(R.string.osc_layout_gamepad_detail)) +
            (if (steamMode) listOf(Option(GameControls.STEAM, context.getString(R.string.osc_layout_steam), context.getString(R.string.osc_layout_steam_detail))) else emptyList()) +
            saved +
            (if (forGame != null) listOf(Option(NEW, context.getString(R.string.osc_layout_new_for, forGame))) else emptyList())
    }

    /** Whether [game] chose Steam's touch controls but runs where they can't be shown. */
    fun steamUnavailable(context: Context, game: Long?, steamMode: Boolean) = !steamMode && GameControls.steamLayout(context, game)

    /** Deletes the saved layout [id] and its actions. A profile that can no longer be chosen goes with it; one that can falls back to the stock layout. */
    fun delete(context: Context, id: String) {
        val games = GameControls.games(context).mapNotNull { GameControls.bindings(context, it) }.toSet()
        InputLibraries.update(context) { library ->
            val layout = library.layouts.first { it.id == id }
            require(deletable(context, library, layout))
            val users = library.usersOfLayout(id)
            val orphans = users.filterNot { keeps(library, it.id) }
            var result = users.filter { keeps(library, it.id) }.fold(library) { acc, p ->
                p.parts.filterValues { it.layout == id }.keys.fold(acc) { lib, role ->
                    lib.chooseLayout(p.id, role, when {
                        role == InputRole.PRIMARY -> InputLibraries.OVERLAY
                        p.base == InputProfile.DESKTOP -> InputLibraries.TRACKPAD
                        else -> InputLibraries.DECK
                    })
                }
            }
            result = orphans.fold(result) { acc, p -> acc.removeProfile(p.id) }
            val loose = orphans.flatMap { p -> p.parts.values.map { it.bindings } }.toSet()
                .filter { b -> b !in games && result.usersOfBindings(b).isEmpty() && !result.selectedBindings(b) && result.bindings.any { it.id == b } }
                .filterNot { b -> InputStockLayouts.presets(InputStockLayouts.layouts()).any { it.id == b } }
            result = loose.fold(result) { acc, b -> acc.removeBindings(b) }
            result.removeLayout(id)
        }
        GameControls.forgetLayout(context, id)
    }

    fun current(context: Context, game: Long?, steamMode: Boolean, desktop: Boolean = false): String =
        if (steamMode && GameControls.steamLayout(context, game)) GameControls.STEAM
        else library(context, game, desktop).activeLayout(desktop, InputRole.PRIMARY)?.id ?: InputLibraries.OVERLAY

    fun choose(context: Context, game: Long?, id: String, desktop: Boolean, gameName: String? = null) {
        require(id != NEW)
        if (id == DEFAULT) { if (game != null) useDefault(context, game); return }
        val steam = id == GameControls.STEAM
        val bindings = if (steam) null else {
            val library = InputLibraries.read(context)
            val layout = library.layouts.first { it.id == id }
            val available = library.bindings.filter { !it.physical && it.layoutId == layout.id && it.compatible(layout) }
            val preferred = listOfNotNull(own(context, library, game), library.activePart(desktop, InputRole.PRIMARY).bindings,
                library.profile(library.activeId(desktop)).binding(InputRole.PRIMARY))
            preferred.firstNotNullOfOrNull { id -> relatedPreset(library, available, id, layout.id) }
                ?: available.firstOrNull()?.id ?: newInputId().also { newId ->
                InputLibraries.update(context) { it.withPreset(SavedInputBindings(newId, layout.name, false, layout.id)) }
            }
        }
        if (game != null) GameControls.choose(context, game, bindings, steam, gameName)
        else {
            if (bindings != null) InputLibraries.update(context) { it.selectPart(desktop, InputRole.PRIMARY, bindings) }
            SessionPrefs.setSteamTouchLayout(context, steam)
        }
    }

    private fun relatedPreset(library: InputLibrary, available: List<SavedInputBindings>, id: String, target: String): String? {
        val from = library.preset(id).layoutId ?: return null
        return available.firstOrNull { candidate ->
            candidate.id == id ||
                target == InputLibraries.previousLayoutId(from) && candidate.id == InputLibraries.previousBindingsId(id, target) ||
                from == InputLibraries.previousLayoutId(target) && id == InputLibraries.previousBindingsId(candidate.id, from)
        }?.id
    }

    /** Copies the layout [game] draws now, actions included, into a layout of its own and chooses it for that game only. */
    fun newForGame(context: Context, game: Long, name: String, desktop: Boolean): Pair<String, String> {
        val layoutId = newInputId()
        val bindingsId = newInputId()
        InputLibraries.update(context) { latest -> copyForGame(context, latest, game, name, desktop, layoutId, bindingsId) }
        GameControls.markMadeFor(context, layoutId, game)
        GameControls.choose(context, game, bindingsId, steam = false, name = name)
        return layoutId to bindingsId
    }

    /** [latest] with a copy of the layout [game] draws now, actions included, under the given ids. Nothing is chosen for the game. */
    fun copyForGame(context: Context, latest: InputLibrary, game: Long, name: String, desktop: Boolean, layoutId: String, bindingsId: String): InputLibrary {
        run {
            val chosen = own(context, latest, game)
            val part = (if (chosen == null) latest else latest.selectPart(desktop, InputRole.PRIMARY, chosen)).activePart(desktop, InputRole.PRIMARY)
            val source = latest.layouts.firstOrNull { it.id == part.layout && it.editable } ?: latest.layouts.first { it.id == InputLibraries.OVERLAY }
            val actions = latest.bindings.firstOrNull { it.id == part.bindings && it.compatible(source) }
                ?: latest.bindings.firstOrNull { !it.physical && it.compatible(source) }
                ?: SavedInputBindings(bindingsId, source.name, false, source.id)
            val title = name.trim().take(80).ifEmpty { source.name }
            return latest.withLayout(source.derived(layoutId, title)).withPreset(actions.copy(id = bindingsId, name = title, layoutId = layoutId))
        }
    }

    /** Uses [bindingsId], made by [copyForGame], for [game] only. */
    fun useForGame(context: Context, game: Long, name: String, bindingsId: String) {
        InputLibraries.read(context).bindings.firstOrNull { it.id == bindingsId }?.layoutId?.let { GameControls.markMadeFor(context, it, game) }
        GameControls.choose(context, game, bindingsId, steam = false, name = name)
    }

    /** The profile, layout and actions the editor opens for [game], or null when its layout can't be edited here. */
    fun editTarget(context: Context, game: Long?, desktop: Boolean): Triple<String, String, String>? {
        val library = library(context, game, desktop)
        val layout = library.activeLayout(desktop, InputRole.PRIMARY)?.takeIf { it.editable } ?: return null
        return Triple(library.activeId(desktop), layout.id, library.activePart(desktop, InputRole.PRIMARY).bindings)
    }

    /** Whether [game] has a layout of its own, rather than the default. */
    fun hasOwn(context: Context, game: Long?): Boolean = own(context, InputLibraries.read(context), game) != null

    /** Whether saving an edit of [game]'s layout would also change the default or another game. */
    fun shared(context: Context, game: Long?): Boolean {
        game ?: return false
        val library = InputLibraries.read(context)
        val bindings = own(context, library, game) ?: return true
        val layout = library.preset(bindings).layoutId
        fun drawsLayout(id: String) = library.bindings.firstOrNull { it.id == id }?.layoutId == layout
        return library.usersOfLayout(layout ?: return true).isNotEmpty() ||
            library.selections.values.any { s -> s.parts[InputRole.PRIMARY]?.let(::drawsLayout) == true } ||
            GameControls.games(context).any { it != game && own(context, library, it)?.let(::drawsLayout) == true }
    }

    fun useDefault(context: Context, game: Long) = GameControls.clear(context, game)

    /** Games with a layout of their own, by name. */
    fun games(context: Context): List<Game> {
        val library = InputLibraries.read(context)
        return GameControls.games(context).mapNotNull { id ->
            val layout = when {
                GameControls.steamLayout(context, id) -> context.getString(R.string.osc_layout_steam)
                else -> own(context, library, id)?.let { b -> library.layouts.firstOrNull { it.id == library.preset(b).layoutId }?.name } ?: return@mapNotNull null
            }
            val name = GameControls.name(context, id)
                ?: id.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.let { runCatching { SteamTouchConfig.gameName(context, it.toInt()) }.getOrNull() }
                ?: id.toString()
            Game(id, name, layout)
        }.sortedBy { it.name.lowercase() }
    }
}
