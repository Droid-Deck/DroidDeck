package com.droiddeck.launcher.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R

class BottomPage(
    val id: String,
    @StringRes val label: Int,
    val icon: ImageVector,
    val content: @Composable () -> Unit,
)

internal fun pickBottomPage(remembered: String?, ids: List<String>): String? =
    ids.firstOrNull { it == remembered } ?: ids.firstOrNull { it == DEFAULT_PAGE } ?: ids.firstOrNull()

internal object SessionBottomTab {
    private var session: String? = null
    private var tab: String? = null

    @Synchronized
    fun picked(sessionId: String?): String? = tab.takeIf { sessionId != null && sessionId == session }

    @Synchronized
    fun pick(sessionId: String?, id: String) {
        session = sessionId
        tab = id
    }
}

internal fun pageForGameStart(ids: List<String>): String? = GAME_PAGE.takeIf { it in ids }

private const val DEFAULT_PAGE = "telemetry"
private const val GAME_PAGE = "telemetry"
private val MIN_TAB = 80.dp

@Composable
internal fun BottomDashboard(
    pages: List<BottomPage>,
    remembered: String?,
    onPage: (String) -> Unit,
    bar: BottomBarModel? = null,
    gameStarts: Int = 0,
    strip: (@Composable (String?) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    var current by remember { mutableStateOf(pickBottomPage(remembered, pages.map { it.id })) }
    val startsWhenOpened = remember { gameStarts }
    LaunchedEffect(gameStarts) {
        if (gameStarts != startsWhenOpened) pageForGameStart(pages.map { it.id })?.let { current = it }
    }
    val page = pages.firstOrNull { it.id == current } ?: pages.firstOrNull()
    Column(Modifier.fillMaxSize().background(colors.background)) {
        bar?.let { BottomScreenBar(it) }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp).height(56.dp)) {
            val roomy = pages.isEmpty() || (maxWidth - 6.dp * (pages.size - 1)) / pages.size >= MIN_TAB
            Row(
                Modifier.fillMaxSize().then(if (roomy) Modifier else Modifier.horizontalScroll(rememberScrollState())),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                pages.forEach { p ->
                    PageTab(p, p.id == page?.id, if (roomy) Modifier.weight(1f) else Modifier.width(MIN_TAB)) {
                        current = p.id
                        onPage(p.id)
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            strip?.invoke(page?.id)
            Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 10.dp)) {
                if (page != null) page.content()
                else Text(stringResource(R.string.bottom_dashboard_empty), fontSize = 15.sp, color = colors.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun PageTab(page: BottomPage, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    val name = stringResource(page.label)
    Column(
        modifier.fillMaxHeight().clip(shape)
            .background(if (selected) pal.signal else Color.Transparent)
            .border(1.dp, if (selected) pal.signal else pal.line, shape)
            .semantics { contentDescription = name }
            .clickable(role = Role.Tab, onClick = onClick).testTag("bottom-page-${page.id}")
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val tint = if (selected) pal.onSignal else colors.onBackground
        Icon(page.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(name, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}
