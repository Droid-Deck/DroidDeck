package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.AddedGameArt
import com.droiddeck.launcher.frontend.AddedGameEdits
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.frontend.LogoPosition
import com.droiddeck.launcher.frontend.SteamGameArt
import com.droiddeck.launcher.frontend.SteamGridDb
import com.droiddeck.launcher.session.SessionPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun AddedGameArt.Slot.shape(): Triple<Dp, Dp, Dp> = when (this) {
    AddedGameArt.Slot.COVER -> Triple(28.dp, 41.dp, 5.dp)
    AddedGameArt.Slot.WIDE -> Triple(60.dp, 28.dp, 5.dp)
    AddedGameArt.Slot.BACKGROUND -> Triple(60.dp, 34.dp, 5.dp)
    AddedGameArt.Slot.LOGO -> Triple(60.dp, 26.dp, 5.dp)
    AddedGameArt.Slot.ICON -> Triple(30.dp, 30.dp, 7.dp)
}

@Composable
private fun AddedGameArt.Slot.label(): String = stringResource(
    when (this) {
        AddedGameArt.Slot.COVER -> R.string.art_cover
        AddedGameArt.Slot.WIDE -> R.string.art_wide_cover
        AddedGameArt.Slot.BACKGROUND -> R.string.art_background
        AddedGameArt.Slot.LOGO -> R.string.art_logo
        AddedGameArt.Slot.ICON -> R.string.art_icon
    },
)

@Composable
private fun artSourceLabel(source: String): String = stringResource(
    when (source) {
        AddedGameArt.FOLDER -> R.string.art_src_folder
        AddedGameArt.STEAM -> R.string.art_src_steam
        AddedGameArt.SGDB -> R.string.art_src_sgdb
        AddedGameArt.FILE -> R.string.art_src_file
        AddedGameArt.EXE -> R.string.art_src_exe
        else -> R.string.art_src_auto
    },
)

@Composable
private fun SlotThumb(slot: AddedGameArt.Slot, model: Any?, scale: Float = 1f, selected: Boolean = false) {
    val pal = LocalPalette.current
    val (w, h, r) = slot.shape()
    val shape = RoundedCornerShape(r)
    Box(
        Modifier.size(w * scale, h * scale).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)
            .border(if (selected) 2.dp else 1.dp, if (selected) pal.signal else pal.line, shape),
    ) {
        if (model != null) AsyncImage(
            model = model, contentDescription = null,
            contentScale = if (slot == AddedGameArt.Slot.LOGO || slot == AddedGameArt.Slot.ICON) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
    }
}

@Composable
private fun FieldRow(label: String, focus: FocusRequester? = null, onClick: () -> Unit, value: @Composable () -> Unit) {
    ListRow(focus = focus, onClick = onClick) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(110.dp))
        Box(Modifier.weight(1f)) { value() }
    }
}

internal class EditRequest(val folder: String, val openTarget: Boolean, val fromSummary: Boolean, val preview: EditPreview)

internal class EditPreview(
    val name: String, val exeName: String?, val startIn: String?, val launchOptions: String,
    val art: Map<AddedGameArt.Slot, File?>,
) {
    companion object {
        fun of(context: android.content.Context, game: com.droiddeck.launcher.frontend.Library.SteamGame, folder: String): EditPreview {
            val exe = SessionPrefs.addedGameExe(context, folder).ifEmpty {
                com.droiddeck.launcher.frontend.AddedExes.list(context).firstOrNull { it.folder == folder && it.picked }?.exe.orEmpty()
            }.takeIf { it.isNotEmpty() }?.let { File(it) }
            val startIn = SessionPrefs.addedGameStartIn(context, folder).takeIf { it.isNotEmpty() }?.substringAfterLast('/') ?: exe?.parentFile?.name
            return EditPreview(
                game.name, exe?.name, startIn, SessionPrefs.addedGameLaunch(context, folder),
                mapOf(AddedGameArt.Slot.COVER to game.art, AddedGameArt.Slot.BACKGROUND to game.hero, AddedGameArt.Slot.ICON to game.icon),
            )
        }

        fun of(game: AddedGames.Game, cover: File?) = EditPreview(
            game.name, game.exe.name, game.guestDir.substringAfterLast('/'), game.launchOptions, mapOf(AddedGameArt.Slot.COVER to cover),
        )
    }
}

internal class ArtTarget(
    val name: String,
    val chosenRef: (Context, AddedGameArt.Slot) -> String,
    val choices: (Context, AddedGameArt.Slot) -> AddedGameArt.Choices,
    val set: (Context, AddedGameArt.Slot, AddedGameArt.Option) -> Boolean,
    val reset: (Context, AddedGameArt.Slot) -> Unit,
    val position: (Context) -> LogoPosition.Value?,
    val setPosition: (Context, LogoPosition.Value?) -> Boolean,
) {
    companion object {
        fun of(game: AddedGames.Game) = ArtTarget(
            game.name,
            { c, slot -> AddedGameArt.chosenSource(c, game, slot).second },
            { c, slot -> AddedGameArt.choices(c, game, slot) },
            { c, slot, option -> AddedGameEdits.setArt(c, game.folder.path, slot, option) },
            { c, slot -> AddedGameEdits.resetArt(c, game.folder.path, slot) },
            { c -> AddedGameEdits.logoPosition(c, game.folder.path) },
            { c, value -> AddedGameEdits.setLogoPosition(c, game.folder.path, value) },
        )

        fun of(game: com.droiddeck.launcher.frontend.Library.SteamGame) = ArtTarget(
            game.name,
            { c, slot -> SteamGameArt.chosenSource(c, game.appId, slot).second },
            { c, slot -> SteamGameArt.choices(c, game, slot) },
            { c, slot, option -> SteamGameArt.choose(c, game, slot, option) },
            { c, slot -> SteamGameArt.reset(c, game, slot) },
            { c -> SteamGameArt.position(c, game) },
            { c, value -> SteamGameArt.setPosition(c, game, value) },
        )
    }
}

@Composable
internal fun SteamGameArtEditor(
    game: com.droiddeck.launcher.frontend.Library.SteamGame, onClose: () -> Unit, onChanged: () -> Unit,
) {
    val target = remember(game.appId) { ArtTarget.of(game) }
    ArtOnlyEditor(game.name, target, game, { SteamGameArt.describe(it, game) }, onClose, onChanged)
}

@Composable
internal fun StoreGameArtEditor(folder: String, name: String, onClose: () -> Unit, onChanged: () -> Unit) {
    val app = LocalContext.current.applicationContext
    var game by remember(folder) { mutableStateOf<AddedGames.Game?>(null) }
    LaunchedEffect(folder) { game = withContext(Dispatchers.IO) { AddedGameEdits.game(app, folder) } }
    val g = game
    val target = remember(g?.appId) { g?.let { ArtTarget.of(it) } }
    ArtOnlyEditor(name, target, g, { c -> g?.let { AddedGameArt.describe(c, it) }.orEmpty() }, onClose, onChanged)
}

@Composable
private fun ArtOnlyEditor(
    name: String, target: ArtTarget?, refresh: Any?, describe: (Context) -> List<AddedGameArt.SlotState>,
    onClose: () -> Unit, onChanged: () -> Unit,
) {
    val app = LocalContext.current.applicationContext
    val colors = MaterialTheme.colorScheme
    val shown = rememberShown(onClose)
    val close = { shown.targetState = false }
    var version by remember { mutableIntStateOf(0) }
    var arts by remember { mutableStateOf<List<AddedGameArt.SlotState>>(emptyList()) }
    var artSlot by remember { mutableStateOf<AddedGameArt.Slot?>(null) }
    var logoOpen by remember { mutableStateOf(false) }
    val position = rememberLogoPosition(target, version)
    LaunchedEffect(version, refresh) { if (target != null) arts = withContext(Dispatchers.IO) { describe(app) } }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusWithinFrames({ false }) { first } }
    AppDialog(shown, close, "artEditor", wide = false, maxWidth = 460.dp) {
        DialogTitleRow(name, onClose = close)
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.72f).dp
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
        ) {
            Text(stringResource(R.string.edit_artwork), fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
            for (slot in AddedGameArt.Slot.values()) key(slot) {
                val state = arts.firstOrNull { it.slot == slot }
                FieldRow(slot.label(), focus = if (slot == AddedGameArt.Slot.COVER) first else null, onClick = { if (target != null) artSlot = slot }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SlotThumb(slot, state?.file)
                        if (state != null) ValueText(artSourceLabel(state.source), muted = true) else Loading()
                    }
                }
            }
            PickerRow(Icons.Outlined.Tune, stringResource(R.string.art_adjust_logo), logoPositionSummary(position?.value), enabled = target != null && position != null) { logoOpen = true }
        }
    }
    val chosen = target ?: return
    artSlot?.let { slot ->
        ArtChooserDialog(chosen, slot, arts.firstOrNull { it.slot == slot }, onBack = { artSlot = null }, onCloseAll = { artSlot = null; close() }) { version++; onChanged() }
    }
    val loaded = position
    if (logoOpen && loaded != null) LogoPositionHost(chosen, arts, loaded, onBack = { logoOpen = false }, onCloseAll = { logoOpen = false; close() }) { version++; onChanged() }
}

/** The position as last read; null until it has been read, so the screen never opens on a guess and saves it over the real one. */
private class LoadedPosition(val value: LogoPosition.Value?)

@Composable
private fun rememberLogoPosition(target: ArtTarget?, version: Int): LoadedPosition? {
    val app = LocalContext.current.applicationContext
    var position by remember(target) { mutableStateOf<LoadedPosition?>(null) }
    LaunchedEffect(target, version) {
        position = if (target == null) null else withContext(Dispatchers.IO) { LoadedPosition(runCatching { target.position(app) }.getOrNull()) }
    }
    return position
}

/** The "Adjust logo" screen for [target], over the game's background and logo as they are now. */
@Composable
private fun LogoPositionHost(
    target: ArtTarget, arts: List<AddedGameArt.SlotState>, start: LoadedPosition,
    onBack: () -> Unit, onCloseAll: () -> Unit, onChanged: () -> Unit,
) {
    fun file(slot: AddedGameArt.Slot) = arts.firstOrNull { it.slot == slot }?.file
    LogoPositionDialog(
        target, hero = file(AddedGameArt.Slot.BACKGROUND) ?: file(AddedGameArt.Slot.WIDE), logo = file(AddedGameArt.Slot.LOGO), start = start.value,
        onBack = onBack, onCloseAll = onCloseAll, onChanged = onChanged,
    )
}

@Composable
private fun Loading() {
    androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
}

@Composable
internal fun AddedGameEditor(
    request: EditRequest, onBack: () -> Unit, onClose: () -> Unit, onChanged: () -> Unit,
    onRemove: (folder: String, appId: Long?, name: String) -> Unit,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val shown = rememberShown(onClose)
    val close = { shown.targetState = false }
    val folder = request.folder
    var version by remember { mutableIntStateOf(0) }
    var game by remember { mutableStateOf<AddedGames.Game?>(null) }
    var arts by remember { mutableStateOf<List<AddedGameArt.SlotState>>(emptyList()) }
    var ranked by remember { mutableStateOf<List<File>?>(null) }
    var targetOpen by remember { mutableStateOf(request.openTarget) }
    var armed by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var otherExe by remember { mutableStateOf(false) }
    var artSlot by remember { mutableStateOf<AddedGameArt.Slot?>(null) }
    var logoOpen by remember { mutableStateOf(false) }
    val artTarget = remember(game?.appId) { game?.let { ArtTarget.of(it) } }
    val position = rememberLogoPosition(artTarget, version)
    var unreachable by remember { mutableStateOf(false) }
    LaunchedEffect(version) {
        withContext(Dispatchers.IO) {
            val g = AddedGameEdits.game(app, folder)
            game = g
            if (g != null) {
                ranked = g.candidates.let { list -> if (list.any { it.path == g.exe.path }) list else listOf(g.exe) + list }
                arts = AddedGameArt.describe(app, g)
            }
        }
    }
    LaunchedEffect(armed) { if (armed) { kotlinx.coroutines.delay(4_000); armed = false } }
    val change: (() -> Unit) -> Unit = { work ->
        scope.launch {
            withContext(Dispatchers.IO) { work() }
            version++
            onChanged()
        }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusWithinFrames({ false }) { first } }
    val preview = request.preview
    AppDialog(shown, close, "gameEditor", wide = false, maxWidth = 460.dp) {
        val g = game
        val name = g?.name ?: preview.name
        DialogTitleRow(
            name, onClose = close,
            leading = if (request.fromSummary) ({ SmallIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), colors.onBackground) { onBack(); close() } }) else null,
        )
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.72f).dp
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
        ) {
            FieldRow(stringResource(R.string.edit_name), focus = first, onClick = { editing = "name" }) { ValueText(name) }
            FieldRow(stringResource(R.string.edit_target), onClick = { targetOpen = !targetOpen }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val exeName = g?.exe?.name ?: preview.exeName
                    Box(Modifier.weight(1f)) { if (exeName != null) ValueText(exeName) else Loading() }
                    if (g == null && exeName != null) Loading()
                    if (g?.exeUncertain == true) UncertainMark(stringResource(R.string.mode_added_exe_check))
                }
            }
            if (targetOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 16.dp)) {
                    val list = ranked
                    if (g == null || list == null) Box(Modifier.padding(8.dp)) { Loading() }
                    else for (exe in list) key(exe.path) {
                        val rel = exe.relativeToOrSelf(g.folder).invariantSeparatorsPath
                        PickerRow(null, exe.name, rel.substringBeforeLast('/', "").ifEmpty { null }, accent = exe.path == g.exe.path) {
                            targetOpen = false
                            change { AddedGameEdits.setExe(app, folder, exe) }
                        }
                    }
                    PickerRow(null, stringResource(R.string.edit_other_exe), null, enabled = g != null) { otherExe = true }
                }
            }
            FieldRow(stringResource(R.string.edit_start_in), onClick = { if (g != null) editing = "start" }) {
                val startIn = g?.guestDir?.substringAfterLast('/') ?: preview.startIn
                if (startIn != null) ValueText(startIn) else Loading()
            }
            val launch = g?.launchOptions ?: preview.launchOptions
            FieldRow(stringResource(R.string.edit_launch_options), onClick = { editing = "launch" }) { ValueText(launch.ifEmpty { "—" }, muted = launch.isEmpty()) }
            Text(stringResource(R.string.edit_artwork), fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
            for (slot in AddedGameArt.Slot.values()) key(slot) {
                val state = arts.firstOrNull { it.slot == slot }
                FieldRow(slot.label(), onClick = { if (g != null) artSlot = slot }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SlotThumb(slot, if (state != null) state.file else preview.art[slot])
                        if (state != null) ValueText(artSourceLabel(state.source), muted = true) else Loading()
                    }
                }
            }
            PickerRow(Icons.Outlined.Tune, stringResource(R.string.art_adjust_logo), logoPositionSummary(position?.value), enabled = g != null && position != null) { logoOpen = true }
            RemoveRow(armed) {
                if (!armed) armed = true else {
                    armed = false
                    onRemove(folder, game?.appId, game?.name ?: preview.name)
                    if (request.fromSummary) onBack()
                    close()
                }
            }
        }
        if (unreachable) Text(stringResource(R.string.games_add_unreachable), fontSize = 12.sp, color = colors.error)
    }
    val shownName = game?.name ?: preview.name
    when (editing) {
        "name" -> TextEntryDialog(stringResource(R.string.edit_name), SessionPrefs.addedGameName(ctx, folder).ifEmpty { shownName }, onDismiss = { editing = null }) { value ->
            change { AddedGameEdits.setName(app, folder, if (value == shownName && SessionPrefs.addedGameName(app, folder).isEmpty()) "" else value) }
        }
        "launch" -> TextEntryDialog(stringResource(R.string.edit_launch_options), game?.launchOptions ?: preview.launchOptions, onDismiss = { editing = null }) { value ->
            change { AddedGameEdits.setLaunchOptions(app, folder, value) }
        }
    }
    val g = game ?: return
    when (editing) {
        "start" -> TextEntryDialog(stringResource(R.string.edit_start_in), hostPath(ctx, g), onDismiss = { editing = null }) { value ->
            scope.launch {
                val ok = withContext(Dispatchers.IO) { AddedGameEdits.setStartIn(app, folder, if (value == g.exe.parentFile?.path) "" else value) }
                unreachable = !ok
                version++
                onChanged()
            }
        }
    }
    if (otherExe) ExePickerDialog(stringResource(R.string.games_pick_exe_title), start = g.folder, onDismiss = { otherExe = false }, onPick = { exe ->
        targetOpen = false
        scope.launch {
            val ok = withContext(Dispatchers.IO) { AddedGameEdits.setExe(app, folder, exe) }
            unreachable = !ok
            version++
            onChanged()
        }
    })
    artSlot?.let { slot ->
        val current = arts.firstOrNull { it.slot == slot }
        ArtChooserDialog(ArtTarget.of(g), slot, current, onBack = { artSlot = null }, onCloseAll = { artSlot = null; close() }) { version++; onChanged() }
    }
    val logoTarget = artTarget
    val loaded = position
    if (logoOpen && logoTarget != null && loaded != null) LogoPositionHost(logoTarget, arts, loaded, onBack = { logoOpen = false }, onCloseAll = { logoOpen = false; close() }) { version++; onChanged() }
}

private fun hostPath(context: android.content.Context, game: AddedGames.Game): String {
    val folderGuest = AddedGames.guestPath(context, game.folder) ?: return game.exe.parentFile?.path.orEmpty()
    return when {
        game.guestDir == folderGuest -> game.folder.path
        game.guestDir.startsWith("$folderGuest/") -> game.folder.path + game.guestDir.removePrefix(folderGuest)
        else -> game.exe.parentFile?.path.orEmpty()
    }
}

@Composable
private fun ValueText(text: String, muted: Boolean = false) {
    Text(
        text, fontSize = 14.sp, color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RemoveRow(armed: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hot = rememberHot(src)
    Box(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(Shape12)
            .background(if (armed) colors.error.copy(alpha = 0.18f) else if (hot) colors.error.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
            .glideBorder(hot, Shape12, colors.error, colors.error.copy(alpha = 0.4f))
            .hoverable(src)
            .clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            stringResource(if (armed) R.string.mode_added_remove_confirm else R.string.edit_remove),
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.error,
        )
    }
}

@Composable
internal fun TextEntryDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    var value by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusWithinFrames({ false }) { focus } }
    AppDialog(shown, close, "textEntry", wide = false, maxWidth = 460.dp) {
        DialogTitleRow(title, onClose = close)
        androidx.compose.material3.OutlinedTextField(
            value, { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onSave(value.trim()); close() }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SecondaryButton(stringResource(R.string.common_ok), compact = true) { onSave(value.trim()); close() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArtChooserDialog(
    target: ArtTarget, slot: AddedGameArt.Slot, current: AddedGameArt.SlotState?,
    onBack: () -> Unit, onCloseAll: () -> Unit, onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val shown = rememberShown(onBack)
    val back = { shown.targetState = false }
    var options by remember { mutableStateOf<List<AddedGameArt.Option>?>(null) }
    var sgdb by remember { mutableStateOf(SteamGridDb.Status.OK) }
    var picking by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    val chosenRef = remember(current) { target.chosenRef(app, slot) }
    LaunchedEffect(slot) {
        val found = withContext(Dispatchers.IO) { runCatching { target.choices(app, slot) }.getOrNull() }
        options = found?.options ?: emptyList()
        sgdb = found?.sgdb ?: SteamGridDb.Status.SERVER
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusWithinFrames({ false }) { first } }
    val apply: (AddedGameArt.Option) -> Unit = { option ->
        scope.launch {
            val saved = withContext(Dispatchers.IO) { target.set(app, slot, option) }
            if (saved) {
                onChanged()
                back()
            }
        }
    }
    val slotName = slot.label()
    AppDialog(shown, back, "artChooser", wide = false, maxWidth = 460.dp) {
        DialogTitleRow(slotName, onClose = { onCloseAll() }, leading = {
            SmallIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), colors.onBackground, onClick = back)
        })
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.72f).dp
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
        ) {
            val opts = options
            if (opts == null) {
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            } else {
                var firstGiven = false
                for (group in listOf(AddedGameArt.FOLDER, AddedGameArt.STEAM, AddedGameArt.SGDB)) {
                    val inGroup = opts.filter { it.group == group }
                    if (inGroup.isEmpty()) continue
                    Text(artSourceLabel(group), fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, top = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (option in inGroup) key(option.full) {
                            val focus = if (!firstGiven) first.also { firstGiven = true } else null
                            ArtOption(slot, option, selected = current?.chosen == true && option.full == chosenRef, focus = focus, description = "${artSourceLabel(group)} $slotName") { apply(option) }
                        }
                    }
                }
                if (sgdb != SteamGridDb.Status.OK) SgdbNote(sgdb)
            }
            PickerRow(Icons.Outlined.Search, stringResource(R.string.art_search_sgdb), null) { searching = true }
            PickerRow(Icons.Outlined.Image, stringResource(R.string.art_pick_image), null, focus = if (opts?.isEmpty() != false) first else null) { picking = true }
            PickerRow(null, stringResource(R.string.art_reset), null) {
                scope.launch {
                    withContext(Dispatchers.IO) { target.reset(app, slot) }
                    onChanged()
                    back()
                }
            }
        }
    }
    if (picking) ExePickerDialog(stringResource(R.string.art_pick_title, slotName), kind = PickKind.IMAGE, onDismiss = { picking = false }, onPick = { file ->
        apply(AddedGameArt.Option(AddedGameArt.FILE, file.path, file.path))
    })
    if (searching) SgdbSearchDialog(
        target, slot, current,
        onBack = { searching = false }, onCloseAll = { searching = false; onCloseAll() }, onPick = apply,
    )
}

@Composable
private fun SgdbNote(status: SteamGridDb.Status) {
    val colors = MaterialTheme.colorScheme
    val quiet = status == SteamGridDb.Status.NO_KEY || status == SteamGridDb.Status.NO_GAME
    Text(
        stringResource(
            when (status) {
                SteamGridDb.Status.NO_KEY -> R.string.art_sgdb_no_key
                SteamGridDb.Status.BAD_KEY -> R.string.art_sgdb_bad_key
                SteamGridDb.Status.LIMIT -> R.string.art_sgdb_limit
                SteamGridDb.Status.OFFLINE -> R.string.art_sgdb_offline
                SteamGridDb.Status.NO_GAME -> R.string.art_sgdb_no_game
                else -> R.string.art_sgdb_server
            },
        ),
        fontSize = 12.sp, color = if (quiet) colors.onSurfaceVariant else colors.error, modifier = Modifier.padding(start = 2.dp, top = 4.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SgdbSearchDialog(
    target: ArtTarget, slot: AddedGameArt.Slot, current: AddedGameArt.SlotState?,
    onBack: () -> Unit, onCloseAll: () -> Unit, onPick: (AddedGameArt.Option) -> Unit,
) {
    val app = LocalContext.current.applicationContext
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val shown = rememberShown(onBack)
    val back = { shown.targetState = false }
    var query by remember { mutableStateOf(target.name) }
    var matches by remember { mutableStateOf<List<SteamGridDb.Game>?>(null) }
    var picked by remember { mutableStateOf<SteamGridDb.Game?>(null) }
    var images by remember { mutableStateOf<List<AddedGameArt.Option>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf(SteamGridDb.Status.OK) }
    val chosenRef = remember(current) { target.chosenRef(app, slot) }
    val slotName = slot.label()
    val showImages: (SteamGridDb.Game) -> Unit = { found ->
        scope.launch {
            picked = found
            images = emptyList()
            busy = true
            status = SteamGridDb.Status.OK
            val result = withContext(Dispatchers.IO) { AddedGameArt.gameImages(app, found.id, slot) }
            images = result.items
            status = result.status
            busy = false
        }
    }
    val search: () -> Unit = {
        scope.launch {
            busy = true
            picked = null
            status = SteamGridDb.Status.OK
            val result = withContext(Dispatchers.IO) { AddedGameArt.searchGames(app, query) }
            matches = result.items
            status = result.status
            busy = false
            val sure = result.items.firstOrNull { AddedGameArt.nameMatch(query, it.name) == 3 } ?: result.items.singleOrNull()
            if (sure != null) showImages(sure)
        }
    }
    LaunchedEffect(Unit) { search() }
    AppDialog(shown, back, "sgdbSearch", wide = false, maxWidth = 460.dp) {
        DialogTitleRow(stringResource(R.string.art_sgdb_title, slotName), onClose = { onCloseAll() }, leading = {
            SmallIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), colors.onBackground, onClick = back)
        })
        androidx.compose.material3.OutlinedTextField(
            query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.art_sgdb_search_label)) },
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { search() }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        )
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
        ) {
            if (status != SteamGridDb.Status.OK) SgdbNote(status)
            val chosenGame = picked
            val list = matches.orEmpty()
            if (chosenGame == null) {
                for (match in list) key(match.id) { PickerRow(null, match.name, null) { showImages(match) } }
                if (matches != null && list.isEmpty() && !busy && status == SteamGridDb.Status.OK) {
                    Text(stringResource(R.string.art_sgdb_none), fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, top = 4.dp))
                }
            } else {
                Text(chosenGame.name, fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, top = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (option in images) key(option.full) {
                        ArtOption(
                            slot, option, selected = current?.chosen == true && option.full == chosenRef, focus = null,
                            description = "${artSourceLabel(AddedGameArt.SGDB)} $slotName",
                        ) { onPick(option) }
                    }
                }
                if (images.isEmpty() && !busy && status == SteamGridDb.Status.OK) {
                    Text(stringResource(R.string.art_sgdb_none), fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, top = 4.dp))
                }
                if (list.size > 1) PickerRow(null, stringResource(R.string.art_sgdb_other_games), null) { picked = null }
            }
            if (busy) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        }
    }
}

@Composable
private fun ArtOption(slot: AddedGameArt.Slot, option: AddedGameArt.Option, selected: Boolean, focus: FocusRequester?, description: String, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hot = rememberHot(src)
    val scale = when (slot) { AddedGameArt.Slot.COVER -> 1.9f; AddedGameArt.Slot.ICON -> 1.6f; else -> 1.5f }
    Box(
        Modifier.then(if (focus != null) Modifier.focusRequester(focus) else Modifier).clip(RoundedCornerShape(8.dp))
            .glideBorder(hot, RoundedCornerShape(8.dp), pal.signal)
            .hoverable(src)
            .clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .semantics { contentDescription = description }
            .padding(2.dp),
    ) {
        SlotThumb(slot, if (option.thumb.startsWith("https://")) option.thumb else File(option.thumb), scale, selected)
    }
}

@Composable
internal fun AddedGamesSummaryDialog(
    games: List<AddedGames.Game>, already: Int, checking: String? = null,
    onOpen: (folder: String, uncertain: Boolean, preview: EditPreview) -> Unit,
    onRemove: (folder: String, appId: Long?, name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val colors = MaterialTheme.colorScheme
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    var rows by remember { mutableStateOf(games.map { it to (null as File?) }) }
    var gone by remember { mutableStateOf(emptySet<String>()) }
    var armed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(armed) { if (armed != null) { kotlinx.coroutines.delay(4_000); armed = null } }
    var resolved by remember { mutableStateOf(emptyMap<String, Pair<AddedGames.Game, File?>?>()) }
    LaunchedEffect(games) {
        for (old in games) {
            val path = old.folder.path
            if (path in resolved) continue
            val fresh = withContext(Dispatchers.IO) { AddedGames.single(app, path)?.let { g -> g to AddedGameArt.resolve(app, g).portrait } }
            resolved = resolved + (path to fresh)
        }
    }
    LaunchedEffect(games, resolved) {
        rows = games.mapNotNull { g -> if (g.folder.path in resolved) resolved[g.folder.path] else g to null }
    }
    val first = remember { FocusRequester() }
    val list = rows.filter { it.first.folder.path !in gone }
    LaunchedEffect(list.isNotEmpty()) { if (list.isNotEmpty()) focusWithinFrames({ false }) { first } }
    AppDialog(shown, close, "addedSummary", wide = false, maxWidth = 440.dp) {
        DialogTitleRow(
            if (list.isEmpty() && checking == null) stringResource(R.string.games_added_none) else pluralStringResource(R.plurals.games_added_n, list.size, list.size),
            onClose = close,
        )
        if (already > 0) Text(pluralStringResource(R.plurals.games_already_n, already, already), fontSize = 12.sp, color = colors.onSurfaceVariant)
        if (checking != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.primary)
            Text(checking, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
        ) {
            list.forEachIndexed { i, (g, cover) ->
                key(g.folder.path) {
                    ListRow(focus = if (i == 0) first else null, onClick = { onOpen(g.folder.path, g.exeUncertain, EditPreview.of(g, cover)); close() }) {
                        SlotThumb(AddedGameArt.Slot.COVER, cover, 0.9f)
                        Column(Modifier.weight(1f)) {
                            Text(g.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(g.exe.name, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (g.exeUncertain) UncertainMark(stringResource(R.string.mode_added_exe_check))
                        val path = g.folder.path
                        RemoveX(armed == path, stringResource(R.string.summary_remove_game, g.name)) {
                            if (armed != path) armed = path else {
                                armed = null
                                gone = gone + path
                                onRemove(path, g.appId, g.name)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoveX(armed: Boolean, description: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hot = rememberHot(src)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(32.dp).clip(Shape12)
            .background(if (armed) colors.error else if (hot) colors.error.copy(alpha = 0.15f) else androidx.compose.ui.graphics.Color.Transparent)
            .glideBorder(hot, Shape12, colors.error)
            .hoverable(src)
            .clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        androidx.compose.material3.Icon(Icons.Filled.Close, null, tint = if (armed) colors.onError else colors.error, modifier = Modifier.size(18.dp))
    }
}
