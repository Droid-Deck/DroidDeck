package com.droiddeck.launcher.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import java.net.URLEncoder

internal data class GameResource(@StringRes val label: Int, val url: String, @StringRes val detail: Int? = null)

internal fun gameResources(title: String, game: Library.SteamGame?): List<GameResource> {
    if (title.isBlank()) return emptyList()
    val query = URLEncoder.encode(title.trim(), "UTF-8").replace("+", "%20")
    val steamId = game?.takeIf { Library.fromSteamClient(it) && it.appId > 0 }?.appId
    return buildList {
        add(GameResource(R.string.game_resource_pcgw, "https://www.pcgamingwiki.com/w/index.php?search=$query"))
        add(GameResource(R.string.game_resource_guides, "https://gamefaqs.gamespot.com/search?game=$query"))
        if (steamId != null) {
            add(GameResource(R.string.game_resource_protondb, "https://www.protondb.com/app/$steamId", R.string.game_resource_protondb_detail))
            add(GameResource(R.string.game_resource_steam_cloud, "https://store.steampowered.com/account/remotestorageapp/?appid=$steamId", R.string.game_resource_steam_cloud_detail))
        }
        add(GameResource(R.string.game_resource_search, "https://www.google.com/search?q=$query"))
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun GameResourcesRow(title: String, game: Library.SteamGame?, modifier: Modifier = Modifier) {
    val links = remember(title, game) { gameResources(title, game) }
    if (links.isEmpty()) return
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        links.forEach { ResourceLink(it) }
    }
}

@Composable
private fun ResourceLink(link: GameResource) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val hot = rememberHot(source)
    val glass = LocalGlass.current != null
    val shape = if (glass) RoundedCornerShape(99.dp) else RoundedCornerShape(8.dp)
    val label = stringResource(link.label)
    val description = listOfNotNull(label, link.detail?.let { stringResource(it) }, stringResource(R.string.game_resources_browser)).joinToString(". ")
    val open = { openUrl(ctx, link.url) }
    val tint = if (hot) pal.signal else pal.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.paneItem("resource:${link.label}").heightIn(min = 44.dp).clip(shape)
            .background(if (hot) pal.signal.copy(alpha = 0.14f) else if (glass) Color.White.copy(alpha = 0.07f) else Color.Transparent)
            .glideBorder(hot, shape, pal.signal, restColor = if (glass) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .semantics { contentDescription = description }
            .hoverable(source).clickable(interactionSource = source, indication = LocalIndication.current, role = Role.Button, onClick = open)
            .controllerConfirm(onClick = open).padding(horizontal = if (glass) 14.dp else 8.dp),
    ) {
        Text(label, fontSize = if (glass) 13.sp else 12.sp, fontWeight = FontWeight.Medium, color = if (glass && !hot) MaterialTheme.colorScheme.onBackground else tint, maxLines = 1)
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = tint.copy(alpha = 0.65f), modifier = Modifier.size(11.dp))
    }
}
