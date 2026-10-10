package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.update.AppUpdates
import com.droiddeck.launcher.update.AppUpdates.Channel
import com.droiddeck.launcher.update.AppUpdates.Follow
import com.droiddeck.launcher.update.AppUpdates.Offer
import com.droiddeck.launcher.update.AppUpdates.Release

/** The Updates page: the latest build of each channel, the one followed, and an update in progress. */
class UpdatesState(
    val catalog: AppUpdates.Catalog? = null,
    val follow: Follow = Follow(Channel.STABLE),
    val checking: Boolean = false,
    val error: String? = null,
    /** "Downloading", "Installing", ... while an update is under way; [percent] is -1 when unknown. */
    val stage: String? = null,
    val percent: Int = -1,
    /** Explain "Install unknown apps" before sending the user to it. */
    val askPermission: Boolean = false,
) {
    val hasUpdate: Boolean get() = AppUpdates.hasUpdate(catalog, follow)
}

class UpdatesActions(
    val onCheck: () -> Unit = {},
    val onFollow: (Follow) -> Unit = {},
    val onInstall: (Release) -> Unit = {},
    val onAllowInstalls: () -> Unit = {},
    val onDismissPermission: () -> Unit = {},
)

@Composable
internal fun UpdatesPage(s: FrontEndState, a: FrontEndActions, modifier: Modifier) {
    val u = s.updates
    val ua = a.updates
    val me = remember { AppUpdates.installed() }
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        PageHeader(stringResource(R.string.upd_title)) {
            // The build that is running, beside the title where it is always in view.
            Text(
                stringResource(R.string.upd_build, me.version, s.buildLabel), fontSize = 12.5.sp, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(top = 6.dp),
            )
            val checked = u.catalog?.checkedAt?.takeIf { it > 0 }
            // The relative time comes capitalized to stand alone (Spanish "Hace 5 minutos"); here it
            // ends a sentence.
            if (!LocalNarrowPane.current) Text(
                if (u.checking) stringResource(R.string.common_checking) else if (checked != null) stringResource(R.string.upd_checked, ago(checked).replaceFirstChar { it.lowercase() }) else stringResource(R.string.upd_not_checked),
                fontSize = 13.sp, color = colors.onSurfaceVariant,
            )
            ToolIcon(Icons.Outlined.Refresh, stringResource(R.string.store_check_updates), busy = u.checking, enabled = !u.checking && u.stage == null, onClick = ua.onCheck)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // Landscape has the width for channels and status side by side; a narrow pane, or a small
            // phone's (where each channel's name and date would be cut to a word), stacks them. The
            // Thor's top screen leaves about 690dp here and keeps them side by side.
            val stacked = LocalNarrowPane.current || maxWidth < 600.dp
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                if (stacked) {
                    StatusPanel(s, u, ua, me)
                    Box(Modifier.height(18.dp))
                    ChannelLabel()
                    ChannelPicker(u, ua)
                } else {
                    // The label sits over both columns, so the status card lines up with the first channel.
                    ChannelLabel()
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Box(Modifier.weight(1f)) { ChannelPicker(u, ua) }
                        Box(Modifier.weight(1.15f)) { StatusPanel(s, u, ua, me) }
                    }
                }
            }
        }
    }
    if (u.askPermission) AlertDialog(
        onDismissRequest = ua.onDismissPermission,
        title = { Text(stringResource(R.string.upd_perm_title)) },
        text = {
            Text(
                stringResource(R.string.upd_perm_text),
            )
        },
        confirmButton = { TextButton(onClick = ua.onAllowInstalls) { Text(stringResource(R.string.upd_open_settings)) } },
        dismissButton = { TextButton(onClick = ua.onDismissPermission) { Text(stringResource(R.string.upd_not_now)) } },
    )
}

/** Where things stand, as a system updater says it: a status line, what you'd get, and one button. */
@Composable
private fun StatusPanel(s: FrontEndState, u: UpdatesState, ua: UpdatesActions, me: AppUpdates.Installed) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val catalog = u.catalog
    val release = catalog?.let { AppUpdates.release(it, u.follow) }
    val offer = if (me.updatable) catalog?.let { AppUpdates.offer(it, u.follow, me) } else null
    val name = channelName(u.follow)
    val offered = release != null && (offer == Offer.UPDATE || offer == Offer.SWITCH)
    val installBlock = release?.let { AppUpdates.installBlock(it, me) }
    class Look(val tint: Color, val status: String, val headline: String, val detail: String?)
    val look = when {
        !me.updatable -> Look(colors.onSurfaceVariant, stringResource(R.string.upd_signed_status), stringResource(R.string.upd_signed_headline),
            stringResource(R.string.upd_signed_detail))
        catalog == null -> Look(colors.onSurfaceVariant, if (u.checking) stringResource(R.string.common_checking) else stringResource(R.string.upd_not_checked), stringResource(R.string.upd_title), null)
        offered && installBlock != null -> Look(colors.onSurfaceVariant, stringResource(R.string.upd_blocked_status), newBuild(u.follow, release!!), null)
        offer == Offer.UPDATE -> Look(AttentionAmber, stringResource(R.string.upd_available), newBuild(u.follow, release!!), null)
        offer == Offer.SWITCH -> Look(pal.signal, stringResource(R.string.upd_switch), newBuild(u.follow, release!!), null)
        offer == Offer.AHEAD -> Look(pal.signal, stringResource(R.string.upd_ahead_status), stringResource(R.string.upd_ahead_headline),
            stringResource(R.string.upd_ahead_detail))
        offer == Offer.GONE -> Look(AttentionAmber, stringResource(R.string.upd_gone_status), stringResource(R.string.upd_gone_headline),
            stringResource(R.string.upd_gone_detail))
        else -> Look(pal.good, stringResource(R.string.upd_current_status), stringResource(R.string.upd_current_headline, name), null)
    }
    Column(
        modifier = Modifier.fillMaxWidth().clip(Shape16).background(colors.surface).border(1.dp, pal.line, Shape16).padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(look.tint))
            Text(look.status, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = look.tint)
            if (offered) Text(stringResource(R.string.upd_dot_ago, ago(release!!.publishedAt)), fontSize = 13.sp, color = colors.onSurfaceVariant)
        }
        Text(look.headline, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, modifier = Modifier.padding(top = 4.dp))
        if (offered) {
            ReleaseNotes(changeTitle(release!!.title.ifBlank { release.tag }), release.summary, release.tag)
        }
        if (look.detail != null) Text(look.detail, fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        if (installBlock != null && offered) {
            Text(installBlock.message(androidx.compose.ui.platform.LocalContext.current), fontSize = 13.sp, color = AttentionAmber, modifier = Modifier.padding(top = 10.dp))
        }
        if (u.error != null) Text(u.error, fontSize = 13.sp, color = pal.error, modifier = Modifier.padding(top = 12.dp))
        val installable = release?.apk != null && installBlock == null && !s.sessionRunning
        val button = release?.apk?.size?.takeIf { it > 0 }?.let { " · ${megabytes(it)}" }.orEmpty()
        Box(Modifier.padding(top = 14.dp)) {
            when {
                u.stage != null -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (u.percent >= 0) stringResource(R.string.upd_stage_percent, u.stage, u.percent) else stringResource(R.string.upd_stage, u.stage), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                    if (u.percent >= 0) LinearProgressIndicator(progress = { u.percent / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
                }
                offered && installBlock != null -> Unit
                offer == Offer.UPDATE -> PrimaryButton(stringResource(R.string.upd_update_button, button), enabled = installable, main = true) { ua.onInstall(release!!) }
                offer == Offer.SWITCH -> PrimaryButton(stringResource(R.string.upd_install_button, button), enabled = installable, main = true) { ua.onInstall(release!!) }
                offer == Offer.AHEAD && installBlock == null ->
                    SecondaryButton(stringResource(R.string.upd_install_anyway, release?.version.orEmpty()), enabled = installable) { ua.onInstall(release!!) }
                offer == Offer.GONE -> PrimaryButton(stringResource(R.string.upd_follow_preview), main = true) { ua.onFollow(Follow(Channel.NIGHTLY)) }
            }
        }
        if (s.sessionRunning && (offered || offer == Offer.AHEAD)) {
            Text(stringResource(R.string.upd_stop_session), fontSize = 12.5.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        if (u.follow.channel == Channel.NIGHTLY) {
            PreviewHistory(catalog, me)
        }
    }
}

/** The change and its notes, two lines each; More opens the rest when either is cut short. */
@Composable
private fun ReleaseNotes(title: String, notes: String, key: String) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var open by rememberSaveable(key) { mutableStateOf(false) }
    var cut by remember(key) { mutableStateOf(false) }
    Text(
        title, fontSize = 15.sp, color = colors.onBackground, maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (it.hasVisualOverflow) cut = true }, modifier = Modifier.padding(top = 10.dp),
    )
    if (notes.isNotBlank()) Text(
        notes, fontSize = 13.5.sp, color = colors.onSurfaceVariant, maxLines = if (open) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (it.hasVisualOverflow) cut = true }, modifier = Modifier.padding(top = 4.dp),
    )
    if (cut || open) {
        val src = remember { MutableInteractionSource() }
        val hot = rememberHot(src)
        val toggle = { open = !open }
        Text(
            if (open) stringResource(R.string.upd_less) else stringResource(R.string.upd_more), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = pal.signal,
            modifier = Modifier.padding(top = 2.dp).offset(x = (-6).dp).paneItem("notes:more")
                .clip(Shape12).glideBorder(hot, Shape12, pal.signal)
                .hoverable(src).clickable(interactionSource = src, indication = null, role = Role.Button, onClick = toggle)
                .controllerConfirm(onClick = toggle)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

/** The headline for a build on offer: "DroidDeck 0.3.0", "New Preview build", "PR #93 test build". */
@Composable
private fun newBuild(f: Follow, r: Release) = when (f.channel) {
    Channel.STABLE -> stringResource(R.string.upd_new_stable, r.version ?: r.tag)
    Channel.NIGHTLY -> stringResource(R.string.upd_new_preview)
    Channel.TEST -> stringResource(R.string.upd_new_test, r.pr)
}

/** A PR title as a sentence: "fix(steam): keep the client alive" -> "Keep the client alive". */
private fun changeTitle(t: String): String =
    t.replace(Regex("""^[a-z]+(\([^)]*\))?!?:\s*"""), "").replaceFirstChar { it.uppercase() }

@Composable
private fun megabytes(bytes: Long) = stringResource(R.string.upd_mb, ((bytes + 524_288) / 1_048_576).toInt())

/** The three channels as cards to pick from; Test builds opens its list of PRs under it. */
@Composable
private fun ChannelLabel() {
    Text(
        stringResource(R.string.upd_channel), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, top = 2.dp, bottom = 10.dp),
    )
}

@Composable
private fun ChannelPicker(u: UpdatesState, ua: UpdatesActions) {
    val colors = MaterialTheme.colorScheme
    val catalog = u.catalog
    val tests = catalog?.tests.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChannelCard(
            Icons.Outlined.Verified, stringResource(R.string.upd_stable), stringResource(R.string.upd_stable_hint),
            catalog?.stable?.let { stringResource(R.string.upd_version_ago, it.version ?: it.tag, ago(it.publishedAt)) }, u.follow.channel == Channel.STABLE,
        ) { ua.onFollow(Follow(Channel.STABLE)) }
        ChannelCard(
            Icons.Outlined.Bolt, stringResource(R.string.upd_preview), stringResource(R.string.upd_preview_hint),
            catalog?.preview?.let { ago(it.publishedAt) }, u.follow.channel == Channel.NIGHTLY,
        ) { ua.onFollow(Follow(Channel.NIGHTLY)) }
        ChannelCard(
            Icons.Outlined.Science, stringResource(R.string.upd_tests), stringResource(R.string.upd_tests_hint),
            if (tests.isEmpty()) stringResource(R.string.upd_tests_none) else pluralStringResource(R.plurals.upd_tests_count, tests.size, tests.size),
            u.follow.channel == Channel.TEST, enabled = tests.isNotEmpty() || u.follow.channel == Channel.TEST,
        ) { tests.firstOrNull()?.let { ua.onFollow(Follow(Channel.TEST, it.pr)) } }
        AnimatedVisibility(u.follow.channel == Channel.TEST && tests.isNotEmpty(), enter = expandVertically(Motion.sp(1f)) + fadeIn(Motion.sp(1f)), exit = shrinkVertically(Motion.sp(1f)) + fadeOut(Motion.sp(1f))) {
            Column(Modifier.padding(start = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tests.forEach { t -> TestRow(t, u.follow.pr == t.pr) { ua.onFollow(Follow(Channel.TEST, t.pr)) } }
            }
        }
    }
}

/** Read-only Preview history below the status card action; only the latest build is installable. */
@Composable
private fun PreviewHistory(catalog: AppUpdates.Catalog?, me: AppUpdates.Installed) {
    val history = catalog?.let { AppUpdates.previewHistory(it, me) } ?: return
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val key = catalog?.recentPreviews?.firstOrNull()?.commit
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    val heading = when (history.kind) {
        AppUpdates.PreviewHistoryKind.CURRENT -> stringResource(R.string.upd_preview_current)
        AppUpdates.PreviewHistoryKind.BEHIND -> pluralStringResource(
            R.plurals.upd_preview_behind, history.buildsBehind, history.buildsBehind,
        )
        AppUpdates.PreviewHistoryKind.RECENT -> pluralStringResource(
            R.plurals.upd_preview_recent, history.changes.size, history.changes.size,
        )
    }
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Text(heading, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = colors.onSurfaceVariant)
        if (history.changes.isNotEmpty()) {
            val visible = if (expanded) history.changes else history.changes.take(3)
            Column(Modifier.padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                visible.forEach { change ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                changeTitle(change.title.ifBlank { change.commit.take(7) }),
                                fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            Text(ago(change.publishedAt), fontSize = 11.sp, color = colors.onSurfaceVariant)
                        }
                        if (change.summary.isNotBlank()) Text(
                            change.summary, fontSize = 11.5.sp, color = colors.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (history.changes.size > 3 || expanded) {
                val src = remember { MutableInteractionSource() }
                val hot = rememberHot(src)
                val toggle = { expanded = !expanded }
                Text(
                    if (expanded) stringResource(R.string.upd_less) else stringResource(R.string.upd_more),
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.signal,
                    modifier = Modifier.padding(top = 1.dp).offset(x = (-6).dp).paneItem("preview:history:more")
                        .clip(Shape12).glideBorder(hot, Shape12, pal.signal)
                        .hoverable(src).clickable(interactionSource = src, indication = null, role = Role.Button, onClick = toggle)
                        .controllerConfirm(onClick = toggle)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ChannelCard(
    icon: ImageVector, title: String, hint: String, latest: String?, selected: Boolean,
    enabled: Boolean = true, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    // Focus shows at once, as on the Components page: no ripple, no fade. Selection is the radio
    // dot, never a ring of its own: the ring is only ever focus.
    val fill = if (hot) pal.signal.copy(alpha = 0.14f) else Color.Transparent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().paneItem("channel:$title")
            .clip(Shape14).background(colors.surface).background(fill).glideBorder(hot, Shape14, pal.signal, pal.line)
            .alpha(if (enabled) 1f else 0.55f)
            .hoverable(src)
            .clickable(interactionSource = src, indication = null, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .controllerConfirm(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(38.dp).clip(Shape12).background(if (selected) pal.signal.copy(alpha = 0.18f) else colors.surfaceVariant)) {
            Icon(icon, contentDescription = null, tint = if (selected) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                if (latest != null) Text(latest, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (selected) pal.signal else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(hint, fontSize = 12.5.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RadioDot(selected)
    }
}

/** The one mark of a chosen option: a filled dot in a ring, beside it. */
@Composable
private fun RadioDot(selected: Boolean, size: androidx.compose.ui.unit.Dp = 22.dp) {
    val pal = LocalPalette.current
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size).clip(CircleShape).border(2.dp, if (selected) pal.signal else pal.line2, CircleShape)) {
        if (selected) Box(Modifier.size(size / 2).clip(CircleShape).background(pal.signal))
    }
}

/** One test build under Test builds: its PR, what it fixes, and how fresh it is. */
@Composable
private fun TestRow(t: Release, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().paneItem("test:${t.pr}")
            .heightIn(min = 52.dp)
            .clip(Shape12)
            .background(colors.surface).background(if (hot) pal.signal.copy(alpha = 0.14f) else Color.Transparent)
            .glideBorder(hot, Shape12, pal.signal, pal.line)
            .hoverable(src)
            .clickable(interactionSource = src, indication = null, role = Role.RadioButton, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text("#${t.pr}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (selected) pal.signal else colors.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(t.title.ifBlank { stringResource(R.string.upd_pr, t.pr) }, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (t.apk == null) stringResource(R.string.upd_not_built) else stringResource(R.string.upd_updated, ago(t.publishedAt)), fontSize = 12.sp, color = colors.onSurfaceVariant)
        }
        RadioDot(selected, 20.dp)
    }
}

@Composable
private fun channelName(f: Follow) = when (f.channel) {
    Channel.STABLE -> stringResource(R.string.upd_stable)
    Channel.NIGHTLY -> stringResource(R.string.upd_preview)
    Channel.TEST -> stringResource(R.string.upd_test_name, f.pr)
}

@Composable
private fun ago(millis: Long): String {
    if (millis <= 0) return stringResource(R.string.upd_while_ago)
    if (System.currentTimeMillis() - millis in 0 until 60_000) return stringResource(R.string.upd_just_now)
    return android.text.format.DateUtils.getRelativeTimeSpanString(
        millis, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}
