package com.droiddeck.launcher.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.itch.ItchBrowse
import com.droiddeck.launcher.stores.itch.ItchBrowseState

@Composable
internal fun ItchBrowseBar() {
    val ctx = LocalContext.current
    val query = ItchBrowseState.query
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ChipRow {
            for (sort in ItchBrowse.sorts) BrowseChip(sortLabel(sort), query.sort == sort) { ItchBrowseState.choose(ctx, query.copy(sort = sort)) }
        }
        ChipRow {
            for (price in ItchBrowse.prices) BrowseChip(priceLabel(price), query.price == price) { ItchBrowseState.choose(ctx, query.copy(price = price)) }
        }
        ChipRow {
            BrowseChip(stringResource(R.string.stores_itch_platform_any), query.platforms.isEmpty()) { ItchBrowseState.choose(ctx, query.copy(platforms = emptySet())) }
            for (platform in ItchBrowse.platformNames) {
                val on = platform in query.platforms
                BrowseChip(platformLabel(platform), on) { ItchBrowseState.choose(ctx, query.copy(platforms = if (on) query.platforms - platform else query.platforms + platform)) }
            }
        }
        ChipRow {
            for (tag in query.tags) BrowseChip("$tag  ✕", true) { ItchBrowseState.choose(ctx, query.copy(tags = query.tags - tag)) }
            BrowseChip(stringResource(R.string.stores_itch_tag_add), false) { picking = true }
        }
    }
    if (picking) TextEntryDialog(
        stringResource(R.string.stores_itch_tag_title), "", number = false, confirm = stringResource(R.string.common_ok),
        onSubmit = { value -> ItchBrowse.tagSlug(value).takeIf { it.isNotEmpty() }?.let { ItchBrowseState.choose(ctx, query.copy(tags = (query.tags + it).distinct())) } },
        onDismiss = { picking = false }, id = "itch-tag",
    )
}

@Composable
internal fun ItchLoadMore() {
    val ctx = LocalContext.current
    val count = ItchBrowseState.items.size
    LaunchedEffect(Unit) { ItchBrowseState.more(ctx) }
    if (ItchBrowseState.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
    else if (ItchBrowseState.failed) Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        BrowseChip(stringResource(R.string.stores_dl_retry), false) { ItchBrowseState.more(ctx, retry = true) }
    }
    else if (!ItchBrowseState.ended && count > 0) Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        BrowseChip(stringResource(R.string.stores_itch_load_more), false) { ItchBrowseState.more(ctx) }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
    ) { content() }
}

@Composable
private fun BrowseChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(8.dp)
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Text(
        label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        color = if (selected) pal.onSignal else if (hot) colors.onBackground else colors.onSurfaceVariant,
        modifier = Modifier.clip(shape).background(if (selected) pal.signal else colors.surfaceVariant)
            .glideBorder(hot, shape, pal.signal)
            .hoverable(src).selectable(selected = selected, interactionSource = src, indication = LocalIndication.current, role = Role.Tab, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun sortLabel(sort: String): String = stringResource(
    when (sort) {
        ItchBrowse.SORT_NEW_POPULAR -> R.string.stores_itch_sort_new_popular
        ItchBrowse.SORT_TOP_SELLERS -> R.string.stores_itch_sort_top_sellers
        ItchBrowse.SORT_TOP_RATED -> R.string.stores_itch_sort_top_rated
        ItchBrowse.SORT_NEWEST -> R.string.stores_itch_sort_new
        else -> R.string.stores_itch_sort_popular
    },
)

@Composable
private fun platformLabel(platform: String): String = stringResource(
    when (platform) {
        "windows" -> R.string.stores_itch_platform_windows
        "osx" -> R.string.stores_itch_platform_macos
        "linux" -> R.string.stores_itch_platform_linux
        "android" -> R.string.stores_itch_platform_android
        "ios" -> R.string.stores_itch_platform_ios
        "web" -> R.string.stores_itch_platform_web
        else -> R.string.stores_itch_platform_mobile_web
    },
)

@Composable
private fun priceLabel(price: String): String = stringResource(
    when (price) {
        ItchBrowse.PRICE_FREE -> R.string.stores_itch_price_free
        ItchBrowse.PRICE_SALE -> R.string.stores_itch_price_sale
        ItchBrowse.PRICE_PAID -> R.string.stores_itch_price_paid
        ItchBrowse.PRICE_UNDER_5 -> R.string.stores_itch_price_under_5
        ItchBrowse.PRICE_UNDER_15 -> R.string.stores_itch_price_under_15
        else -> R.string.stores_itch_price_any
    },
)
