package com.droiddeck.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreTab
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.arrangeStoreTabs

internal object StoreTabsState {
    var tabs by mutableStateOf<List<StoreTab>>(emptyList())
        private set

    fun load(context: android.content.Context) {
        SessionPrefs.settleStoresTabOrder(context, Store.entries.map { it.id })
        tabs = arrangeStoreTabs(SessionPrefs.storesTabOrder(context), SessionPrefs.storesTabHidden(context))
    }

    fun reorder(context: android.content.Context, ids: List<String>) {
        val byId = tabs.associateBy { it.store.id }
        tabs = ids.mapNotNull { byId[it] }
        save(context, arranged = true)
    }

    // The Stores tab needs at least one shown store to open on.
    fun setShown(context: android.content.Context, store: Store, show: Boolean): Boolean {
        if (!show && tabs.count { it.shown } <= 1) return false
        tabs = tabs.map { if (it.store == store) it.copy(shown = show) else it }
        save(context, arranged = false)
        return true
    }

    private fun save(context: android.content.Context, arranged: Boolean) =
        SessionPrefs.saveStoresTabs(context, tabs.map { it.store.id }, tabs.filterNot { it.shown }.map { it.store.id }.toSet(), arranged)
}

internal object ManageStores {
    var open by mutableStateOf(false)
    var returnTo: FocusRequester? = null
}

internal enum class StoreAccount { SIGNED_IN, SIGNED_OUT, EXPIRED, NEEDS_KEY }

internal fun storeAccount(store: Store): StoreAccount = when {
    StoresState.isSignedIn(store) && StoresState.expired[store] == true -> StoreAccount.EXPIRED
    StoresState.isSignedIn(store) -> StoreAccount.SIGNED_IN
    store == Store.ITCH -> StoreAccount.NEEDS_KEY
    else -> StoreAccount.SIGNED_OUT
}

internal fun startStoreSignIn(context: android.content.Context, store: Store, at: Rect?, start: Color, onItchKey: () -> Unit) {
    when {
        store == Store.ITCH -> onItchKey()
        at == null || Motion.scale == 0f || StoresMotion.signIn != null -> StoresState.signIn(context, store)
        else -> StoresMotion.signIn = SignInFlood(store, at, start, sourceColours(store.id).dot)
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun SideSheet(open: Boolean, onClose: () -> Unit, returnFocus: FocusRequester?, tag: String, content: @Composable ColumnScope.() -> Unit) {
    val pal = LocalPalette.current
    BackHandler(enabled = open) { onClose() }
    var wasOpen by remember { mutableStateOf(false) }
    val closeInput = androidx.compose.ui.platform.LocalInputModeManager.current
    LaunchedEffect(open) {
        if (open) wasOpen = true
        else if (wasOpen && returnFocus != null && closeInput.inputMode == androidx.compose.ui.input.InputMode.Keyboard) focusSoon { returnFocus }
    }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                    .focusProperties { canFocus = false }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() },
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            val width = minOf(560.dp, maxWidth * 0.92f)
            AnimatedVisibility(open, enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut()) {
                val inside = remember { FocusRequester() }
                var holds by remember { mutableStateOf(false) }
                val openInput = androidx.compose.ui.platform.LocalInputModeManager.current
                // The launcher only takes focus in controller mode, so opening by touch switches to it like startup does.
                LaunchedEffect(Unit) {
                    repeat(60) {
                        androidx.compose.runtime.withFrameNanos { }
                        if (holds || !open) return@LaunchedEffect
                        if (openInput.inputMode != androidx.compose.ui.input.InputMode.Keyboard) openInput.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
                        runCatching { inside.requestFocus() }
                    }
                }
                Column(
                    Modifier.width(width).fillMaxHeight()
                        .onFocusChanged { holds = it.hasFocus }
                        .focusRequester(inside)
                        .focusProperties { exit = { if (open) FocusRequester.Cancel else FocusRequester.Default } }
                        .focusGroup()
                        .background(MaterialTheme.colorScheme.background)
                        .border(1.dp, pal.line).padding(horizontal = 18.dp, vertical = 16.dp).testTag(tag),
                    content = content,
                )
            }
        }
    }
}

private val STORE_ROW = 60.dp
private val STORE_HANDLE = 44.dp

@Composable
internal fun ManageStoresSheet() {
    val ctx = LocalContext.current
    LaunchedEffect(ManageStores.open) { if (ManageStores.open) StoreTabsState.load(ctx) }
    SideSheet(ManageStores.open, onClose = { ManageStores.open = false }, returnFocus = ManageStores.returnTo, tag = "manage-stores-sheet") {
        PageHeader(stringResource(R.string.library_manage_stores)) {
            Spacer(Modifier.weight(1f))
            SmallIconButton(Icons.Filled.Close, stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.onSurfaceVariant) {
                ManageStores.open = false
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SortableRows(
                rows = { StoreTabsState.tabs.map { it.store.id } },
                commit = { StoreTabsState.reorder(ctx, it) },
                movable = { true },
                rowHeight = STORE_ROW, handleWidth = STORE_HANDLE,
                onActivate = { id ->
                    val tab = StoreTabsState.tabs.firstOrNull { it.store.id == id } ?: return@SortableRows
                    StoreTabsState.setShown(ctx, tab.store, !tab.shown)
                },
            ) { id, held, offset, hint, modifier, onFocus ->
                val tab = StoreTabsState.tabs.firstOrNull { it.store.id == id }
                if (tab != null) StoreSortRow(tab, held, offset, hint, modifier, onFocus)
            }
        }
    }
}

@Composable
private fun StoreSortRow(
    tab: StoreTab, held: Boolean, offset: Float, hint: String?, modifier: Modifier, onFocus: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val store = tab.store
    val account = storeAccount(store)
    val name = StoresState.accounts[store]
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val note = when (account) {
        StoreAccount.SIGNED_IN -> name ?: ""
        StoreAccount.SIGNED_OUT -> stringResource(R.string.store_account_signed_out)
        StoreAccount.EXPIRED -> stringResource(R.string.store_account_expired)
        StoreAccount.NEEDS_KEY -> stringResource(R.string.store_account_api_key)
    }
    Column(
        modifier.fillMaxWidth().height(STORE_ROW).testTag("store-row-${store.id}")
            .zIndex(if (held) 1f else 0f)
            .graphicsLayer { translationY = offset }
            .background(if (held) colors.surfaceVariant else colors.surface)
            .glideBorder(hot, Shape12, pal.signal)
            .onFocusChanged { onFocus(it.isFocused) }
            .hoverable(src)
            .focusable(interactionSource = src),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().weight(1f).padding(end = 10.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(width = STORE_HANDLE, height = STORE_ROW).testTag("store-handle-${store.id}")) {
                Icon(Icons.Filled.DragHandle, null, tint = if (held) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f).alpha(if (tab.shown) 1f else 0.5f)) {
                Box(Modifier.size(10.dp).background(sourceColours(store.id).dot.copy(alpha = if (account == StoreAccount.SIGNED_IN) 1f else 0.4f), CircleShape))
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(store.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (note.isNotEmpty()) Text(
                        note, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (account == StoreAccount.EXPIRED) colors.error else colors.onSurfaceVariant,
                    )
                }
            }
            if (hint != null) KeyHint("Y", hint)
            ToggleSwitch(
                tab.shown, true, store.label,
                Modifier.testTag("store-shown-${store.id}").focusProperties { canFocus = false },
            ) { StoreTabsState.setShown(ctx, store, it) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (held) Color.Transparent else pal.line))
    }
}
