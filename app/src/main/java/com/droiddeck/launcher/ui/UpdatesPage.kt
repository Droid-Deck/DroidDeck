package com.droiddeck.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.remember
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
        PageHeader("Updates") {
            Box(Modifier.weight(1f))
            val checked = u.catalog?.checkedAt?.takeIf { it > 0 }
            if (!LocalNarrowPane.current) Text(
                if (u.checking) "Checking…" else if (checked != null) "Checked ${ago(checked)}" else "Not checked yet",
                fontSize = 13.sp, color = colors.onSurfaceVariant,
            )
            SecondaryButton("Check now", enabled = !u.checking && u.stage == null, compact = true, onClick = ua.onCheck)
        }
        Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            // Landscape has the width for channels and status side by side; a narrow pane stacks them.
            if (LocalNarrowPane.current) {
                StatusPanel(s, u, ua, me)
                Box(Modifier.height(18.dp))
                ChannelPicker(u, ua)
            } else Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Box(Modifier.weight(1f)) { ChannelPicker(u, ua) }
                Box(Modifier.weight(1.15f)) { StatusPanel(s, u, ua, me) }
            }
            AboutFooter(s)
        }
    }
    if (u.askPermission) AlertDialog(
        onDismissRequest = ua.onDismissPermission,
        title = { Text("Let DroidDeck update itself") },
        text = {
            Text(
                "Android asks once before an app may install updates. On the next screen, turn on " +
                    "\"Allow from this source\", then come back - the update carries on by itself.",
            )
        },
        confirmButton = { TextButton(onClick = ua.onAllowInstalls) { Text("Open settings") } },
        dismissButton = { TextButton(onClick = ua.onDismissPermission) { Text("Not now") } },
    )
}

/** Where things stand, in one headline, what's new in a line or two, and at most one big button. */
@Composable
private fun StatusPanel(s: FrontEndState, u: UpdatesState, ua: UpdatesActions, me: AppUpdates.Installed) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val catalog = u.catalog
    val release = catalog?.let { AppUpdates.release(it, u.follow) }
    val offer = if (me.ci) catalog?.let { AppUpdates.offer(it, u.follow, me) } else null
    val name = channelName(u.follow)
    class Look(val icon: ImageVector, val tint: Color, val headline: String, val detail: String?)
    val look = when {
        !me.ci -> Look(Icons.Outlined.Code, colors.onSurfaceVariant, "Local build",
            "Built on a computer, so it's signed differently from the builds here. Uninstall it to switch to one.")
        catalog == null -> Look(Icons.Outlined.Sync, pal.signal, if (u.checking) "Checking for updates…" else "Not checked yet", null)
        offer == Offer.UPDATE -> Look(Icons.Outlined.Download, AttentionAmber, "Update ready", null)
        offer == Offer.SWITCH -> Look(Icons.Outlined.SwapHoriz, pal.signal, "Switch to $name", "Your games and settings stay as they are.")
        offer == Offer.AHEAD -> Look(Icons.Outlined.Info, pal.signal, "You're ahead of Stable", "You'll move onto Stable with its next release.")
        offer == Offer.GONE -> Look(Icons.Outlined.Info, AttentionAmber, "This test has ended", "Its fix was merged or dropped. Nightly keeps you on the newest fixes.")
        else -> Look(Icons.Outlined.CheckCircle, pal.good, "You're up to date", null)
    }
    val attention = offer == Offer.UPDATE
    Column(
        modifier = Modifier.fillMaxWidth().clip(Shape16)
            .background(if (attention) AttentionAmber.copy(alpha = 0.06f) else colors.surface)
            .border(1.dp, if (attention) AttentionAmber.copy(alpha = 0.35f) else pal.line, Shape16)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).clip(CircleShape).background(look.tint.copy(alpha = 0.16f))) {
                Icon(look.icon, contentDescription = null, tint = look.tint, modifier = Modifier.size(26.dp))
            }
            Column {
                Text(look.headline, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
                Text(runningLine(catalog, me), fontSize = 13.sp, color = colors.onSurfaceVariant)
            }
        }
        if (look.detail != null) Text(look.detail, fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp))
        // What the update brings: the change's title and one line from its notes.
        if (release != null && (offer == Offer.UPDATE || offer == Offer.SWITCH)) {
            Column(
                modifier = Modifier.padding(top = 14.dp).fillMaxWidth().clip(Shape12).background(colors.surfaceVariant).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("WHAT'S NEW · ${ago(release.publishedAt).uppercase()}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = colors.onSurfaceVariant)
                Text(release.title.ifBlank { release.tag }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (release.summary.isNotBlank()) Text(release.summary, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (u.error != null) Text(u.error, fontSize = 13.sp, color = pal.error, modifier = Modifier.padding(top = 12.dp))
        val installable = release?.apk != null && !s.sessionRunning
        Box(Modifier.padding(top = 18.dp)) {
            when {
                u.stage != null -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (u.percent >= 0) "${u.stage}… ${u.percent}%" else "${u.stage}…", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                    if (u.percent >= 0) LinearProgressIndicator(progress = { u.percent / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
                }
                offer == Offer.UPDATE -> PrimaryButton("Update now", enabled = installable, main = true, icon = Icons.Outlined.Download, modifier = Modifier.fillMaxWidth()) { ua.onInstall(release!!) }
                offer == Offer.SWITCH -> PrimaryButton("Switch to $name", enabled = installable, main = true, icon = Icons.Outlined.SwapHoriz, modifier = Modifier.fillMaxWidth()) { ua.onInstall(release!!) }
                offer == Offer.AHEAD -> SecondaryButton("Install Stable ${release?.version.orEmpty()} anyway", enabled = installable, modifier = Modifier.fillMaxWidth()) { ua.onInstall(release!!) }
                offer == Offer.GONE -> PrimaryButton("Follow Nightly", main = true, modifier = Modifier.fillMaxWidth()) { ua.onFollow(Follow(Channel.NIGHTLY)) }
            }
        }
        if (s.sessionRunning && (offer == Offer.UPDATE || offer == Offer.SWITCH || offer == Offer.AHEAD)) {
            Text("Stop the running session to update.", fontSize = 12.5.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** The three channels as cards to pick from; Test builds opens its list of PRs under it. */
@Composable
private fun ChannelPicker(u: UpdatesState, ua: UpdatesActions) {
    val colors = MaterialTheme.colorScheme
    val catalog = u.catalog
    val tests = catalog?.tests.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("UPDATE CHANNEL", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, top = 2.dp))
        ChannelCard(
            Icons.Outlined.Verified, "Stable", "Tested releases, for most people",
            catalog?.stable?.let { "${it.version ?: it.tag} · ${ago(it.publishedAt)}" }, u.follow.channel == Channel.STABLE,
        ) { ua.onFollow(Follow(Channel.STABLE)) }
        ChannelCard(
            Icons.Outlined.Bolt, "Nightly", "Newest fixes, the odd new bug",
            catalog?.nightly?.let { ago(it.publishedAt) }, u.follow.channel == Channel.NIGHTLY,
        ) { ua.onFollow(Follow(Channel.NIGHTLY)) }
        ChannelCard(
            Icons.Outlined.Science, "Test builds", "Try a fix before it's released",
            when (tests.size) { 0 -> "None right now"; 1 -> "1 to try"; else -> "${tests.size} to try" },
            u.follow.channel == Channel.TEST, enabled = tests.isNotEmpty() || u.follow.channel == Channel.TEST,
        ) { tests.firstOrNull()?.let { ua.onFollow(Follow(Channel.TEST, it.pr)) } }
        AnimatedVisibility(u.follow.channel == Channel.TEST && tests.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(start = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tests.forEach { t -> TestRow(t, u.follow.pr == t.pr) { ua.onFollow(Follow(Channel.TEST, t.pr)) } }
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
    val edge by animateColorAsState(if (hot || selected) pal.signal else pal.line, Motion.tw(200), label = "channelEdge")
    val fill by animateColorAsState(
        if (selected) pal.signal.copy(alpha = 0.10f) else if (hot) Color.White.copy(alpha = 0.05f) else colors.surface,
        Motion.tw(200), label = "channelFill",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().paneItem("channel:$title")
            .clip(Shape14).background(fill).border(if (hot) 2.dp else 1.dp, edge, Shape14)
            .alpha(if (enabled) 1f else 0.55f)
            .hoverable(src)
            .clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, role = Role.RadioButton, onClick = onClick)
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
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (selected) pal.signal else pal.line2, CircleShape)) {
            if (selected) Box(Modifier.size(11.dp).clip(CircleShape).background(pal.signal))
        }
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
            .background(if (selected) pal.signal.copy(alpha = 0.08f) else colors.surface)
            .border(if (hot) 2.dp else 1.dp, if (hot || selected) pal.signal else pal.line, Shape12)
            .hoverable(src)
            .clickable(interactionSource = src, indication = LocalIndication.current, role = Role.RadioButton, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text("#${t.pr}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (selected) pal.signal else colors.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(t.title.ifBlank { "PR #${t.pr}" }, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (t.apk == null) "Not built for this copy of DroidDeck" else "Updated ${ago(t.publishedAt)}", fontSize = 12.sp, color = colors.onSurfaceVariant)
        }
    }
}

/** The build's version and full label on one quiet line under everything else. */
@Composable
private fun AboutFooter(s: FrontEndState) {
    val colors = MaterialTheme.colorScheme
    Text(
        "DroidDeck ${AppUpdates.installed().version} · ${s.buildLabel}", fontSize = 12.5.sp, color = colors.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 20.dp, start = 2.dp),
    )
}

private fun channelName(f: Follow) = when (f.channel) {
    Channel.STABLE -> "Stable"
    Channel.NIGHTLY -> "Nightly"
    Channel.TEST -> "the PR #${f.pr} test"
}

/** What is running, in plain words: "On Stable 0.2.0", "On Nightly · built 3 hours ago", "On the PR #93 test build". */
private fun runningLine(catalog: AppUpdates.Catalog?, me: AppUpdates.Installed): String {
    val built = if (me.committedAt > 0) " · built ${ago(me.committedAt)}" else ""
    return when {
        !me.ci -> "DroidDeck ${me.version}$built"
        me.pr != 0 -> "On the PR #${me.pr} test build$built"
        catalog?.stable?.let { AppUpdates.isRunning(it, me) } == true -> "On Stable ${catalog.stable.version ?: catalog.stable.tag}"
        else -> "On Nightly$built"
    }
}

private fun ago(millis: Long): String {
    if (millis <= 0) return "a while ago"
    if (System.currentTimeMillis() - millis in 0 until 60_000) return "just now"
    return android.text.format.DateUtils.getRelativeTimeSpanString(
        millis, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}
