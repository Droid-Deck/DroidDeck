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

/** Where things stand, as a system updater says it: a status line, what you'd get, and one button. */
@Composable
private fun StatusPanel(s: FrontEndState, u: UpdatesState, ua: UpdatesActions, me: AppUpdates.Installed) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val catalog = u.catalog
    val release = catalog?.let { AppUpdates.release(it, u.follow) }
    val offer = if (me.ci) catalog?.let { AppUpdates.offer(it, u.follow, me) } else null
    val name = channelName(u.follow)
    val offered = release != null && (offer == Offer.UPDATE || offer == Offer.SWITCH)
    class Look(val tint: Color, val status: String, val headline: String, val detail: String?)
    val look = when {
        !me.ci -> Look(colors.onSurfaceVariant, "Local build", "Built on a computer",
            "It's signed differently from the builds here, so Android won't update it in place. Uninstall it to switch.")
        catalog == null -> Look(colors.onSurfaceVariant, if (u.checking) "Checking…" else "Not checked yet", "Updates", null)
        offer == Offer.UPDATE -> Look(AttentionAmber, "Update available", newBuild(u.follow, release!!), null)
        offer == Offer.SWITCH -> Look(pal.signal, "Ready to switch", newBuild(u.follow, release!!), null)
        offer == Offer.AHEAD -> Look(pal.signal, "Ahead of Stable", "You're ahead of Stable",
            "This build is newer than the last Stable release. You'll move onto Stable with its next one.")
        offer == Offer.GONE -> Look(AttentionAmber, "Test ended", "This test has ended",
            "Its fix was merged or dropped. Follow Nightly to keep getting the newest fixes.")
        else -> Look(pal.good, "Up to date", "You have the latest $name", null)
    }
    Column(
        modifier = Modifier.fillMaxWidth().clip(Shape16).background(colors.surface).border(1.dp, pal.line, Shape16).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(look.tint))
            Text(look.status, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = look.tint)
        }
        Text(look.headline, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, modifier = Modifier.padding(top = 6.dp))
        if (offered) {
            Text("Published ${ago(release!!.publishedAt)}", fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            Text(changeTitle(release.title.ifBlank { release.tag }), fontSize = 15.sp, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp))
            if (release.summary.isNotBlank()) Text(release.summary, fontSize = 13.5.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        if (look.detail != null) Text(look.detail, fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        if (u.error != null) Text(u.error, fontSize = 13.sp, color = pal.error, modifier = Modifier.padding(top = 12.dp))
        val installable = release?.apk != null && !s.sessionRunning
        val button = release?.apk?.size?.takeIf { it > 0 }?.let { " · ${megabytes(it)}" }.orEmpty()
        Box(Modifier.padding(top = 18.dp)) {
            when {
                u.stage != null -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (u.percent >= 0) "${u.stage}… ${u.percent}%" else "${u.stage}…", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                    if (u.percent >= 0) LinearProgressIndicator(progress = { u.percent / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
                }
                offer == Offer.UPDATE -> PrimaryButton("Update$button", enabled = installable, main = true) { ua.onInstall(release!!) }
                offer == Offer.SWITCH -> PrimaryButton("Install$button", enabled = installable, main = true) { ua.onInstall(release!!) }
                offer == Offer.AHEAD -> SecondaryButton("Install Stable ${release?.version.orEmpty()} anyway", enabled = installable) { ua.onInstall(release!!) }
                offer == Offer.GONE -> PrimaryButton("Follow Nightly", main = true) { ua.onFollow(Follow(Channel.NIGHTLY)) }
            }
        }
        if (s.sessionRunning && (offered || offer == Offer.AHEAD)) {
            Text("Stop the running session to update.", fontSize = 12.5.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        Box(Modifier.padding(top = 18.dp, bottom = 12.dp).fillMaxWidth().height(1.dp).background(pal.line))
        Text(runningLine(catalog, me), fontSize = 13.sp, color = colors.onSurfaceVariant)
    }
}

/** The headline for a build on offer: "DroidDeck 0.3.0", "New Nightly build", "PR #93 test build". */
private fun newBuild(f: Follow, r: Release) = when (f.channel) {
    Channel.STABLE -> "DroidDeck ${r.version ?: r.tag}"
    Channel.NIGHTLY -> "New Nightly build"
    Channel.TEST -> "PR #${r.pr} test build"
}

/** A PR title as a sentence: "fix(steam): keep the client alive" -> "Keep the client alive". */
private fun changeTitle(t: String): String =
    t.replace(Regex("""^[a-z]+(\([^)]*\))?!?:\s*"""), "").replaceFirstChar { it.uppercase() }

private fun megabytes(bytes: Long) = "${(bytes + 524_288) / 1_048_576} MB"

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

/** What is running, in plain words: "You have Stable 0.2.0", "You have Nightly from 3 hours ago". */
private fun runningLine(catalog: AppUpdates.Catalog?, me: AppUpdates.Installed): String {
    val from = if (me.committedAt > 0) " from ${ago(me.committedAt)}" else ""
    return when {
        !me.ci -> "You have DroidDeck ${me.version}$from"
        me.pr != 0 -> "You have the PR #${me.pr} test build$from"
        catalog?.stable?.let { AppUpdates.isRunning(it, me) } == true -> "You have Stable ${catalog.stable.version ?: catalog.stable.tag}"
        else -> "You have Nightly$from"
    }
}

private fun ago(millis: Long): String {
    if (millis <= 0) return "a while ago"
    if (System.currentTimeMillis() - millis in 0 until 60_000) return "just now"
    return android.text.format.DateUtils.getRelativeTimeSpanString(
        millis, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}
