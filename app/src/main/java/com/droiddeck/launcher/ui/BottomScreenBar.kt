package com.droiddeck.launcher.ui

import android.content.Context
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState

internal class BarExtra(
    val description: String,
    val icon: Int? = null,
    val vector: ImageVector? = null,
    val active: Boolean = false,
    val tag: String? = null,
    val onClick: () -> Unit,
)

internal class BottomBarModel(
    val title: String?,
    val onClose: (() -> Unit)?,
    val onMenu: (() -> Unit)?,
    val onSteam: (() -> Unit)?,
    val onQam: (() -> Unit)?,
    val extras: List<BarExtra> = emptyList(),
    val menuActive: Boolean = false,
)

@Composable
internal fun BottomScreenBar(model: BottomBarModel, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.fillMaxWidth().height(BAR_HEIGHT).blockTouches()) {
        val buttons = listOfNotNull(model.onClose, model.onMenu, model.onQam).size + model.extras.size
        val steamWidth = if (model.onSteam != null) STEAM_WIDTH + BAR_GAP else 0.dp
        val titleWidth = if (model.title != null) MIN_TITLE + BAR_GAP else 0.dp
        val needed = BAR_PAD * 2 + BUTTON * buttons + BAR_GAP * (buttons - 1).coerceAtLeast(0) + steamWidth + titleWidth
        val fits = maxWidth >= needed
        Row(
            Modifier.fillMaxSize().then(if (fits) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                .padding(horizontal = BAR_PAD, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BAR_GAP),
        ) {
            model.onClose?.let {
                BarButton(stringResource(R.string.common_close), Modifier.testTag("bottom-bar-close"), vector = Icons.Outlined.Close, onClick = it)
            }
            if (model.title != null) Text(
                model.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = (if (fits) Modifier.weight(1f) else Modifier).padding(start = 4.dp),
            ) else if (fits) Spacer(Modifier.weight(1f))
            model.extras.forEach { extra ->
                BarButton(extra.description, extra.tag?.let { Modifier.testTag(it) } ?: Modifier, icon = extra.icon, vector = extra.vector,
                    active = extra.active, onClick = extra.onClick)
            }
            BarActions(model.onMenu, model.menuActive, model.onSteam, model.onQam)
        }
    }
}

@Composable
internal fun BarActions(
    onMenu: (() -> Unit)?,
    menuActive: Boolean,
    onSteam: (() -> Unit)?,
    onQam: (() -> Unit)?,
    menuModifier: Modifier = Modifier,
    steamModifier: Modifier = Modifier,
    qamModifier: Modifier = Modifier,
    qamInteraction: MutableInteractionSource? = null,
    onQamConfirm: (() -> Unit)? = null,
    showShortcuts: Boolean = false,
) {
    onMenu?.let {
        BarButton(stringResource(R.string.touch_droiddeck_menu), menuModifier.testTag("bottom-bar-menu"), icon = R.drawable.ic_stat_session,
            active = menuActive, onClick = it)
    }
    onSteam?.let {
        BarButton(stringResource(R.string.drawer_steam_menu), steamModifier.testTag("bottom-bar-steam"), label = "STEAM",
            shortcut = if (showShortcuts) "⧉" else null, onClick = it)
    }
    onQam?.let {
        BarButton(stringResource(R.string.drawer_open_qam), qamModifier.testTag("bottom-bar-qam"), vector = Icons.Outlined.MoreHoriz,
            shortcut = if (showShortcuts) "☰" else null,
            interactionSource = qamInteraction ?: remember { MutableInteractionSource() }, onConfirm = onQamConfirm, onClick = it)
    }
}

@Composable
internal fun BarButton(
    description: String,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    vector: ImageVector? = null,
    label: String? = null,
    active: Boolean = false,
    danger: Boolean = false,
    shortcut: String? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    onConfirm: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val hot = interactionSource.collectIsFocusedAsState().value || interactionSource.collectIsHoveredAsState().value
    val shape = RoundedCornerShape(22.dp)
    val accent = if (danger) colors.error else pal.signal
    val tint = when {
        active -> pal.onSignal
        hot || danger -> accent
        else -> colors.onBackground
    }
    val fill = when {
        active -> pal.signal
        hot -> accent.copy(alpha = 0.14f)
        else -> Color.Transparent
    }
    val sized = if (label != null) Modifier.height(44.dp).widthIn(min = 44.dp) else Modifier.size(44.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.then(sized).semantics { contentDescription = description }
            .clip(shape).background(fill)
            .glideBorder(hot, shape, accent, if (active) pal.signal else if (danger) colors.error.copy(alpha = 0.6f) else pal.line)
            .hoverable(interactionSource)
            .clickable(role = Role.Button, interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick)
            .controllerConfirm(onClick = onConfirm ?: onClick),
    ) {
        when {
            label != null -> Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = tint,
                maxLines = 1, modifier = Modifier.padding(horizontal = 14.dp))
            icon != null -> Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            vector != null -> Icon(vector, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        if (shortcut != null) Text(
            shortcut, fontSize = 8.sp, lineHeight = 9.sp, color = colors.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 6.dp),
        )
    }
}

internal val BAR_HEIGHT = 64.dp
private val BAR_PAD = 12.dp
private val BAR_GAP = 8.dp
private val BUTTON = 44.dp
private val STEAM_WIDTH = 80.dp
private val MIN_TITLE = 72.dp

internal fun sessionBarModel(
    title: String?,
    onClose: (() -> Unit)?,
    onMenu: (() -> Unit)?,
    onSteam: () -> Unit,
    onQam: () -> Unit,
    extras: List<BarExtra> = emptyList(),
): BottomBarModel {
    val steam = SessionState.mode == SessionService.MODE_STEAM
    return BottomBarModel(title, onClose, onMenu, onSteam.takeIf { steam }, onQam.takeIf { steam }, extras)
}

internal fun bottomScreenBarView(context: Context, model: () -> BottomBarModel): ComposeView = ComposeView(context).apply {
    setContent { DroidDeckTheme { BottomScreenBar(model()) } }
}
