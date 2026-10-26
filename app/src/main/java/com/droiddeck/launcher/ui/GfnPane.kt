package com.droiddeck.launcher.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.gfn.GfnGame
import com.droiddeck.launcher.stores.gfn.GfnState
import com.droiddeck.launcher.stores.gfn.GfnStoreAccount

/** The GeForce NOW tab's chip key; it sits after the store tabs and is not a [com.droiddeck.launcher.stores.Store]. */
internal const val GEFORCE_NOW = "geforce-now"

internal val GfnGreen = Color(0xFF76B900)
internal val GfnColours = SourceColours(Color(0xFF1C2A0A), Color(0xFFCDEB8B), GfnGreen)

internal fun gfnPalette(base: Palette): Palette = Palette(
    base.id, base.label, base.detail, base.background, base.surface, base.surfaceVariant, base.line, base.line2,
    base.onBackground, base.onSurfaceVariant, primary = GfnGreen, primary2 = Color(0xFF5A8F00), onPrimary = Color(0xFF0B0B0D), signal = GfnGreen,
    good = base.good, error = base.error,
)

@Composable
internal fun GfnTheme(content: @Composable () -> Unit) {
    val base = LocalPalette.current
    val p = remember(base) { gfnPalette(base) }
    val scheme = MaterialTheme.colorScheme.copy(primary = p.primary, onPrimary = p.onPrimary)
    CompositionLocalProvider(LocalPalette provides p) { MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, content = content) }
}

@Composable
internal fun GfnPane(height: Dp) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { GfnState.start(ctx) }
    when (val phase = GfnState.phase) {
        GfnState.Phase.Loading -> Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        }
        is GfnState.Phase.Failed -> Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Note(phase.message)
            PrimaryButton(stringResource(R.string.gfn_try_again), main = true) { GfnState.start(ctx, force = true) }
        }
        is GfnState.Phase.SignedOut, is GfnState.Phase.Code -> Box(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 16.dp)) {
            GfnSignInCard(phase, height)
        }
        is GfnState.Phase.SignedIn -> GfnStorefront(phase.user)
    }
}

@Composable
private fun GfnSignInCard(phase: GfnState.Phase, height: Dp) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val narrow = LocalNarrowPane.current
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.fillMaxWidth().heightIn(min = height.coerceAtLeast(0.dp)).clip(shape)
            .background(Brush.linearGradient(listOf(GfnColours.fill, colors.background))).border(1.dp, pal.line, shape)
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(GfnGreen.copy(alpha = 0.28f), Color.Transparent), center = Offset(size.width * 0.16f, size.height * 0.9f), radius = size.height * 0.8f))
            },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = if (narrow) 20.dp else 40.dp, bottom = if (narrow) 20.dp else 36.dp, end = 20.dp, top = 20.dp),
        ) {
            Rise(2) {
                Text(
                    stringResource(R.string.gfn_name), fontSize = if (narrow) 30.sp else 42.sp, lineHeight = if (narrow) 34.sp else 46.sp,
                    fontWeight = FontWeight.Black, color = colors.onBackground,
                )
            }
            Text(stringResource(R.string.gfn_pitch), fontSize = 15.sp, color = colors.onBackground)
            Text(stringResource(R.string.gfn_unofficial), fontSize = 12.sp, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            when (phase) {
                is GfnState.Phase.Code -> {
                    Text(stringResource(R.string.gfn_code_hint, phase.url), fontSize = 14.sp, color = colors.onBackground)
                    Text(phase.userCode, fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp, color = GfnGreen)
                    Actions {
                        PrimaryButton(stringResource(R.string.gfn_open_page), main = true) { openUrl(ctx, phase.url) }
                        SecondaryButton(stringResource(R.string.common_cancel)) { GfnState.cancelSignIn() }
                    }
                    Text(stringResource(R.string.gfn_code_waiting), fontSize = 12.sp, color = colors.onSurfaceVariant)
                }
                else -> {
                    (phase as? GfnState.Phase.SignedOut)?.error?.let { Text(it, fontSize = 13.sp, color = colors.error) }
                    Rise(3) {
                        PrimaryButton(stringResource(R.string.stores_signin), main = true, large = true, icon = Icons.AutoMirrored.Filled.Login) { GfnState.signIn(ctx) }
                    }
                }
            }
        }
    }
}

private const val MODE_STORE = 0
private const val MODE_LIBRARY = 1
private const val MODE_ACCOUNTS = 2

@Composable
private fun GfnStorefront(user: String) {
    val ctx = LocalContext.current
    var mode by rememberSaveable { mutableIntStateOf(MODE_STORE) }
    var query by rememberSaveable { mutableStateOf("") }
    val grid = rememberLazyGridState()
    LaunchedEffect(mode) {
        when (mode) {
            MODE_LIBRARY -> GfnState.loadLibrary(ctx)
            MODE_ACCOUNTS -> GfnState.loadAccounts(ctx)
        }
        grid.scrollToItem(0)
    }
    LaunchedEffect(Unit) { GfnState.loadLibrary(ctx) }
    LaunchedEffect(mode, query) { if (mode == MODE_STORE) GfnState.showStore(ctx, query) }
    val nearEnd by remember {
        derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= grid.layoutInfo.totalItemsCount - 10
        }
    }
    LaunchedEffect(nearEnd, GfnState.storeGames.size, mode) {
        if (mode == MODE_STORE && nearEnd && GfnState.storeGames.isNotEmpty()) GfnState.loadStore(ctx, query, reset = false)
    }
    val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
    val library = GfnState.library
    val libraryShown = remember(library, query) { library.orEmpty().filter { game -> words.all { game.search.contains(it) } } }
    val full: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(CardWidth), state = grid, modifier = Modifier.fillMaxSize().focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
    ) {
        item(key = "profile", span = full) { Rise(1) { GfnProfile(user, library?.size, onRefresh = {
            when (mode) {
                MODE_STORE -> GfnState.loadStore(ctx, query, reset = true)
                MODE_LIBRARY -> GfnState.loadLibrary(ctx, force = true)
                else -> GfnState.loadAccounts(ctx)
            }
        }) } }
        item(key = "modes", span = full) {
            Rise(2) {
                val pills: @Composable () -> Unit = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        GfnModePill(stringResource(R.string.gfn_mode_store), mode == MODE_STORE, "store") { mode = MODE_STORE; query = "" }
                        GfnModePill(stringResource(R.string.gfn_mode_library), mode == MODE_LIBRARY, "library") { mode = MODE_LIBRARY; query = "" }
                        GfnModePill(stringResource(R.string.gfn_mode_accounts), mode == MODE_ACCOUNTS, "accounts") { mode = MODE_ACCOUNTS; query = "" }
                    }
                }
                if (mode == MODE_ACCOUNTS) Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.heightIn(min = 52.dp).padding(vertical = 4.dp), contentAlignment = Alignment.CenterStart) { pills() }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(LocalPalette.current.line))
                } else ModeHeader(
                    stringResource(R.string.gfn_name), query, { query = it },
                    placeholder = if (mode == MODE_STORE) stringResource(R.string.gfn_search_store, GfnState.storeTotal)
                    else stringResource(R.string.gfn_search_library, library?.size ?: 0),
                    lead = pills,
                ) {}
            }
        }
        when (mode) {
            MODE_STORE -> {
                GfnState.storeError?.let { line -> item(key = "error", span = full) { Note(line) } }
                val games = GfnState.storeGames
                items(games, key = { "gfn:${it.id}" }, contentType = { "game" }) { game -> GfnCard(game, first = game.id == games.first().id, showOwned = true) }
                if (GfnState.storeLoading) item(key = "loading", span = full) {
                    if (games.isEmpty()) Note(stringResource(R.string.gfn_loading_store))
                    else androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
                } else if (games.isEmpty() && GfnState.storeError == null) item(key = "empty", span = full) { GfnEmpty(stringResource(R.string.stores_nothing_matches)) }
            }
            MODE_LIBRARY -> {
                GfnState.libraryError?.let { line -> item(key = "error", span = full) { Note(line) } }
                when {
                    library == null && GfnState.libraryError == null -> item(key = "loading", span = full) { Note(stringResource(R.string.gfn_loading_library)) }
                    library?.isEmpty() == true -> item(key = "empty", span = full) { Note(stringResource(R.string.gfn_library_empty)) }
                    libraryShown.isEmpty() && library != null -> item(key = "nothing", span = full) { GfnEmpty(stringResource(R.string.stores_nothing_matches)) }
                    else -> items(libraryShown, key = { "gfn:${it.id}" }, contentType = { "game" }) { game ->
                        GfnCard(game, first = game.id == libraryShown.first().id, showOwned = false)
                    }
                }
            }
            else -> {
                item(key = "note", span = full) {
                    Text(stringResource(R.string.gfn_accounts_note), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 2.dp))
                }
                GfnState.accountsMessage?.let { line -> item(key = "message", span = full) { Note(line) } }
                val accounts = GfnState.accounts
                if (accounts == null) item(key = "loading", span = full) { androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp)) }
                else items(accounts, key = { "account:${it.provider}" }, span = { full() }) { account -> GfnAccountRow(account) }
            }
        }
    }
}

@Composable
private fun GfnEmpty(text: String) {
    Box(Modifier.padding(vertical = 32.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GfnProfile(user: String, owned: Int?, onRefresh: () -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).clip(shape).background(colors.surface).border(1.dp, pal.line, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp).clip(CircleShape).background(GfnGreen)) {
                Text(user.take(1).uppercase().ifEmpty { "G" }, fontSize = 16.sp, fontWeight = FontWeight.Black, color = Color.Black)
            }
            Column(Modifier.weight(1f)) {
                Text(user, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (owned != null) stringResource(R.string.gfn_profile_line, owned) else stringResource(R.string.gfn_name),
                    fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Actions {
                SecondaryButton(stringResource(R.string.stores_refresh), compact = true) { onRefresh() }
                ConfirmButton(stringResource(R.string.stores_signout_go), stringResource(R.string.gfn_signout_confirm), compact = true) { GfnState.signOut(ctx) }
            }
        }
        Text(stringResource(R.string.gfn_unofficial), fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 2)
    }
}

@Composable
private fun GfnModePill(label: String, on: Boolean, key: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(99.dp)
    Text(
        label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        color = if (on) colors.onPrimary else if (hot) colors.onBackground else colors.onSurfaceVariant,
        modifier = Modifier.paneItem("gfnmode:$key").clip(shape)
            .background(if (on) pal.signal else Color.Transparent)
            .glideBorder(hot, shape, pal.signal, if (on) Color.Transparent else pal.line)
            .hoverable(src).clickable(role = Role.Tab, interactionSource = src, indication = LocalIndication.current, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun GfnCard(game: GfnGame, first: Boolean, showOwned: Boolean) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "gfnCardScale")
    val play = { GfnState.play(ctx, game) }
    Column(
        Modifier.fillMaxWidth().paneItem("card:gfn:${game.id}").then(if (first) Modifier.firstTile() else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp)).glideBorder(hot, RoundedCornerShape(10.dp), pal.signal, pal.line)
            .hoverable(src).clickable(role = Role.Button, interactionSource = src, indication = LocalIndication.current, onClick = play)
            .controllerConfirm(onClick = play),
    ) {
        CardFace(game.title, game.imageUrl.ifEmpty { null }, null, null, hot) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (showOwned && game.inLibrary) Icon(Icons.Filled.Check, null, tint = GfnGreen, modifier = Modifier.size(11.dp))
                Text(game.store, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
            }
        }
    }
}

@Composable
private fun GfnAccountRow(account: GfnStoreAccount) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    val busy = GfnState.accountsBusy
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().clip(shape).background(colors.surface).border(1.dp, pal.line, shape).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp).clip(Shape12).background(if (account.connected) GfnColours.fill else colors.surfaceVariant)) {
            Text(account.label.take(1).uppercase(), fontSize = 15.sp, fontWeight = FontWeight.Black, color = if (account.connected) GfnColours.ink else colors.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Text(account.label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    account.status == "expired" -> stringResource(R.string.gfn_status_expired)
                    account.status == "sync_error" -> stringResource(R.string.gfn_status_sync_error)
                    account.connected && account.displayName.isNotEmpty() -> stringResource(R.string.gfn_status_connected_as, account.displayName)
                    account.connected -> stringResource(R.string.gfn_status_connected)
                    else -> stringResource(R.string.gfn_status_not_connected)
                },
                fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            when (GfnState.actionFor(account)) {
                "link" -> PrimaryButton(stringResource(if (account.connected) R.string.gfn_reconnect else R.string.gfn_connect), enabled = !busy, compact = true) { GfnState.link(ctx, account) }
                "sync" -> SecondaryButton(stringResource(R.string.gfn_sync), enabled = !busy, compact = true) { GfnState.sync(ctx, account) }
                "unlink" -> ConfirmButton(stringResource(R.string.gfn_disconnect), stringResource(R.string.gfn_disconnect_confirm), compact = true) { GfnState.unlink(ctx, account) }
                else -> if (account.provider == "STEAM" && !account.connected)
                    Text(stringResource(R.string.gfn_steam_link), fontSize = 12.sp, color = colors.onSurfaceVariant)
            }
        }
    }
}
