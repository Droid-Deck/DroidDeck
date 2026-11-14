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
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
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
import com.droiddeck.launcher.ui.GlideAlertDialog as AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.update.AppUpdates
import com.droiddeck.launcher.update.AppUpdates.Channel
import com.droiddeck.launcher.update.AppUpdates.Follow
import com.droiddeck.launcher.update.AppUpdates.Offer
import com.droiddeck.launcher.update.AppUpdates.Release

class UpdatesState(
    val catalog: AppUpdates.Catalog? = null,
    val follow: Follow = Follow(Channel.STABLE),
    val checking: Boolean = false,
    val error: String? = null,
    val stage: String? = null,
    val percent: Int = -1,
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
    val onSeen: (Release) -> Unit = {},
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun UpdatesPage(s: FrontEndState, a: FrontEndActions, modifier: Modifier) {
    val host = rememberMenuHost()
    val u = s.updates
    val ua = a.updates
    val me = remember { AppUpdates.installed() }
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        ) {
            Text(
                stringResource(R.string.upd_build, me.version, s.buildLabel), fontSize = 12.5.sp, color = colors.onSurfaceVariant,
                maxLines = 1, modifier = Modifier.weight(1f)
                    .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 700, velocity = 40.dp),
            )
            val checked = u.catalog?.checkedAt?.takeIf { it > 0 }
            if (!LocalNarrowPane.current) Text(
                if (u.checking) stringResource(R.string.common_checking) else if (checked != null) stringResource(R.string.upd_checked, ago(checked).replaceFirstChar { it.lowercase() }) else stringResource(R.string.upd_not_checked),
                fontSize = 13.sp, color = colors.onSurfaceVariant,
            )
            val clipboard = LocalClipboardManager.current
            val context = LocalContext.current
            val details = buildDetails(me, s.buildLabel)
            ToolIcon(Icons.Outlined.ContentCopy, stringResource(R.string.upd_copy_build)) {
                clipboard.setText(AnnotatedString(details))
                android.widget.Toast.makeText(context, context.getString(R.string.upd_copied), android.widget.Toast.LENGTH_SHORT).show()
            }
            ToolIcon(Icons.Outlined.Refresh, stringResource(R.string.store_check_updates), busy = u.checking, enabled = !u.checking && u.stage == null, onClick = ua.onCheck)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val stacked = LocalNarrowPane.current || maxWidth < 600.dp
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                if (stacked) {
                    StatusPanel(s, u, ua, me)
                    Box(Modifier.height(18.dp))
                    ChannelLabel()
                    ChannelPicker(u, ua, s.sessionRunning)
                } else {
                    ChannelLabel()
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Box(Modifier.weight(1f)) { ChannelPicker(u, ua, s.sessionRunning) }
                        Box(Modifier.weight(1.15f)) { StatusPanel(s, u, ua, me) }
                    }
                }
                Box(Modifier.padding(top = 18.dp)) {
                    ToggleRow(
                        host, "build-watermark", stringResource(R.string.setup_build_watermark),
                        stringResource(R.string.setup_build_watermark_hint), s.buildWatermark,
                    ) { a.onBuildWatermark() }
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
        confirmButton = { TextButton(onClick = ua.onAllowInstalls, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.upd_open_settings)) } },
        dismissButton = { TextButton(onClick = ua.onDismissPermission, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.upd_not_now)) } },
    )
}

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
        offer == Offer.AHEAD && u.follow.channel == Channel.NIGHTLY -> Look(pal.signal, stringResource(R.string.upd_ahead_preview_status),
            stringResource(R.string.upd_ahead_preview_headline), stringResource(R.string.upd_ahead_preview_detail))
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
            ReleaseFacts(release)
        }
        if (release != null && u.follow.channel == Channel.TEST && catalog != null) {
            androidx.compose.runtime.LaunchedEffect(release.tag, release.commit, release.apk?.sha256) { ua.onSeen(release) }
            if (!offered) ReleaseFacts(release)
            TestFreshness(catalog, release)
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
                offer == Offer.AHEAD && u.follow.channel == Channel.STABLE && installBlock == null ->
                    SecondaryButton(stringResource(R.string.upd_install_anyway, release?.version.orEmpty()), enabled = installable) { ua.onInstall(release!!) }
                offer == Offer.GONE -> PrimaryButton(stringResource(R.string.upd_follow_preview), main = true) { ua.onFollow(Follow(Channel.NIGHTLY)) }
            }
        }
        if (s.sessionRunning && (offered || offer == Offer.AHEAD)) {
            Text(stringResource(R.string.upd_stop_session), fontSize = 12.5.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        if (u.follow.channel == Channel.NIGHTLY) {
            PreviewHistory(catalog, me, s, u, ua)
        }
    }
}

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

@Composable
private fun newBuild(f: Follow, r: Release) = when (f.channel) {
    Channel.STABLE -> stringResource(R.string.upd_new_stable, r.version ?: r.tag)
    Channel.NIGHTLY -> stringResource(R.string.upd_new_preview)
    Channel.TEST -> stringResource(R.string.upd_new_test, r.pr)
}

private fun changeTitle(t: String): String =
    t.replace(Regex("""^[a-z]+(\([^)]*\))?!?:\s*"""), "").replaceFirstChar { it.uppercase() }

@Composable
private fun megabytes(bytes: Long) = stringResource(R.string.upd_mb, ((bytes + 524_288) / 1_048_576).toInt())

@Composable
private fun ChannelLabel() {
    Text(
        stringResource(R.string.upd_channel), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, top = 2.dp, bottom = 10.dp),
    )
}

@Composable
private fun ChannelPicker(u: UpdatesState, ua: UpdatesActions, sessionRunning: Boolean) {
    val colors = MaterialTheme.colorScheme
    val catalog = u.catalog
    val tests = catalog?.tests.orEmpty()
    val me = remember { AppUpdates.installed() }
    val ready = tests.count { me.updatable && AppUpdates.installBlock(it, me) == null }
    var showTests by rememberSaveable { mutableStateOf(false) }
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
            if (tests.isEmpty()) stringResource(R.string.upd_tests_none) else pluralStringResource(R.plurals.upd_tests_count, ready, ready),
            u.follow.channel == Channel.TEST, enabled = tests.isNotEmpty() || u.follow.channel == Channel.TEST,
        ) { showTests = !showTests }
        AnimatedVisibility((showTests || u.follow.channel == Channel.TEST) && tests.isNotEmpty(), enter = expandVertically(Motion.sp(1f)) + fadeIn(Motion.sp(1f)), exit = shrinkVertically(Motion.sp(1f)) + fadeOut(Motion.sp(1f))) {
            TestList(tests, me, u, ua)
        }
        val context = androidx.compose.ui.platform.LocalContext.current
        val agentReady = com.droiddeck.launcher.agent.AgentHost.installed &&
            (com.droiddeck.launcher.agent.AgentSetup.ready || com.droiddeck.launcher.agent.AgentSetup.completed(context))
        LabBuilds(sessionRunning, onRebuild = if (agentReady) { info ->
            com.droiddeck.launcher.agent.AgentHost.send(context, context.getString(R.string.agent_rebuild_lab, info.label))
            context.startActivity(android.content.Intent(context, com.droiddeck.launcher.FrontEndActivity::class.java)
                .putExtra(com.droiddeck.launcher.stores.download.StoreDownloadService.EXTRA_NAV, "agent")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } else null)
    }
}

@Composable
private fun PreviewHistory(catalog: AppUpdates.Catalog?, me: AppUpdates.Installed, s: FrontEndState, u: UpdatesState, ua: UpdatesActions) {
    val history = catalog?.let { AppUpdates.previewHistory(it, me) } ?: return
    val colors = MaterialTheme.colorScheme
    val heading = when (history.kind) {
        AppUpdates.PreviewHistoryKind.CURRENT -> stringResource(R.string.upd_preview_current)
        AppUpdates.PreviewHistoryKind.BEHIND -> pluralStringResource(
            R.plurals.upd_preview_behind, history.buildsBehind, history.buildsBehind,
        )
        AppUpdates.PreviewHistoryKind.RECENT -> pluralStringResource(
            R.plurals.upd_preview_recent, maxOf(history.builds.size, history.changes.size), maxOf(history.builds.size, history.changes.size),
        )
    }
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Text(heading, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = colors.onSurfaceVariant)
        if (history.builds.isNotEmpty()) {
            Text(stringResource(R.string.upd_preview_pick), fontSize = 11.5.sp, color = colors.onSurfaceVariant)
            PreviewBuilds(history, me, s, u, ua)
        } else if (history.changes.isNotEmpty()) {
            LegacyChanges(history.changes)
        }
    }
}

@Composable
private fun PreviewBuilds(history: AppUpdates.PreviewHistory, me: AppUpdates.Installed, s: FrontEndState, u: UpdatesState, ua: UpdatesActions) {
    val builds = history.builds
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var shown by rememberSaveable(builds.firstOrNull()?.commit) {
        mutableIntStateOf(maxOf(5, if (history.installedIndex >= 0) history.installedIndex + 2 else 0))
    }
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        builds.take(shown).forEachIndexed { index, build ->
            PreviewBuildRow(
                build, index, history.installedIndex, me, s, u, ua,
                expanded = open == build.commit,
            ) { open = if (open == build.commit) null else build.commit }
        }
    }
    val more = builds.size - shown
    if (more > 0) MoreLink(stringResource(R.string.upd_show_more, more), "preview:history:more") { shown += 20 }
    else if (shown > 5) MoreLink(stringResource(R.string.upd_less), "preview:history:less") { shown = 5 }
}

@Composable
private fun PreviewBuildRow(
    build: Release, index: Int, installedIndex: Int, me: AppUpdates.Installed,
    s: FrontEndState, u: UpdatesState, ua: UpdatesActions, expanded: Boolean, onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val installed = index == installedIndex
    val block = AppUpdates.installBlock(build, me)
    val tooOld = block != null && build.apk != null && build.apk.versionCode < me.versionCode
    Column(
        Modifier.fillMaxWidth().clip(Shape12)
            .background(if (hot) pal.signal.copy(alpha = 0.12f) else if (expanded) colors.surfaceVariant else Color.Transparent)
            .alpha(if (tooOld && !expanded) 0.55f else 1f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().paneItem("preview:${build.commit}")
                .glideBorder(hot, Shape12, pal.signal)
                .hoverable(src).clickable(interactionSource = src, indication = null, role = Role.Button, onClick = onToggle)
                .controllerConfirm(onClick = onToggle)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    changeTitle(build.title.ifBlank { build.shortCommit }), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                    color = colors.onBackground, maxLines = if (expanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(ago(build.committedAt.takeIf { it > 0 } ?: build.publishedAt), build.author.takeIf { it.isNotEmpty() }?.let { "@$it" }, build.shortCommit)
                        .joinToString(" · "),
                    fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (installed) Chip(stringResource(R.string.upd_installed_tag), ok = true)
        }
        if (expanded) Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp)) {
            if (build.summary.isNotBlank()) Text(build.summary, fontSize = 12.5.sp, color = colors.onSurfaceVariant)
            ReleaseFacts(build)
            Box(Modifier.padding(top = 10.dp)) {
                when {
                    installed -> Unit
                    block != null -> Text(block.message(LocalContext.current), fontSize = 12.5.sp, color = AttentionAmber)
                    me.updatable -> {
                        val size = build.apk?.size?.takeIf { it > 0 }?.let { " · ${megabytes(it)}" }.orEmpty()
                        val label = if (installedIndex in 0 until index) stringResource(R.string.upd_rollback_this, size)
                        else stringResource(R.string.upd_install_this, size)
                        SecondaryButton(label, enabled = u.stage == null && !s.sessionRunning, compact = true) { ua.onInstall(build) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LegacyChanges(changes: List<AppUpdates.PreviewChange>) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        changes.take(10).forEach { change ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    changeTitle(change.title.ifBlank { change.commit.take(7) }),
                    fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(ago(change.publishedAt), fontSize = 11.sp, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MoreLink(text: String, id: String, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.signal,
        modifier = Modifier.padding(top = 2.dp).offset(x = (-6).dp).paneItem(id)
            .clip(Shape12).glideBorder(hot, Shape12, pal.signal)
            .hoverable(src).clickable(interactionSource = src, indication = null, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReleaseFacts(r: Release) {
    val colors = MaterialTheme.colorScheme
    val facts = listOfNotNull(
        r.author.takeIf { it.isNotEmpty() }?.let { "@$it" },
        stringResource(R.string.upd_version_code, r.versionCode),
    ).joinToString(" · ")
    Text(facts, fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (r.linkedPr > 0) LinkPill(stringResource(R.string.upd_pr, r.linkedPr), "https://github.com/Droid-Deck/DroidDeck/pull/${r.linkedPr}")
        if (r.commit.length >= 7) LinkPill(stringResource(R.string.upd_link_commit, r.shortCommit), "https://github.com/Droid-Deck/DroidDeck/commit/${r.commit}")
        if (r.url.startsWith("https://github.com/Droid-Deck/")) LinkPill(stringResource(R.string.upd_link_release), r.url)
    }
}

@Composable
private fun TestFreshness(catalog: AppUpdates.Catalog, r: Release) {
    val colors = MaterialTheme.colorScheme
    if (r.newerCommitPushed) Text(
        stringResource(R.string.upd_newer_commit, r.headCommit.take(7)), fontSize = 12.5.sp, color = AttentionAmber, modifier = Modifier.padding(top = 8.dp),
    )
    val behind = AppUpdates.previewsSince(catalog, r)
    if (behind > 0) Text(
        stringResource(R.string.upd_main_moved, behind), fontSize = 12.5.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun LinkPill(label: String, url: String) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val open = { openUrl(ctx, url) }
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.paneItem("link:$url").heightIn(min = 36.dp).offset(x = (-8).dp).clip(Shape12)
            .background(if (hot) pal.signal.copy(alpha = 0.14f) else Color.Transparent)
            .glideBorder(hot, Shape12, pal.signal)
            .hoverable(src).clickable(interactionSource = src, indication = null, role = Role.Button, onClick = open)
            .controllerConfirm(onClick = open).padding(horizontal = 8.dp),
    ) {
        val tint = pal.signal
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = tint, maxLines = 1)
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = tint.copy(alpha = 0.65f), modifier = Modifier.size(11.dp))
    }
}

@Composable
private fun TestList(tests: List<Release>, me: AppUpdates.Installed, u: UpdatesState, ua: UpdatesActions) {
    val colors = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    val needle = query.trim().removePrefix("#").removePrefix("@")
    val matches = tests.filter {
        needle.isEmpty() || it.pr.toString().startsWith(needle) || it.title.contains(needle, ignoreCase = true) ||
            it.author.contains(needle, ignoreCase = true) || (needle.length >= 4 && it.commit.startsWith(needle, ignoreCase = true))
    }
    val (ready, old) = matches.partition { it.versionCode >= me.versionCode }
    val followedOld = u.follow.channel == Channel.TEST && old.any { it.pr == u.follow.pr }
    var showOld by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(start = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (tests.size > 5 || query.isNotEmpty()) AdbTextField(
            value = query, onValueChange = { query = it }, label = stringResource(R.string.upd_tests_find),
            keyboardType = KeyboardType.Text, imeAction = ImeAction.Search, compact = true, showLabel = false,
            placeholder = stringResource(R.string.upd_tests_find), modifier = Modifier.fillMaxWidth(),
        )
        if (matches.isEmpty()) Text(stringResource(R.string.upd_tests_no_match), fontSize = 12.5.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(4.dp))
        ready.forEach { t -> TestRow(t, u.follow.pr == t.pr && u.follow.channel == Channel.TEST) { ua.onFollow(Follow(Channel.TEST, t.pr)) } }
        if (old.isNotEmpty()) {
            MoreLink(stringResource(R.string.upd_tests_old, old.size, me.versionCode), "tests:old") { showOld = !showOld }
            if (showOld || followedOld || needle.isNotEmpty()) old.forEach { t ->
                TestRow(t, u.follow.pr == t.pr && u.follow.channel == Channel.TEST, tooOld = true) { ua.onFollow(Follow(Channel.TEST, t.pr)) }
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

@Composable
private fun RadioDot(selected: Boolean, size: androidx.compose.ui.unit.Dp = 22.dp) {
    val pal = LocalPalette.current
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size).clip(CircleShape).border(2.dp, if (selected) pal.signal else pal.line2, CircleShape)) {
        if (selected) Box(Modifier.size(size / 2).clip(CircleShape).background(pal.signal))
    }
}

@Composable
private fun TestRow(t: Release, selected: Boolean, tooOld: Boolean = false, onClick: () -> Unit) {
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
            .alpha(if (tooOld && !selected) 0.6f else 1f)
            .hoverable(src)
            .clickable(interactionSource = src, indication = null, role = Role.RadioButton, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text("#${t.pr}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (selected) pal.signal else colors.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(changeTitle(t.title.ifBlank { stringResource(R.string.upd_pr, t.pr) }), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = when {
                t.apk == null -> stringResource(R.string.upd_not_built)
                tooOld -> stringResource(R.string.upd_tests_old_row, t.versionCode)
                else -> stringResource(R.string.upd_updated, ago(t.builtAt.takeIf { it > 0 } ?: t.publishedAt))
            }
            Text(
                listOfNotNull(detail, t.author.takeIf { it.isNotEmpty() }?.let { "@$it" }, t.shortCommit).joinToString(" · "),
                fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (t.newerCommitPushed) Text(stringResource(R.string.upd_newer_commit_short), fontSize = 12.sp, color = AttentionAmber, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RadioDot(selected, 20.dp)
    }
}

private fun buildDetails(me: AppUpdates.Installed, label: String): String = buildString {
    append("DroidDeck ").append(me.version).append(" (versionCode ").append(me.versionCode).append(")\n")
    append(label)
    if (me.pr > 0) append("\nPR #").append(me.pr).append(" https://github.com/Droid-Deck/DroidDeck/pull/").append(me.pr)
    if (me.commit.length >= 7) append("\nCommit ").append(me.commit)
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
