package com.droiddeck.launcher.input

import java.util.UUID

enum class InputLayoutTemplate { CUSTOM, OVERLAY, DECK, TRACKPAD }

enum class InputLayoutScreen { MAIN, SECONDARY, BOTH;
    fun supports(role: InputRole) = role != InputRole.PHYSICAL && (this == BOTH ||
        if (role == InputRole.PRIMARY) this == MAIN else this == SECONDARY)
}

data class InputSurfaceStyle(val tint: Int? = ControllerPrefs.STEAM_BLUE, val opacity: Int = 100)

data class SavedInputLayout(
    val id: String, val name: String, val elements: List<InputSurfaceElement> = emptyList(),
    val template: InputLayoutTemplate = InputLayoutTemplate.CUSTOM,
    val style: InputSurfaceStyle = InputSurfaceStyle(),
    val screen: InputLayoutScreen = InputLayoutScreen.BOTH,
) {
    val editable get() = template == InputLayoutTemplate.CUSTOM
    val adaptiveSticks get() = elements.filter { it.kind == InputElementKind.STICK }.takeIf { editable && it.isNotEmpty() }?.all { it.floating && it.hideWhenIdle }
    fun supports(role: InputRole) = screen.supports(role) && when (template) {
        InputLayoutTemplate.OVERLAY -> role == InputRole.PRIMARY
        InputLayoutTemplate.DECK, InputLayoutTemplate.TRACKPAD -> role == InputRole.SECONDARY
        InputLayoutTemplate.CUSTOM -> true
    }
}

data class SavedInputBindings(
    val id: String, val name: String, val physical: Boolean,
    val layoutId: String? = null,
    val controls: Map<InputControl, InputAction> = emptyMap(),
    val elements: Map<String, InputAction> = emptyMap(),
    val keys: List<PhysicalInputBinding> = emptyList(),
    val clicks: Map<String, InputAction> = emptyMap(),
) {
    fun compatible(layout: SavedInputLayout?) = if (physical) layout == null
        else layout != null && if (layout.editable) layoutId == layout.id else layoutId == null

    fun action(element: InputSurfaceElement) = elements[element.id]
        ?: if (element.id == "legacy:${element.control.id}") controls[element.control] ?: InputAction.Native()
        else InputAction.Disabled

    fun clickAction(element: InputSurfaceElement) = clicks[element.id]
        ?: defaultElementClick(element, action(element)) { controls[it] ?: InputAction.Native() }
}

data class InputSelection(
    val profile: String = InputProfiles.AUTO, val parts: Map<InputRole, String> = emptyMap(), val profileSelected: Boolean = false,
)

data class InputPart(val bindings: String, val layout: String? = null)

data class SavedInputProfile(val id: String, val name: String, val base: String, val parts: Map<InputRole, InputPart>) {
    fun binding(role: InputRole) = parts.getValue(role).bindings
}

data class InputLibrary(
    val profiles: List<SavedInputProfile>, val layouts: List<SavedInputLayout>, val bindings: List<SavedInputBindings>,
    val selected: String = InputProfiles.AUTO,
    val steam: String = InputProfile.GAME, val desktop: String = InputProfile.DESKTOP,
    val selections: Map<String, InputSelection> = emptyMap(),
) {
    fun profile(id: String) = profiles.first { it.id == id }
    fun preset(id: String) = bindings.first { it.id == id }
    fun layout(profile: String, role: InputRole) = profiles.firstOrNull { it.id == profile }?.parts?.get(role)?.layout
        ?.let { id -> layouts.firstOrNull { it.id == id } }
    private fun mode(isDesktop: Boolean) = if (isDesktop) InputProfile.DESKTOP else InputProfile.GAME
    fun selection(isDesktop: Boolean) = selections[mode(isDesktop)] ?: InputSelection(selected)
    fun activeId(isDesktop: Boolean): String {
        val id = selection(isDesktop).profile
        return if (id == InputProfiles.AUTO) { if (isDesktop) desktop else steam } else id
    }
    fun selectProfile(isDesktop: Boolean, id: String) = copy(selections = selections + (mode(isDesktop) to InputSelection(id, profileSelected = true)))
    fun selectPart(isDesktop: Boolean, role: InputRole, bindingsId: String): InputLibrary {
        val previous = selection(isDesktop)
        return copy(selections = selections + (mode(isDesktop) to previous.copy(parts = previous.parts + (role to bindingsId))))
    }
    fun activePart(isDesktop: Boolean, role: InputRole): InputPart {
        val binding = selection(isDesktop).parts[role]?.let(::preset)
        return if (binding != null) InputPart(binding.id, binding.layoutId) else profile(activeId(isDesktop)).parts.getValue(role)
    }
    fun activeLayout(isDesktop: Boolean, role: InputRole) = activePart(isDesktop, role).layout?.let { id -> layouts.first { it.id == id } }
    fun compileActive(isDesktop: Boolean): InputProfile = compileParts(activeId(isDesktop), InputRole.entries.associateWith { preset(activePart(isDesktop, it).bindings) })

    fun saveSelection(isDesktop: Boolean, name: String, id: String = newInputId()): InputLibrary {
        val current = profile(activeId(isDesktop))
        var result = withProfile(SavedInputProfile(id, inputName(name), current.base, InputRole.entries.associateWith { activePart(isDesktop, it) }))
        for (role in InputRole.entries) result = result.usePart(id, role, id, shared = false)
        return result.selectProfile(isDesktop, id)
    }


    fun compile(id: String): InputProfile {
        val profile = profile(id)
        val parts = InputRole.entries.associateWith { preset(profile.binding(it)) }
        return compileParts(id, parts)
    }

    private fun compileParts(id: String, parts: Map<InputRole, SavedInputBindings>): InputProfile {
        return InputProfile(id, parts.mapValues { it.value.controls }.filterValues { it.isNotEmpty() },
            parts.getValue(InputRole.PHYSICAL).keys,
            parts.mapValues { it.value.elements }.filterValues { it.isNotEmpty() },
            parts.mapValues { it.value.clicks }.filterValues { it.isNotEmpty() })
    }

    fun usersOfBindings(id: String) = profiles.filter { p -> p.parts.values.any { it.bindings == id } }
    fun selectedBindings(id: String) = selections.values.any { id in it.parts.values }
    fun usersOfLayout(id: String) = profiles.filter { p -> p.parts.values.any { it.layout == id } }
    fun withProfile(value: SavedInputProfile) = copy(profiles = profiles.filterNot { it.id == value.id } + value)
    fun withPreset(value: SavedInputBindings) = copy(bindings = bindings.filterNot { it.id == value.id } + value)
    fun withLayout(value: SavedInputLayout) = copy(layouts = layouts.filterNot { it.id == value.id } + value,
        bindings = bindings.map { preset -> if (preset.layoutId != value.id) preset else preset.copy(
            elements = preset.elements.filter { (id, action) -> value.elements.any { it.id == id && action.validFor(it) } },
            clicks = preset.clicks.filter { (id, action) -> value.elements.any { it.id == id && it.hasClick && action.validForClick() } }) })

    fun duplicateProfile(id: String, name: String, newId: String = newInputId(), sharePhysical: Boolean = false): InputLibrary {
        require(profiles.size < 64)
        var result = withProfile(profile(id).copy(id = newId, name = inputName(name)))
        for (role in InputRole.entries) result = result.usePart(newId, role, id, shared = sharePhysical && role == InputRole.PHYSICAL)
        return result
    }

    fun usePart(profileId: String, role: InputRole, sourceId: String, shared: Boolean): InputLibrary {
        val target = profile(profileId)
        val source = profile(sourceId).parts.getValue(role)
        if (shared) return withProfile(target.copy(parts = target.parts + (role to source)))
        val sourceLayout = layout(sourceId, role)
        val name = (target.name + " / " + role.name.lowercase()).take(80)
        val copiedLayout = sourceLayout?.takeIf { it.editable }?.copy(id = newInputId(), name = name)
        val copiedBindings = preset(source.bindings).copy(id = newInputId(), name = name,
            layoutId = copiedLayout?.id ?: preset(source.bindings).layoutId)
        return (if (copiedLayout == null) this else withLayout(copiedLayout)).withPreset(copiedBindings)
            .withProfile(target.copy(parts = target.parts + (role to InputPart(copiedBindings.id, copiedLayout?.id ?: source.layout))))
    }

    fun useLayout(profileId: String, role: InputRole, layoutId: String, shared: Boolean): InputLibrary {
        val source = layouts.first { it.id == layoutId }
        require(source.supports(role))
        val target = profile(profileId)
        val layout = if (shared || !source.editable) source else source.copy(id = newInputId(), name = (target.name + " / " + source.name).take(80))
        val previous = preset(target.binding(role))
        val actions = if (previous.compatible(source)) previous else bindings.firstOrNull { it.compatible(source) } ?: SavedInputBindings(newInputId(), target.name, false,
            controls = if (source.editable) emptyMap() else InputProfile.defaults(target.base).bindings[role].orEmpty(),
            elements = if (source.editable) source.elements.associate { it.id to InputAction.Disabled } else emptyMap())
        val copied = actions.copy(id = newInputId(), name = (target.name + " / " + role.name.lowercase()).take(80),
            layoutId = layout.id.takeIf { layout.editable })
        return withLayout(layout).withPreset(copied)
            .withProfile(target.copy(parts = target.parts + (role to InputPart(copied.id, layout.id))))
    }

    fun copyBindings(id: String, name: String, profileId: String? = null, role: InputRole? = null, newId: String = newInputId()): InputLibrary {
        require(bindings.size < 512)
        var result = withPreset(preset(id).copy(id = newId, name = inputName(name)))
        if (profileId != null && role != null) result = result.chooseBindings(profileId, role, newId)
        return result
    }

    fun chooseBindings(profileId: String, role: InputRole, id: String): InputLibrary {
        val profile = profile(profileId)
        require(preset(id).compatible(layout(profileId, role)))
        return withProfile(profile.copy(parts = profile.parts + (role to profile.parts.getValue(role).copy(bindings = id))))
    }

    fun chooseLayout(profileId: String, role: InputRole, id: String): InputLibrary {
        val layout = layouts.first { it.id == id }
        require(layout.supports(role))
        val profile = profile(profileId)
        val previous = preset(profile.binding(role))
        val compatible = bindings.firstOrNull { it.id == previous.id && it.compatible(layout) }
            ?: bindings.firstOrNull { it.compatible(layout) }
            ?: SavedInputBindings(newInputId(), layout.name, false, if (layout.editable) layout.id else null)
        return withPreset(compatible).withProfile(profile.copy(parts = profile.parts + (role to InputPart(compatible.id, id))))
    }

    fun copyLayout(id: String, name: String, newId: String = newInputId()): InputLibrary {
        val source = layouts.first { it.id == id }
        require(source.editable)
        return withLayout(source.copy(id = newId, name = inputName(name)))
    }

    fun removeProfile(id: String): InputLibrary {
        require(id != InputProfile.GAME && id != InputProfile.DESKTOP)
        return copy(profiles = profiles.filterNot { it.id == id }, selected = selected.takeUnless { it == id } ?: InputProfiles.AUTO,
            steam = steam.takeUnless { it == id } ?: InputProfile.GAME, desktop = desktop.takeUnless { it == id } ?: InputProfile.DESKTOP,
            selections = selections.mapValues { (_, selection) -> if (selection.profile == id) selection.copy(profile = InputProfiles.AUTO) else selection })
    }

    fun removeLayout(id: String): InputLibrary {
        require(usersOfLayout(id).isEmpty() && bindings.none { it.layoutId == id && selectedBindings(it.id) } && layouts.first { it.id == id }.editable)
        return copy(layouts = layouts.filterNot { it.id == id }, bindings = bindings.filterNot { it.layoutId == id })
    }

    fun removeBindings(id: String): InputLibrary {
        require(usersOfBindings(id).isEmpty() && !selectedBindings(id))
        return copy(bindings = bindings.filterNot { it.id == id })
    }

    fun resetProfile(id: String): InputLibrary {
        val p = profile(id)
        var result = this
        val defaults = InputProfile.defaults(p.base)
        val parts = p.parts.mapValues { (role, part) ->
            val previous = preset(part.bindings)
            val isPrivate = usersOfBindings(part.bindings).all { it.id == id }
            val preset = SavedInputBindings(if (isPrivate) previous.id else newInputId(),
                if (isPrivate) previous.name else (p.name + " / " + role.name.lowercase()).take(80), role == InputRole.PHYSICAL,
                part.layout?.takeIf { layout(id, role)?.editable == true }, defaults.bindings[role].orEmpty(),
                elements = (layout(id, role)?.let { InputStockLayouts.actions(it) } ?: emptyMap()) + layout(id, role)?.elements.orEmpty().filter { it.id == "builtin:keyboard" }
                    .associate { it.id to InputAction.Command(InputCommand.KEYBOARD) })
            result = result.withPreset(preset)
            part.copy(bindings = preset.id)
        }
        return result.withProfile(p.copy(parts = parts))
    }

    fun validate(): InputLibrary {
        require(profiles.size in 2..64 && layouts.size in 3..256 && bindings.size in 1..512)
        fun unique(ids: List<String>) { require(ids.all { it.isNotBlank() && it.length <= 128 } && ids.distinct().size == ids.size) }
        unique(profiles.map { it.id }); unique(layouts.map { it.id }); unique(bindings.map { it.id })
        require(profiles.any { it.id == InputProfile.GAME } && profiles.any { it.id == InputProfile.DESKTOP })
        require(selected == InputProfiles.AUTO || profiles.any { it.id == selected })
        require(profiles.any { it.id == steam } && profiles.any { it.id == desktop })
        for (layout in layouts) {
            require(layout.style.opacity in 1..100)
            inputName(layout.name); require(layout.elements.size <= 64); unique(layout.elements.map { it.id })
            for (e in layout.elements) {
                inputName(e.name)
                require(e.icon == 0 || InputButtonIcons.has(e.icon))
                require(e.image.isEmpty() || e.image.matches(Regex("[A-Za-z0-9_.-]{1,128}")) && e.image != "." && e.image != "..")
                require(e.opacity.isFinite() && e.opacity in 0.01f..1f)
                require(!e.toggle || e.kind == InputElementKind.BUTTON || e.kind == InputElementKind.TRIGGER)
                require(listOf(e.x, e.y, e.width, e.height).all { it.isFinite() })
                require(e == e.bounded())
                require(if (e.id.startsWith("legacy:")) e.id == "legacy:${e.control.id}" && e.kind == InputElementKind.from(e.control)
                    else e.control == e.kind.control)
            }
        }
        for (preset in bindings) {
            inputName(preset.name)
            require(preset.controls.all { (c, a) -> a.validFor(c) })
            require(preset.elements.size <= 64 && preset.keys.size <= 64 && preset.clicks.size <= 64)
            require(preset.keys.map { Triple(it.key.device, it.key.keyCode, it.key.scanCode) }.distinct().size == preset.keys.size)
            require(preset.physical || preset.keys.isEmpty())
            val layout = preset.layoutId?.let { id -> layouts.first { it.id == id } }
            require(!preset.physical || layout == null && preset.elements.isEmpty() && preset.clicks.isEmpty())
            require(preset.elements.all { (id, action) -> layout?.elements?.firstOrNull { it.id == id }?.let { action.validFor(it) } == true })
            require(preset.clicks.all { (id, action) -> layout?.elements?.any { it.id == id && it.hasClick } == true && action.validForClick() })
            require(preset.keys.all { it.key.device.isNotBlank() && it.key.keyCode >= 0 && it.key.scanCode >= 0 &&
                it.action.validFor(InputControl.A) && (it.action !is InputAction.Native || it.action.target == null) })
        }
        require(selections.keys.all { it == InputProfile.GAME || it == InputProfile.DESKTOP })
        for (selection in selections.values) {
            require(selection.profile == InputProfiles.AUTO || profiles.any { it.id == selection.profile })
            for ((role, id) in selection.parts) {
                val binding = preset(id)
                require(binding.physical == (role == InputRole.PHYSICAL))
                val layout = binding.layoutId?.let { lid -> layouts.first { it.id == lid } }
                require(binding.compatible(layout) && (layout == null || layout.supports(role)))
            }
        }
        for (p in profiles) {
            inputName(p.name); require(p.base == InputProfile.GAME || p.base == InputProfile.DESKTOP)
            require(p.parts.keys == InputRole.entries.toSet())
            for ((role, part) in p.parts) {
                val layout = part.layout?.let { id -> layouts.first { it.id == id } }
                require(if (role == InputRole.PHYSICAL) layout == null else layout?.supports(role) == true)
                require(preset(part.bindings).compatible(layout))
            }
        }
        return this
    }
}

internal fun inputName(value: String): String = value.trim().also { require(it.isNotEmpty() && it.length <= 80) }
fun newInputId(): String = UUID.randomUUID().toString()

internal fun InputAction.validFor(element: InputSurfaceElement): Boolean = when {
    this !is InputAction.Native -> validFor(element.control)
    element.id.startsWith("legacy:") -> validFor(element.control)
    element.kind == InputElementKind.DPAD -> target == null
    target == null -> false
    element.kind == InputElementKind.TRACKPAD -> target.isTrackpad
    element.kind == InputElementKind.STICK -> target == InputControl.LS || target == InputControl.RS
    element.kind == InputElementKind.TRIGGER -> target == InputControl.LT || target == InputControl.RT
    else -> target.dimensions == 1 && target != InputControl.LT && target != InputControl.RT
}
