package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.gamejolt.GameJoltBrowse
import com.droiddeck.launcher.stores.gamejolt.GameJoltBrowseState
import com.droiddeck.launcher.stores.gamejolt.GameJoltQuery

@Composable
internal fun GameJoltBrowseBar(showMature: Boolean) {
    val ctx = LocalContext.current
    val query = GameJoltBrowseState.query
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (GameJoltBrowseState.items.isEmpty()) GameJoltBrowseState.more(ctx) }
    fun toggle(set: Set<String>, value: String): Set<String> = if (value in set) set - value else set + value
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ChipRow {
            for (section in GameJoltBrowse.sections) BrowseChip(sectionLabel(section), query.section == section) { GameJoltBrowseState.choose(ctx, query.copy(section = section)) }
        }
        ChipRow {
            for (price in GameJoltBrowse.prices) BrowseChip(priceLabel(price), query.price == price) { GameJoltBrowseState.choose(ctx, query.copy(price = price)) }
        }
        ChipRow {
            BrowseChip(stringResource(R.string.stores_itch_platform_any), query.os.isEmpty()) { GameJoltBrowseState.choose(ctx, query.copy(os = emptySet())) }
            for (os in GameJoltBrowse.systems) BrowseChip(systemLabel(os), os in query.os) { GameJoltBrowseState.choose(ctx, query.copy(os = toggle(query.os, os))) }
        }
        ChipRow {
            for (browser in GameJoltBrowse.browsers) BrowseChip(browserLabel(browser), browser in query.browser) { GameJoltBrowseState.choose(ctx, query.copy(browser = toggle(query.browser, browser))) }
        }
        ChipRow {
            for (maturity in GameJoltBrowse.maturities.filter { showMature || it != "adult" }) BrowseChip(maturityLabel(maturity), maturity in query.maturity) { GameJoltBrowseState.choose(ctx, query.copy(maturity = toggle(query.maturity, maturity))) }
        }
        ChipRow {
            for (status in GameJoltBrowse.statuses) BrowseChip(statusLabel(status), status in query.status) { GameJoltBrowseState.choose(ctx, query.copy(status = toggle(query.status, status))) }
            BrowseChip(stringResource(R.string.stores_gamejolt_partners), query.partners) { GameJoltBrowseState.choose(ctx, query.copy(partners = !query.partners)) }
        }
        ChipRow {
            for (tag in query.tags) BrowseChip("$tag  ✕", true) { GameJoltBrowseState.choose(ctx, query.copy(tags = query.tags - tag)) }
            BrowseChip(stringResource(R.string.stores_itch_tag_add), false) { picking = true }
        }
    }
    if (picking) TextEntryDialog(
        stringResource(R.string.stores_itch_tag_title), "", number = false, confirm = stringResource(R.string.common_ok),
        onSubmit = { value -> GameJoltBrowse.tagSlug(value).takeIf { it.isNotEmpty() }?.let { GameJoltBrowseState.choose(ctx, query.copy(tags = (query.tags + it).distinct())) } },
        onDismiss = { picking = false }, id = "gamejolt-tag",
    )
}

@Composable
internal fun GameJoltLoadMore() {
    val ctx = LocalContext.current
    val count = GameJoltBrowseState.items.size
    LaunchedEffect(Unit) { GameJoltBrowseState.more(ctx) }
    if (GameJoltBrowseState.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
    else if (GameJoltBrowseState.failed) Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        BrowseChip(stringResource(R.string.stores_dl_retry), false) { GameJoltBrowseState.more(ctx, retry = true) }
    }
    else if (!GameJoltBrowseState.ended && count > 0) Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        BrowseChip(stringResource(R.string.stores_itch_load_more), false) { GameJoltBrowseState.more(ctx) }
    }
}

@Composable
private fun sectionLabel(section: String): String = stringResource(
    when (section) {
        GameJoltBrowse.SECTION_HOT -> R.string.stores_gamejolt_section_hot
        GameJoltBrowse.SECTION_BEST -> R.string.stores_gamejolt_section_best
        GameJoltBrowse.SECTION_NEW -> R.string.stores_gamejolt_section_new
        else -> R.string.stores_gamejolt_section_featured
    },
)

@Composable
private fun priceLabel(price: String): String = stringResource(
    when (price) {
        GameJoltBrowse.PRICE_FREE -> R.string.stores_itch_price_free
        GameJoltBrowse.PRICE_SALE -> R.string.stores_itch_price_sale
        GameJoltBrowse.PRICE_PAID -> R.string.stores_itch_price_paid
        GameJoltBrowse.PRICE_UNDER_5 -> R.string.stores_itch_price_under_5
        GameJoltBrowse.PRICE_UNDER_15 -> R.string.stores_itch_price_under_15
        GameJoltBrowse.PRICE_UNDER_30 -> R.string.stores_gamejolt_price_under_30
        else -> R.string.stores_itch_price_any
    },
)

@Composable
private fun systemLabel(os: String): String = stringResource(
    when (os) {
        "windows" -> R.string.stores_itch_platform_windows
        "mac" -> R.string.stores_itch_platform_macos
        "linux" -> R.string.stores_itch_platform_linux
        else -> R.string.stores_gamejolt_os_other
    },
)

@Composable
private fun browserLabel(browser: String): String = when (browser) {
    "html" -> "HTML"
    "flash" -> "Flash"
    else -> "Unity"
}

@Composable
private fun maturityLabel(maturity: String): String = stringResource(
    when (maturity) {
        "everyone" -> R.string.stores_gamejolt_maturity_everyone
        "teen" -> R.string.stores_gamejolt_maturity_teen
        else -> R.string.stores_gamejolt_maturity_adult
    },
)

@Composable
private fun statusLabel(status: String): String = stringResource(
    when (status) {
        "complete" -> R.string.stores_gamejolt_status_complete
        "wip" -> R.string.stores_gamejolt_status_early
        else -> R.string.stores_gamejolt_status_devlog
    },
)
