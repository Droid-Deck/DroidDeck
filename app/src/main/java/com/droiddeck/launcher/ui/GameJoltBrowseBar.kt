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
            BrowseDropdown(sectionLabel(query.section)) { first, close ->
                GameJoltBrowse.sections.forEachIndexed { index, section ->
                    MenuItem(sectionLabel(section), query.section == section, focusRequester = if (index == 0) first else null) {
                        GameJoltBrowseState.choose(ctx, query.copy(section = section))
                        close()
                    }
                }
            }
            BrowseDropdown(priceLabel(query.price)) { first, close ->
                GameJoltBrowse.prices.forEachIndexed { index, price ->
                    MenuItem(priceLabel(price), query.price == price, focusRequester = if (index == 0) first else null) {
                        GameJoltBrowseState.choose(ctx, query.copy(price = price))
                        close()
                    }
                }
            }
            val anyPlatform = stringResource(R.string.stores_itch_platform_any)
            BrowseDropdown(browseSummary(GameJoltBrowse.systems.filter { it in query.os }.map { systemLabel(it) }, anyPlatform)) { first, _ ->
                MenuItem(anyPlatform, query.os.isEmpty(), focusRequester = first) { GameJoltBrowseState.choose(ctx, query.copy(os = emptySet())) }
                for (os in GameJoltBrowse.systems) MenuItem(systemLabel(os), os in query.os) { GameJoltBrowseState.choose(ctx, query.copy(os = toggle(query.os, os))) }
            }
            BrowseDropdown(browseSummary(GameJoltBrowse.browsers.filter { it in query.browser }.map { browserLabel(it) }, stringResource(R.string.stores_gamejolt_filter_browser))) { first, _ ->
                GameJoltBrowse.browsers.forEachIndexed { index, browser ->
                    MenuItem(browserLabel(browser), browser in query.browser, focusRequester = if (index == 0) first else null) { GameJoltBrowseState.choose(ctx, query.copy(browser = toggle(query.browser, browser))) }
                }
            }
            val maturities = GameJoltBrowse.maturities.filter { showMature || it != "adult" }
            BrowseDropdown(browseSummary(maturities.filter { it in query.maturity }.map { maturityLabel(it) }, stringResource(R.string.stores_gamejolt_filter_maturity))) { first, _ ->
                maturities.forEachIndexed { index, maturity ->
                    MenuItem(maturityLabel(maturity), maturity in query.maturity, focusRequester = if (index == 0) first else null) { GameJoltBrowseState.choose(ctx, query.copy(maturity = toggle(query.maturity, maturity))) }
                }
            }
            BrowseDropdown(browseSummary(GameJoltBrowse.statuses.filter { it in query.status }.map { statusLabel(it) }, stringResource(R.string.stores_gamejolt_filter_status))) { first, _ ->
                GameJoltBrowse.statuses.forEachIndexed { index, status ->
                    MenuItem(statusLabel(status), status in query.status, focusRequester = if (index == 0) first else null) { GameJoltBrowseState.choose(ctx, query.copy(status = toggle(query.status, status))) }
                }
            }
            val partners = stringResource(R.string.stores_gamejolt_partners)
            BrowseDropdown(if (query.partners) partners else stringResource(R.string.stores_gamejolt_filter_partners)) { first, _ ->
                MenuItem(partners, query.partners, focusRequester = first) { GameJoltBrowseState.choose(ctx, query.copy(partners = !query.partners)) }
            }
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
