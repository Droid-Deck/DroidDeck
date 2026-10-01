package com.droiddeck.launcher.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.files.InAppFilePicker
import com.droiddeck.launcher.runtime.UserApps
import com.droiddeck.launcher.store.UserAppsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private enum class AddSource(val label: Int, val hint: Int) {
    SCRIPT(R.string.add_app_tab_script, R.string.add_app_script_hint),
    APPIMAGE(R.string.add_app_tab_appimage, R.string.add_app_appimage_hint),
    GITHUB(R.string.add_app_tab_github, R.string.add_app_github_hint),
    FLATPAK(R.string.add_app_tab_flatpak, R.string.add_app_flatpak_hint),
}

/** The Desktop page's Add: a script, an AppImage, a GitHub release or a Flatpak, with a name and icon. */
@Composable
internal fun AddAppDialog(runtimeReady: Boolean, onDismiss: () -> Unit, onAdd: (UserApps.Request, String) -> Unit) {
    val ctx = LocalContext.current
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    val close = { shown.targetState = false }
    LaunchedEffect(shown.currentState, shown.isIdle) { if (shown.isIdle && !shown.currentState && !shown.targetState) onDismiss() }

    var source by rememberSaveable { mutableStateOf(AddSource.SCRIPT) }
    var scriptPath by rememberSaveable { mutableStateOf<String?>(null) }
    var imagePath by rememberSaveable { mutableStateOf<String?>(null) }
    var repo by rememberSaveable { mutableStateOf("") }
    var flatpak by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var iconPath by rememberSaveable { mutableStateOf<String?>(null) }

    val pickScript = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let { scriptPath = it.path }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let { imagePath = it.path }
    }
    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let { iconPath = it.path }
    }

    val repoId = UserApps.githubRepo(repo)
    val flatpakId = UserApps.flatpakId(flatpak)
    val custom = name.trim().takeIf { it.isNotEmpty() }
    val request: Pair<UserApps.Request, String>? = when (source) {
        AddSource.SCRIPT -> scriptPath?.takeIf { it.endsWith(".sh", ignoreCase = true) }?.let { File(it) }
            ?.let { UserApps.Request(UserApps.Source.Script(it), custom, iconPath) to (custom ?: it.nameWithoutExtension) }
        AddSource.APPIMAGE -> imagePath?.takeIf { it.endsWith(".appimage", ignoreCase = true) }?.let { File(it) }
            ?.let { UserApps.Request(UserApps.Source.AppImage(it), custom, iconPath) to (custom ?: it.nameWithoutExtension) }
        AddSource.GITHUB -> repoId?.let { UserApps.Request(UserApps.Source.GitHub(it), custom, iconPath) to (custom ?: it) }
        AddSource.FLATPAK -> flatpakId?.let { UserApps.Request(UserApps.Source.Flatpak(it), custom, iconPath) to (custom ?: it) }
    }
    val busy = UserAppsState.working
    val tabFocus = remember { AddSource.entries.map { FocusRequester() } }
    val inputMode = LocalInputModeManager.current.inputMode
    // Once, as it opens: typing a name switches the input mode too, and must keep its field.
    LaunchedEffect(Unit) {
        if (inputMode == InputMode.Keyboard) {
            repeat(2) { androidx.compose.runtime.withFrameNanos { } }
            runCatching { tabFocus[source.ordinal].requestFocus() }
        }
    }

    AppDialog(
        shown, close, "addApp",
        Modifier.bumpers(
            onPrevious = { source = AddSource.entries[(source.ordinal + AddSource.entries.size - 1) % AddSource.entries.size] },
            onNext = { source = AddSource.entries[(source.ordinal + 1) % AddSource.entries.size] },
        ),
    ) {
        Rise(0) {
            Column {
                Eyebrow(stringResource(R.string.user_apps_back))
                Title(stringResource(R.string.add_app_title))
            }
        }
        Rise(1) {
            TabStrip(
                AddSource.entries.map { stringResource(it.label) }, source.ordinal, { source = AddSource.entries[it] },
                focusRequesters = tabFocus,
            )
        }
        Rise(2) {
            AnimatedContent(
                targetState = source,
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (fadeIn(Motion.tw(260, 60)) + slideInHorizontally(Motion.tw(320, 60)) { dir * it / 12 })
                        .togetherWith(fadeOut(Motion.tw(140)) + slideOutHorizontally(Motion.tw(140)) { -dir * it / 16 })
                },
                label = "addSource",
            ) { src ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Lede(stringResource(src.hint))
                    when (src) {
                        AddSource.SCRIPT -> {
                            FileRow(scriptPath) { pickScript.launch(InAppFilePicker.buildIntent(ctx, listOf("sh"), ctx.getString(R.string.add_app_pick_script))) }
                            scriptPath?.let { FolderNote(it) }
                        }
                        AddSource.APPIMAGE -> FileRow(imagePath) {
                            pickImage.launch(InAppFilePicker.buildIntent(ctx, listOf("appimage"), ctx.getString(R.string.add_app_pick_appimage)))
                        }
                        AddSource.GITHUB -> OutlinedTextField(
                            repo, { repo = it.take(200) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.add_app_repository)) },
                            placeholder = { Text(stringResource(R.string.add_app_repository_placeholder)) },
                            isError = repo.isNotBlank() && repoId == null,
                            supportingText = if (repo.isNotBlank() && repoId == null) { { Text(stringResource(R.string.add_app_repository_invalid)) } } else null,
                        )
                        AddSource.FLATPAK -> OutlinedTextField(
                            flatpak, { flatpak = it.take(200) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.add_app_flatpak_id)) },
                            placeholder = { Text(stringResource(R.string.add_app_flatpak_placeholder)) },
                            isError = flatpak.isNotBlank() && flatpakId == null,
                            supportingText = if (flatpak.isNotBlank() && flatpakId == null) { { Text(stringResource(R.string.add_app_flatpak_invalid)) } } else null,
                        )
                    }
                }
            }
        }
        Rise(3) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NameIconRow(
                    name, { name = it }, stringResource(R.string.add_app_name_placeholder),
                    iconPath?.let(::iconModel), if (source == AddSource.SCRIPT) Icons.Outlined.Terminal else Icons.Outlined.Apps,
                    onChoose = { pickIcon.launch(InAppFilePicker.buildIntent(ctx, ICON_TYPES, ctx.getString(R.string.add_app_pick_icon))) },
                    clear = iconPath?.let { R.string.add_app_clear_icon to { iconPath = null } },
                )
                if (source == AddSource.GITHUB && repoId != null) IconSuggestions(repoId, iconPath) { iconPath = it }
            }
        }
        Rise(4) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    !runtimeReady -> Note(stringResource(R.string.add_app_runtime_required))
                    busy != null -> Note(stringResource(R.string.add_app_busy, busy))
                }
                Actions {
                    PrimaryButton(stringResource(R.string.add_app_confirm), enabled = request != null && runtimeReady && busy == null) {
                        request?.let { (r, label) -> onAdd(r, label); close() }
                    }
                    SecondaryButton(stringResource(R.string.add_app_cancel), onClick = close)
                }
            }
        }
    }
}

private val ICON_TYPES = listOf("png", "jpg", "jpeg", "webp")

/** What an icon choice shows: a picked file, or a suggestion's link. */
private fun iconModel(ref: String): Any = if (ref.startsWith("https://")) ref else File(ref)

/**
 * An added app's name and icon, changed on its page. [icon]: null keeps the current one, "" goes
 * back to the default, else a picked file or a suggestion.
 */
@Composable
internal fun EditAppDialog(app: UserApps.App, onDismiss: () -> Unit, onSave: (String, String?) -> Unit) {
    val ctx = LocalContext.current
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    val close = { shown.targetState = false }
    LaunchedEffect(shown.currentState, shown.isIdle) { if (shown.isIdle && !shown.currentState && !shown.targetState) onDismiss() }
    var name by rememberSaveable(app.key) { mutableStateOf(app.name) }
    var icon by rememberSaveable(app.key) { mutableStateOf<String?>(null) }
    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let { icon = it.path }
    }
    val preview: Any? = when (val i = icon) {
        null -> app.icon
        "" -> null
        else -> iconModel(i)
    }
    val busy = UserAppsState.working
    val changed = name.trim() != app.name || icon != null
    AppDialog(shown, close, "editApp") {
        Rise(0) {
            Column {
                Eyebrow(stringResource(app.kind.label()))
                Title(stringResource(R.string.edit_app_title, app.name))
            }
        }
        Rise(1) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NameIconRow(
                    name, { name = it }, app.name, preview, if (app.kind == UserApps.Kind.SCRIPT) Icons.Outlined.Terminal else Icons.Outlined.Apps,
                    onChoose = { pickIcon.launch(InAppFilePicker.buildIntent(ctx, ICON_TYPES, ctx.getString(R.string.add_app_pick_icon))) },
                    clear = if (preview == null) null else R.string.add_app_icon_reset to { icon = "" },
                )
                app.repo?.let { repo -> IconSuggestions(repo, icon) { icon = it } }
            }
        }
        Rise(2) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (busy != null) Note(stringResource(R.string.add_app_busy, busy))
                Actions {
                    PrimaryButton(stringResource(R.string.edit_app_save), enabled = name.isNotBlank() && changed && busy == null) {
                        onSave(name.trim(), icon); close()
                    }
                    SecondaryButton(stringResource(R.string.add_app_cancel), onClick = close)
                }
            }
        }
    }
}

/** The card the add and edit dialogs sit in: it scales in like the app's menus and closes on a tap outside or B. */
@Composable
private fun AppDialog(
    shown: MutableTransitionState<Boolean>, close: () -> Unit, label: String, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val pal = LocalPalette.current
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
      // The page's focus memory stays the page's: nothing in here is a place to come back to.
      CompositionLocalProvider(LocalFrontFocus provides null) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = close)) {
            AnimatedVisibility(
                visibleState = shown,
                enter = fadeIn(Motion.tw(220)) + scaleIn(Motion.sp(0.7f), initialScale = 0.92f, transformOrigin = TransformOrigin(0.5f, 0.6f)),
                exit = fadeOut(Motion.tw(150)) + scaleOut(Motion.tw(150), targetScale = 0.96f),
                label = label,
            ) {
                val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.92f).dp
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .widthIn(max = 620.dp)
                        .fillMaxWidth()
                        .heightIn(max = maxHeight)
                        .shadow(24.dp, Shape16, ambientColor = Color.Black, spotColor = Color.Black)
                        .clip(Shape16)
                        .background(pal.surfaceVariant.copy(alpha = 0.97f))
                        .border(1.dp, pal.signal.copy(alpha = 0.22f), Shape16)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .controllerBack(close)
                        .then(modifier)
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    content = content,
                )
            }
        }
      }
    }
}

/** The name field beside the icon it will have, its picker and [clear] (a label and what it does). */
@Composable
private fun NameIconRow(
    name: String, onName: (String) -> Unit, placeholder: String, preview: Any?, glyph: ImageVector,
    onChoose: () -> Unit, clear: Pair<Int, () -> Unit>?,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            name, { onName(it.take(80)) }, singleLine = true, modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.add_app_name)) },
            placeholder = { Text(placeholder) },
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(52.dp).clip(Shape14).background(colors.surface).border(1.dp, pal.line2, Shape14),
        ) {
            AnimatedContent(preview, transitionSpec = { fadeIn(Motion.tw(260)) togetherWith fadeOut(Motion.tw(160)) }, label = "appIcon") { model ->
                if (model != null) AsyncImage(model = model, contentDescription = stringResource(R.string.add_app_icon), contentScale = ContentScale.Fit, modifier = Modifier.size(40.dp))
                else Icon(glyph, stringResource(R.string.add_app_icon_default), tint = colors.onSurfaceVariant, modifier = Modifier.size(26.dp))
            }
        }
        SecondaryButton(stringResource(R.string.add_app_choose_icon), compact = true, onClick = onChoose)
        if (clear != null) SecondaryButton(stringResource(clear.first), compact = true, onClick = clear.second)
    }
}

/** Icons found in [repo], looked up off the main thread once the name has settled; [selected] is ringed. */
@Composable
private fun IconSuggestions(repo: String, selected: String?, onPick: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val found by produceState<List<String>?>(null, repo) {
        value = null
        delay(500)
        value = withContext(Dispatchers.IO) { runCatching { UserApps.githubIcons(repo) }.getOrDefault(emptyList()) }
    }
    val list = found
    AnimatedVisibility(list == null || list.isNotEmpty(), enter = fadeIn(Motion.tw(220)) + expandVertically(Motion.tw(260)), exit = fadeOut(Motion.tw(150)) + shrinkVertically(Motion.tw(200))) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(if (list == null) R.string.add_app_icon_searching else R.string.add_app_icon_suggestions),
                fontSize = 12.sp, color = colors.onSurfaceVariant,
            )
            if (list != null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                list.forEachIndexed { i, url -> Rise(i) { SuggestedIcon(url, url == selected) { onPick(url) } } }
            }
        }
    }
}

@Composable
private fun SuggestedIcon(url: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val scale by animateFloatAsState(if (hot || selected) 1.06f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "suggestScale")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(56.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(Shape14).background(colors.surface)
            .glideBorder(hot || selected, Shape14, pal.signal, pal.line2)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick)
            .controllerConfirm(onClick = onClick),
    ) { AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(), contentDescription = stringResource(R.string.add_app_icon), contentScale = ContentScale.Fit, modifier = Modifier.size(44.dp)) }
}

/** The chosen file, or none yet, with the button that picks it. */
@Composable
private fun FileRow(path: String?, onChoose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.weight(1f).clip(Shape12).background(colors.surface).border(1.dp, pal.line2, Shape12).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.add_app_file), fontSize = 12.sp, color = colors.onSurfaceVariant)
            Text(
                path ?: stringResource(R.string.add_app_no_file), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (path != null) colors.onBackground else colors.onSurfaceVariant,
            )
        }
        SecondaryButton(stringResource(if (path == null) R.string.add_app_choose_file else R.string.add_app_change_file), onClick = onChoose)
    }
}

/** Whether the script's folder will be linked or copied in, worked out off the main thread. */
@Composable
private fun FolderNote(scriptPath: String) {
    var plan by remember(scriptPath) { mutableStateOf<UserApps.ScriptPlan?>(null) }
    LaunchedEffect(scriptPath) {
        plan = withContext(Dispatchers.IO) { runCatching { UserApps.planScript(File(scriptPath)) }.getOrNull() }
    }
    val p = plan
    Note(
        when {
            p == null -> stringResource(R.string.add_app_checking_folder)
            p.copy -> stringResource(R.string.add_app_folder_copied, FileUtils.sizeToString(p.bytes))
            else -> stringResource(R.string.add_app_folder_linked, p.folder.path)
        },
    )
}
