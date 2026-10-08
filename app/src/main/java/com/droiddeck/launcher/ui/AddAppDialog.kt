package com.droiddeck.launcher.ui

import android.app.Activity
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import com.droiddeck.launcher.runtime.LinuxFex
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

private val ICON_TYPES = listOf("png", "jpg", "jpeg", "webp")
private val FieldShape = RoundedCornerShape(8.dp)

/** What an icon choice shows: a picked file, or a suggestion's link. */
private fun iconModel(ref: String): Any = if (ref.startsWith("https://")) ref else File(ref)

/** The Desktop page's Add: a script, an AppImage, a GitHub release or a Flatpak, with a name and icon. */
@Composable
internal fun AddAppDialog(runtimeReady: Boolean, onDismiss: () -> Unit, onAdd: (UserApps.Request, String) -> Unit) {
    val ctx = LocalContext.current
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }

    var source by rememberSaveable { mutableStateOf(AddSource.SCRIPT) }
    var scriptPath by rememberSaveable { mutableStateOf<String?>(null) }
    var imagePath by rememberSaveable { mutableStateOf<String?>(null) }
    var repo by rememberSaveable { mutableStateOf("") }
    var flatpak by rememberSaveable { mutableStateOf("") }
    var bundlePath by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var iconPath by rememberSaveable { mutableStateOf<String?>(null) }

    val pickScript = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let { scriptPath = it.path }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let { imagePath = it.path }
    }
    val pickBundle = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let { bundlePath = it.path; flatpak = "" }
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
        AddSource.FLATPAK -> bundlePath?.takeIf { it.endsWith(".flatpak", ignoreCase = true) }?.let { File(it) }
            ?.let { UserApps.Request(UserApps.Source.FlatpakBundle(it), custom, iconPath) to (custom ?: it.nameWithoutExtension) }
            ?: flatpakId?.let { UserApps.Request(UserApps.Source.Flatpak(it), custom, iconPath) to (custom ?: it) }
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
    val step = { by: Int -> source = AddSource.entries[(source.ordinal + by + AddSource.entries.size) % AddSource.entries.size] }

    AppDialog(shown, close, "addApp", wide = true, modifier = Modifier.bumpers(onPrevious = { step(-1) }, onNext = { step(1) })) {
        DialogHeader(stringResource(R.string.user_apps_back), stringResource(R.string.add_app_title)) {
            TabStrip(AddSource.entries.map { stringResource(it.label) }, source.ordinal, { source = AddSource.entries[it] }, focusRequesters = tabFocus)
        }
        val glyph = if (source == AddSource.SCRIPT) Icons.Outlined.Terminal else Icons.Outlined.Apps
        Columns(
            first = {
                Panel(stringResource(R.string.add_app_source)) {
                    AnimatedContent(
                        targetState = source,
                        transitionSpec = {
                            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                            (fadeIn(Motion.tw(260, 60)) + slideInHorizontally(Motion.tw(320, 60)) { dir * it / 12 })
                                .togetherWith(fadeOut(Motion.tw(140)) + slideOutHorizontally(Motion.tw(140)) { -dir * it / 16 })
                        },
                        label = "addSource",
                    ) { src ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Small(stringResource(src.hint))
                            when (src) {
                                AddSource.SCRIPT -> {
                                    FileRow(scriptPath) { pickScript.launch(InAppFilePicker.buildIntent(ctx, listOf("sh"), ctx.getString(R.string.add_app_pick_script))) }
                                    scriptPath?.let { FolderNote(it) }
                                }
                                AddSource.APPIMAGE -> FileRow(imagePath) {
                                    pickImage.launch(InAppFilePicker.buildIntent(ctx, listOf("appimage"), ctx.getString(R.string.add_app_pick_appimage)))
                                }
                                AddSource.GITHUB -> CheckedField(
                                    repo, { repo = it.take(200) }, stringResource(R.string.add_app_repository),
                                    stringResource(R.string.add_app_repository_placeholder),
                                    stringResource(R.string.add_app_repository_invalid).takeIf { repo.isNotBlank() && repoId == null },
                                )
                                AddSource.FLATPAK -> {
                                    CheckedField(
                                        flatpak, { flatpak = it.take(200); bundlePath = null }, stringResource(R.string.add_app_flatpak_id),
                                        stringResource(R.string.add_app_flatpak_placeholder),
                                        stringResource(R.string.add_app_flatpak_invalid).takeIf { flatpak.isNotBlank() && flatpakId == null },
                                    )
                                    Small(stringResource(R.string.add_app_flatpak_bundle_hint))
                                    FileRow(bundlePath) {
                                        pickBundle.launch(InAppFilePicker.buildIntent(ctx, listOf("flatpak"), ctx.getString(R.string.add_app_pick_flatpak)))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            second = {
                Panel(stringResource(R.string.add_app_appearance)) {
                    AppPreview(iconPath?.let(::iconModel), glyph, request?.second, stringResource(source.label))
                    NameField(name, { name = it }, stringResource(R.string.add_app_name_placeholder))
                    IconActions(
                        onChoose = { pickIcon.launch(InAppFilePicker.buildIntent(ctx, ICON_TYPES, ctx.getString(R.string.add_app_pick_icon))) },
                        clear = iconPath?.let { R.string.add_app_clear_icon to { iconPath = null } },
                    )
                    if (source == AddSource.GITHUB && repoId != null) IconSuggestions(repoId, iconPath) { iconPath = it }
                }
            },
        )
        DialogFooter(
            when {
                !runtimeReady -> stringResource(R.string.add_app_runtime_required)
                busy != null -> stringResource(R.string.add_app_busy, busy)
                else -> null
            },
            stringResource(R.string.add_app_confirm), request != null && runtimeReady && busy == null,
            onCancel = close,
        ) { request?.let { (r, label) -> onAdd(r, label); close() } }
    }
}

/**
 * An added app's name and icon, changed on its page. [icon]: null keeps the current one, "" goes
 * back to the default, else a picked file or a suggestion.
 */
@Composable
internal fun EditAppDialog(app: UserApps.App, onDismiss: () -> Unit, onSave: (String, String?, String?) -> Unit) {
    val ctx = LocalContext.current
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    var name by rememberSaveable(app.key) { mutableStateOf(app.name) }
    var icon by rememberSaveable(app.key) { mutableStateOf<String?>(null) }
    var fex by rememberSaveable(app.key) { mutableStateOf(app.fex) }
    val fexChoice = app.kind != UserApps.Kind.FLATPAK
    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let { icon = it.path }
    }
    val preview: Any? = when (val i = icon) {
        null -> app.icon
        "" -> null
        else -> iconModel(i)
    }
    val busy = UserAppsState.working
    val changed = name.trim() != app.name || icon != null || fex != app.fex
    AppDialog(shown, close, "editApp", wide = false) {
        DialogHeader(stringResource(app.kind.label()), stringResource(R.string.edit_app_title, app.name))
        Rise(1) {
            Panel(stringResource(R.string.add_app_appearance)) {
                AppPreview(
                    preview, if (app.kind == UserApps.Kind.SCRIPT) Icons.Outlined.Terminal else Icons.Outlined.Apps,
                    name.trim().ifEmpty { null }, stringResource(app.kind.label()),
                )
                NameField(name, { name = it }, app.name)
                IconActions(
                    onChoose = { pickIcon.launch(InAppFilePicker.buildIntent(ctx, ICON_TYPES, ctx.getString(R.string.add_app_pick_icon))) },
                    clear = if (preview == null) null else R.string.add_app_icon_reset to { icon = "" },
                )
                app.repo?.let { repo -> IconSuggestions(repo, icon) { icon = it } }
                if (fexChoice) {
                    Text(stringResource(R.string.app_fex_title).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SegmentedTabs(
                        listOf(
                            LinuxFex.AUTO to stringResource(R.string.app_fex_auto),
                            LinuxFex.ON to stringResource(R.string.app_fex_on),
                            LinuxFex.OFF to stringResource(R.string.app_fex_off),
                        ),
                        fex,
                    ) { fex = it }
                    Text(stringResource(R.string.app_fex_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        DialogFooter(
            busy?.let { stringResource(R.string.add_app_busy, it) },
            stringResource(R.string.edit_app_save), name.isNotBlank() && changed && busy == null,
            onCancel = close,
        ) { onSave(name.trim(), icon, fex.takeIf { fexChoice && it != app.fex }); close() }
    }
}

/** Open from the first frame; [onDismiss] once the closing animation has run. */
@Composable
internal fun rememberShown(onDismiss: () -> Unit): MutableTransitionState<Boolean> {
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    LaunchedEffect(shown.currentState, shown.isIdle) { if (shown.isIdle && !shown.currentState && !shown.targetState) onDismiss() }
    return shown
}

/**
 * The card both dialogs sit in, sized to fit a landscape handheld without scrolling: it scales in
 * like the app's menus, rises above the keyboard, and closes on a tap outside or B.
 */
@Composable
internal fun AppDialog(
    shown: MutableTransitionState<Boolean>, close: () -> Unit, label: String, wide: Boolean,
    modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit,
) {
    val pal = LocalPalette.current
    // The page's ring stays out of sight behind it.
    VeilRing()
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
      // The page's focus memory stays the page's: nothing in here is a place to come back to.
      CompositionLocalProvider(LocalFrontFocus provides null) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().imePadding()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = close),
        ) {
            AnimatedVisibility(
                visibleState = shown,
                enter = fadeIn(Motion.tw(180)) + scaleIn(Motion.sp(0.7f), initialScale = 0.94f),
                exit = fadeOut(Motion.tw(140)) + scaleOut(Motion.tw(140), targetScale = 0.96f),
                label = label,
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .padding(12.dp)
                        .widthIn(max = if (wide) 860.dp else 560.dp)
                        .fillMaxWidth()
                        .heightIn(max = (LocalConfiguration.current.screenHeightDp - 24).dp)
                        .shadow(24.dp, Shape16, ambientColor = Color.Black, spotColor = Color.Black)
                        .clip(Shape16)
                        .background(pal.surfaceVariant.copy(alpha = 0.97f))
                        .border(1.dp, pal.signal.copy(alpha = 0.22f), Shape16)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .controllerBack(close)
                        .then(modifier)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    content = content,
                )
            }
        }
      }
    }
}

/** The eyebrow and title, with [trailing] (the tabs) beside them when there is room, else under them. */
@Composable
internal fun DialogHeader(eyebrow: String, title: String, trailing: (@Composable () -> Unit)? = null) {
    val heading = @Composable {
        Column {
            Eyebrow(eyebrow)
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Rise(0) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            when {
                trailing == null -> heading()
                maxWidth >= 600.dp -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.weight(1f)) { heading() }
                    trailing()
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { heading(); trailing() }
            }
        }
    }
}

/** Side by side and of one height on a wide card, stacked on a narrow one. */
@Composable
private fun Columns(first: @Composable ColumnScope.() -> Unit, second: @Composable ColumnScope.() -> Unit) {
    Rise(1) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 600.dp) Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.height(IntrinsicSize.Max)) {
                Column(Modifier.weight(1.15f).fillMaxHeight(), content = first)
                Column(Modifier.weight(1f).fillMaxHeight(), content = second)
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                first()
                second()
            }
        }
    }
}

/** A titled inset of the card; it fills the height its column is given. */
@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().fillMaxHeight().clip(Shape14)
            .background(colors.surface.copy(alpha = 0.6f)).border(1.dp, LocalPalette.current.line, Shape14).padding(14.dp),
    ) {
        Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = colors.onSurfaceVariant)
        content()
    }
}

/** Why the main button is off (or nothing), then Cancel and the main button, at the card's foot. */
@Composable
internal fun DialogFooter(note: String?, confirm: String, enabled: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val lineColor = LocalPalette.current.line
    Rise(2) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.drawBehind { drawLine(lineColor, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }.padding(top = 14.dp),
        ) {
            Box(Modifier.weight(1f)) { if (note != null) Small(note) }
            SecondaryButton(stringResource(R.string.add_app_cancel), onClick = onCancel)
            PrimaryButton(confirm, enabled = enabled, onClick = onConfirm)
        }
    }
}

@Composable
internal fun Small(text: String, error: Boolean = false) =
    Text(text, fontSize = 12.sp, lineHeight = 16.sp, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)

private fun hideKeyboard(view: View) {
    (view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
        ?.hideSoftInputFromWindow(view.windowToken, 0)
}

/** The app's text field, with what is wrong with its value under it. */
@Composable
private fun CheckedField(value: String, onChange: (String) -> Unit, label: String, placeholder: String, problem: String?) {
    val view = LocalView.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AdbTextField(
            value, onChange, label, KeyboardType.Uri, ImeAction.Done, placeholder = placeholder, compact = true,
            onDone = { hideKeyboard(view) },
        )
        if (problem != null) Small(problem, error = true)
    }
}

@Composable
private fun NameField(name: String, onName: (String) -> Unit, placeholder: String) {
    val view = LocalView.current
    AdbTextField(
        name, { onName(it.take(80)) }, stringResource(R.string.add_app_name), KeyboardType.Text, ImeAction.Done,
        placeholder = placeholder, compact = true, onDone = { hideKeyboard(view) },
    )
}

/** The app as the Desktop page will show it: its icon, its name ([name] null: not known yet) and kind. */
@Composable
private fun AppPreview(icon: Any?, glyph: ImageVector, name: String?, kind: String) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().clip(Shape12).background(colors.surfaceVariant).border(1.dp, pal.line2, Shape12).padding(10.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).clip(Shape12).background(colors.surface)) {
            AnimatedContent(icon, transitionSpec = { (fadeIn(Motion.tw(260)) + scaleIn(Motion.sp(0.6f), 0.85f)) togetherWith fadeOut(Motion.tw(160)) }, label = "appIcon") { model ->
                if (model != null) AsyncImage(model = model, contentDescription = stringResource(R.string.add_app_icon), contentScale = ContentScale.Fit, modifier = Modifier.size(40.dp))
                else Icon(glyph, stringResource(R.string.add_app_icon_default), tint = colors.onSurfaceVariant, modifier = Modifier.size(24.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                name ?: stringResource(R.string.add_app_preview_name), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (name != null) colors.onBackground else colors.onSurfaceVariant,
            )
            Text(kind, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** The icon's picker and [clear] (a label and what it does). */
@Composable
private fun IconActions(onChoose: () -> Unit, clear: Pair<Int, () -> Unit>?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(stringResource(R.string.add_app_choose_icon), compact = true, onClick = onChoose)
        AnimatedVisibility(clear != null, enter = fadeIn(Motion.tw(180)) + expandHorizontally(Motion.tw(220)), exit = fadeOut(Motion.tw(120)) + shrinkHorizontally(Motion.tw(180))) {
            clear?.let { (label, action) -> SecondaryButton(stringResource(label), compact = true, onClick = action) }
        }
    }
}

/** Icons found in [repo], looked up off the main thread once the name has settled; [selected] is ringed. */
@Composable
private fun IconSuggestions(repo: String, selected: String?, onPick: (String) -> Unit) {
    val found by produceState<List<String>?>(null, repo) {
        value = null
        delay(500)
        value = withContext(Dispatchers.IO) { runCatching { UserApps.githubIcons(repo) }.getOrDefault(emptyList()) }
    }
    val list = found
    if (list != null && list.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(44.dp)) {
        Small(stringResource(if (list == null) R.string.add_app_icon_searching else R.string.add_app_icon_suggestions))
        if (list != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            list.forEachIndexed { i, url -> Rise(i) { SuggestedIcon(url, url == selected) { onPick(url) } } }
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
        modifier = Modifier.size(44.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(Shape12).background(colors.surface)
            .glideBorder(hot, Shape12, pal.signal, pal.line2)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick)
            .controllerConfirm(onClick = onClick),
    ) {
        AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(), contentDescription = stringResource(R.string.add_app_icon), contentScale = ContentScale.Fit, modifier = Modifier.size(34.dp))
        // The chosen one is ticked; the ring stays with focus.
        if (selected) Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(14.dp).clip(androidx.compose.foundation.shape.CircleShape).background(pal.signal),
        ) { Icon(Icons.Filled.Check, contentDescription = null, tint = pal.onSignal, modifier = Modifier.size(10.dp)) }
    }
}

/** The chosen file, or none yet, laid out like the app's text fields, with the button that picks it. */
@Composable
private fun FileRow(path: String?, onChoose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column {
        Text(stringResource(R.string.add_app_file), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = colors.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.weight(1f).height(44.dp).background(colors.surfaceVariant, FieldShape).border(1.dp, pal.line2, FieldShape).padding(horizontal = 12.dp),
            ) {
                Text(
                    path?.substringAfterLast('/') ?: stringResource(R.string.add_app_no_file), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (path != null) colors.onBackground else colors.onSurfaceVariant,
                )
            }
            SecondaryButton(stringResource(if (path == null) R.string.add_app_choose_file else R.string.add_app_change_file), compact = true, onClick = onChoose)
        }
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
    Small(
        when {
            p == null -> stringResource(R.string.add_app_checking_folder)
            p.copy -> stringResource(R.string.add_app_folder_copied, FileUtils.sizeToString(p.bytes))
            else -> stringResource(R.string.add_app_folder_linked, p.folder.path)
        },
    )
}
